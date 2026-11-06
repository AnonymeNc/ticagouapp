package nc.ticagou.backup;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Restauration complète d'une archive {@code .tcb} sur un serveur (ADR 0007) : c'est l'outil de la reprise après
 * sinistre, utilisé sans démarrer l'application (mode {@code restaurer} de {@link RestoreCli}).
 * <ol>
 *   <li>déchiffrement avec la clé de secours (une clé fausse ou une archive abîmée sont détectées) ;</li>
 *   <li>la base cible est recréée vide (refusé si elle contient déjà une crèche, sauf {@code replace}) ;</li>
 *   <li>{@code pg_restore} du dump, puis comparaison du nombre de lignes avec le manifeste ;</li>
 *   <li>les documents chiffrés remplacent ceux du dossier de stockage (l'ancien dossier est gardé à côté).</li>
 * </ol>
 * La clé des documents, celle des sauvegardes et les autres secrets du serveur sont dans la base : après la
 * restauration, le serveur relit les documents et continue de sauvegarder avec la même clé de secours.
 */
public class ArchiveRestorer {

    private static final Pattern JDBC_URL = Pattern.compile("jdbc:postgresql://([^:/]+)(?::(\\d+))?/([^?]+).*");
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_]+");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");

    /** Paramètres : ceux du service Windows (variables TICAGOU_*), plus l'archive et la clé de secours. */
    public record Options(Path archive, String recoveryKey, String jdbcUrl, String user, String password, Path pgBin,
                          Path storageDir, boolean replace) {
    }

    /** Contenu de l'archive déchiffrée : manifeste, dump et nombre de documents. */
    public record Opened(Path zip, Map<String, Object> manifest, int documents) {

        public boolean beforeMigration() {
            return "AVANT_MIGRATION".equals(manifest.get("kind"));
        }
    }

    public record Result(String createdAt, String schema, int tables, long rows, int documents, Path previousDocuments) {
    }

    private final ObjectMapper json = new ObjectMapper();
    private final PrintStream log;

    public ArchiveRestorer(PrintStream log) {
        this.log = log;
    }

    public Result restore(Options options) throws IOException, SQLException {
        var work = Files.createTempDirectory("ticagou-restauration");
        try {
            log.println("Déchiffrement de " + options.archive().getFileName() + "…");
            var opened = open(options.archive(), options.recoveryKey(), work);
            log.println("Sauvegarde du " + opened.manifest().getOrDefault("createdAt", "?") + ", schéma V"
                    + opened.manifest().getOrDefault("schema", "?") + (opened.beforeMigration() ? " (avant mise à jour)" : ""));

            var target = target(options.jdbcUrl());
            requireEmptyOrReplace(options, target);
            log.println("Recréation de la base « " + target.database() + " »…");
            recreate(options, target);
            var dump = work.resolve("database.dump");
            try (var zip = new ZipFile(opened.zip().toFile())) {
                Files.copy(zip.getInputStream(zip.getEntry("database.dump")), dump);
            }
            log.println("Restauration des données (pg_restore)…");
            pgRestore(options, target, dump);
            long rows = verify(options, opened.manifest());

            Path previous = null;
            if (opened.documents() > 0) {
                log.println("Restauration de " + opened.documents() + " document(s)…");
                previous = restoreDocuments(opened.zip(), options.storageDir());
            } else if (opened.beforeMigration()) {
                log.println("Sauvegarde avant mise à jour : elle ne contient pas les documents, le dossier actuel est gardé.");
            }
            @SuppressWarnings("unchecked")
            var tables = (Map<String, Object>) opened.manifest().getOrDefault("tables", Map.of());
            return new Result(String.valueOf(opened.manifest().get("createdAt")), String.valueOf(opened.manifest().get("schema")),
                    tables.size(), rows, opened.documents(), previous);
        } finally {
            BackupService.delete(work);
        }
    }

    /** Déchiffre l'archive dans {@code work} et lit son manifeste. */
    @SuppressWarnings("unchecked")
    Opened open(Path archive, String recoveryKey, Path work) throws IOException {
        if (!Files.isRegularFile(archive)) {
            throw new IOException("Archive introuvable : " + archive);
        }
        var zipPath = work.resolve("archive.zip");
        try (InputStream in = Files.newInputStream(archive); var out = Files.newOutputStream(zipPath)) {
            BackupCrypto.decrypt(in, out, recoveryKey);
        }
        try (var zip = new ZipFile(zipPath.toFile())) {
            var manifestEntry = zip.getEntry("manifest.json");
            if (manifestEntry == null || zip.getEntry("database.dump") == null) {
                throw new IOException("Archive incomplète : manifeste ou base absents.");
            }
            var manifest = json.readValue(zip.getInputStream(manifestEntry), Map.class);
            int documents = (int) zip.stream().filter(e -> !e.isDirectory() && e.getName().startsWith("documents/")).count();
            return new Opened(zipPath, manifest, documents);
        }
    }

    record Target(String host, int port, String database) {
    }

    static Target target(String jdbcUrl) {
        var m = JDBC_URL.matcher(jdbcUrl);
        if (!m.matches()) {
            throw new IllegalArgumentException("Adresse de base non reconnue : " + jdbcUrl);
        }
        var database = m.group(3);
        if (!SAFE_NAME.matcher(database).matches()) {
            throw new IllegalArgumentException("Nom de base inattendu : " + database);
        }
        return new Target(m.group(1), m.group(2) == null ? 5432 : Integer.parseInt(m.group(2)), database);
    }

    private Connection connect(Options options, Target target, String database) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://%s:%d/%s".formatted(target.host(), target.port(), database),
                options.user(), options.password());
    }

    /** Une base qui contient déjà une crèche n'est écrasée que sur demande explicite. */
    private void requireEmptyOrReplace(Options options, Target target) throws SQLException, IOException {
        if (options.replace()) {
            return;
        }
        try (var conn = connect(options, target, target.database()); var st = conn.createStatement()) {
            try (var rs = st.executeQuery("SELECT to_regclass('public.structure') IS NOT NULL")) {
                rs.next();
                if (!rs.getBoolean(1)) {
                    return;
                }
            }
            try (var rs = st.executeQuery("SELECT count(*) FROM structure")) {
                rs.next();
                if (rs.getLong(1) > 0) {
                    throw new IOException("La base « " + target.database() + " » contient déjà une crèche : relancez avec "
                            + "--remplacer pour l'écraser par la sauvegarde.");
                }
            }
        } catch (SQLException e) {
            if ("3D000".equals(e.getSQLState())) {
                return; // La base n'existe pas : elle sera créée.
            }
            throw e;
        }
    }

    private void recreate(Options options, Target target) throws SQLException {
        try (var conn = connect(options, target, "postgres"); var st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS \"" + target.database() + "\" WITH (FORCE)");
            st.execute("CREATE DATABASE \"" + target.database() + "\"");
        }
    }

    private void pgRestore(Options options, Target target, Path dump) throws IOException {
        var exe = executable(options.pgBin(), "pg_restore");
        var command = List.of(exe.toString(), "--no-owner", "--no-privileges", "--exit-on-error",
                "--host=" + target.host(), "--port=" + target.port(), "--username=" + options.user(),
                "--dbname=" + target.database(), dump.toString());
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("PGPASSWORD", options.password() == null ? "" : options.password());
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        try {
            if (process.waitFor() != 0) {
                throw new IOException("pg_restore a échoué : " + (output.isEmpty() ? "code " + process.exitValue() : output));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Restauration interrompue.", e);
        }
    }

    static Path executable(Path pgBin, String name) throws IOException {
        var exe = name + (System.getProperty("os.name").toLowerCase().contains("win") ? ".exe" : "");
        var candidates = new ArrayList<Path>();
        if (pgBin != null) {
            candidates.add(pgBin.resolve(exe));
        }
        candidates.add(Path.of("C:\\Program Files\\PostgreSQL\\17\\bin", exe));
        for (var dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            if (!dir.isBlank()) {
                candidates.add(Path.of(dir, exe));
            }
        }
        return candidates.stream().filter(Files::isRegularFile).findFirst()
                .orElseThrow(() -> new IOException(name + " est introuvable : indiquez le dossier des outils PostgreSQL (--pg-bin)."));
    }

    /** Compare le nombre de lignes de chaque table avec celui noté dans le manifeste au moment de la sauvegarde. */
    @SuppressWarnings("unchecked")
    private long verify(Options options, Map<String, Object> manifest) throws SQLException, IOException {
        var expected = (Map<String, Object>) manifest.getOrDefault("tables", Map.of());
        var target = target(options.jdbcUrl());
        var missing = new ArrayList<String>();
        long rows = 0;
        try (var conn = connect(options, target, target.database()); var st = conn.createStatement()) {
            for (var entry : expected.entrySet()) {
                if (!SAFE_NAME.matcher(entry.getKey()).matches()) {
                    continue;
                }
                try (var rs = st.executeQuery("SELECT count(*) FROM \"" + entry.getKey() + "\"")) {
                    rs.next();
                    rows += rs.getLong(1);
                    if (rs.getLong(1) < ((Number) entry.getValue()).longValue()) {
                        missing.add(entry.getKey());
                    }
                } catch (SQLException e) {
                    missing.add(entry.getKey());
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IOException("Tables incomplètes après restauration : " + String.join(", ", missing));
        }
        return rows;
    }

    /**
     * Remplace le dossier des documents par celui de l'archive ; l'ancien dossier, s'il n'est pas vide, est renommé
     * à côté ({@code documents.avant-restauration-<date>}). Les chemins qui sortiraient du dossier sont refusés.
     */
    Path restoreDocuments(Path zipPath, Path storageDir) throws IOException {
        var root = storageDir.toAbsolutePath().normalize();
        Path previous = null;
        if (Files.isDirectory(root)) {
            try (var content = Files.list(root)) {
                if (content.findAny().isPresent()) {
                    previous = root.resolveSibling(root.getFileName() + ".avant-restauration-" + LocalDateTime.now().format(STAMP));
                    Files.move(root, previous);
                }
            }
        }
        Files.createDirectories(root);
        try (var zip = new ZipFile(zipPath.toFile())) {
            for (var entry : zip.stream().filter(e -> !e.isDirectory() && e.getName().startsWith("documents/")).toList()) {
                var target = root.resolve(entry.getName().substring("documents/".length())).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("Chemin refusé dans l'archive : " + entry.getName());
                }
                Files.createDirectories(target.getParent());
                Files.copy(zip.getInputStream(entry), target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return previous;
    }

    static Optional<String> env(String name) {
        return Optional.ofNullable(System.getenv(name)).filter(v -> !v.isBlank());
    }
}
