package nc.ticagou.backup;

import nc.ticagou.notification.NativePush;
import nc.ticagou.notification.NotificationService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;
import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.audit.AuditService.Actor;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.backup.BackupDtos.Kind;
import nc.ticagou.backup.BackupDtos.Overview;
import nc.ticagou.backup.BackupDtos.RunDto;
import nc.ticagou.backup.BackupDtos.Status;
import nc.ticagou.common.ApiException;
import nc.ticagou.config.TiCagouProperties;

/**
 * Sauvegardes chiffrées de l'instance (ADR 0007, ADR 0013) : pg_dump de la base et documents chiffrés, réunis dans une
 * archive chiffrée avec la clé de secours, écrite dans le dossier de l'instance, avec une rotation 7 jours, 4 semaines,
 * 12 mois. L'hébergement copie ce dossier hors site (deploy/). Le test de restauration recharge la dernière archive
 * dans une base temporaire, vérifie les tables, puis la supprime : les données en service ne sont jamais touchées.
 */
@Service
public class BackupService {

    static final String KEY_SECRET = "backup_recovery_key";
    static final Pattern ARCHIVE = Pattern.compile("ticagou-(\\d{4}-\\d{2}-\\d{2})-(\\d{4})\\.tcb");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");
    private static final Logger LOG = LoggerFactory.getLogger(BackupService.class);

    private final JdbcTemplate jdbc;
    private final PgTools pg;
    private final TiCagouProperties properties;
    private final ObjectMapper json;
    private final AuditService audit;
    private final Clock clock;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "sauvegarde");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();

    private final NotificationService notifications;

    public BackupService(JdbcTemplate jdbc, PgTools pg, TiCagouProperties properties, ObjectMapper json,
                         AuditService audit, Clock clock, NotificationService notifications) {
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.pg = pg;
        this.properties = properties;
        this.json = json;
        this.audit = audit;
        this.clock = clock;
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    // ---------------------------------------------------------------- réglages

    record Settings(boolean enabled, int keepDays, int keepWeeks, int keepMonths, LocalDate lastTestOn,
                    Boolean lastTestOk) {
    }

    Settings settings() {
        return jdbc.queryForObject("""
                SELECT enabled, keep_days, keep_weeks, keep_months, last_test_on, last_test_ok FROM backup_settings
                WHERE id = 1""",
                (rs, n) -> new Settings(rs.getBoolean(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                        rs.getObject(5, LocalDate.class), (Boolean) rs.getObject(6)));
    }

    /** Délai au-delà duquel l'absence de sauvegarde réussie déclenche une alerte. */
    static final Duration STALE_AFTER = Duration.ofHours(48);

    /**
     * Vrai si aucune sauvegarde réussie depuis 48 h ; sans aucune sauvegarde, compté depuis l'installation. Les
     * sauvegardes désactivées ne suppriment pas l'alerte.
     */
    static boolean isStale(Instant lastSuccess, Instant installedAt, Instant now) {
        var since = lastSuccess != null ? lastSuccess : installedAt;
        return since != null && since.plus(STALE_AFTER).isBefore(now);
    }

    public Overview overview() {
        var s = settings();
        var history = jdbc.query("SELECT * FROM backup_run ORDER BY started_at DESC LIMIT 30", (rs, n) -> new RunDto(
                rs.getObject("id", UUID.class), Kind.valueOf(rs.getString("kind")), rs.getString("author"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                Status.valueOf(rs.getString("status")), (Long) rs.getObject("size_bytes"), rs.getString("file_name"),
                rs.getInt("copies"), rs.getString("detail")));
        var lastSuccess = history.stream()
                .filter(r -> (r.kind() == Kind.AUTOMATIQUE || r.kind() == Kind.MANUELLE) && r.status() == Status.REUSSIE).findFirst()
                .orElse(null);
        var installedAt = jdbc.query("SELECT initialized_at FROM installation", (rs, n) -> rs.getTimestamp(1).toInstant())
                .stream().findFirst().orElse(null);
        var stale = isStale(lastSuccess == null ? null : lastSuccess.startedAt(), installedAt, clock.instant());
        return new Overview(s.enabled(), properties.backup().dailyTime(), s.keepDays(), s.keepWeeks(), s.keepMonths(),
                s.lastTestOn(), s.lastTestOk(), pg.available(), running.get(), lastSuccess, s.enabled() ? nextRun() : null, stale,
                history);
    }

    private Instant nextRun() {
        var now = clock.instant().atZone(clock.getZone());
        var today = now.toLocalDate().atTime(properties.backup().dailyTime()).atZone(clock.getZone());
        return (today.isAfter(now) && !ranAutomatically(now.toLocalDate()) ? today : today.plusDays(1)).toInstant();
    }

    // ---------------------------------------------------------------- clé de secours

    /** Clé de l'instance, gardée par l'éditeur hors de la base ; en développement, créée en base à la première demande. */
    String recoveryKey(boolean create) {
        if (!properties.backup().recoveryKey().isBlank()) {
            return properties.backup().recoveryKey();
        }
        var existing = jdbc.query("SELECT value FROM server_secret WHERE name = ?",
                (rs, n) -> new String(rs.getBytes(1), StandardCharsets.UTF_8), KEY_SECRET);
        if (!existing.isEmpty() || !create) {
            return existing.isEmpty() ? null : existing.getFirst();
        }
        jdbc.update("INSERT INTO server_secret (name, value) VALUES (?, ?) ON CONFLICT (name) DO NOTHING",
                KEY_SECRET, BackupCrypto.newRecoveryKey().getBytes(StandardCharsets.UTF_8));
        return recoveryKey(false);
    }

    // ---------------------------------------------------------------- sauvegarde

    /** Sauvegarde à la demande, en arrière-plan ; l'historique montre sa progression. */
    public RunDto start() {
        var user = CurrentUser.get();
        if (!pg.available()) {
            throw ApiException.rule("backup_tools", "pg_dump est introuvable sur le serveur : réglez ticagou.backup.pg-bin.");
        }
        var actor = new Actor(user.structureId(), user.userId(), user.displayName());
        var run = begin(Kind.MANUELLE, user.displayName(), actor);
        executor.submit(() -> backup(run, actor));
        return find(run);
    }

    /**
     * Sauvegarde automatique de la nuit : une fois par jour, à partir de l'heure réglée (rattrapée si l'instance était
     * arrêtée). Une sauvegarde manuelle réussie depuis cette heure en tient lieu ; un échec n'est pas relancé avant le
     * lendemain.
     */
    public void runNightlyIfDue() {
        var s = settings();
        var now = clock.instant().atZone(clock.getZone());
        var dailyTime = properties.backup().dailyTime();
        if (!s.enabled() || now.toLocalTime().isBefore(dailyTime)
                || ranAutomatically(now.toLocalDate()) || savedManuallySince(now.toLocalDate().atTime(dailyTime))
                || running.get() || !pg.available()) {
            return;
        }
        var actor = Actor.anonymous();
        var run = begin(Kind.AUTOMATIQUE, null, actor);
        executor.submit(() -> backup(run, actor));
    }

    private boolean savedManuallySince(LocalDateTime from) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM backup_run WHERE kind = 'MANUELLE' AND status <> 'ECHEC' AND started_at >= ?)",
                Boolean.class, Timestamp.from(from.atZone(clock.getZone()).toInstant())));
    }

    private boolean ranAutomatically(LocalDate day) {
        var from = day.atStartOfDay(clock.getZone()).toInstant();
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM backup_run WHERE kind = 'AUTOMATIQUE' AND started_at >= ?)", Boolean.class,
                Timestamp.from(from)));
    }

    private UUID begin(Kind kind, String author, Actor actor) {
        if (!running.compareAndSet(false, true)) {
            throw ApiException.conflict("backup_running", "Une sauvegarde ou un test est déjà en cours.");
        }
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO backup_run (id, kind, author, started_at, status) VALUES (?, ?, ?, ?, 'EN_COURS')",
                id, kind.name(), author, Timestamp.from(clock.instant()));
        if (kind != Kind.TEST) {
            audit.record(actor, AuditAction.BACKUP_STARTED, "backup_run", id, Map.of("kind", kind.name()));
        }
        return id;
    }

    /** Exécution synchrone (appelée en arrière-plan ; directement par les tests). */
    void backup(UUID runId, Actor actor) {
        Path work = null;
        try {
            var s = settings();
            var key = recoveryKey(true);
            work = Files.createTempDirectory("ticagou-sauvegarde");
            var dump = work.resolve("database.dump");
            pg.dump(dump);
            var zip = work.resolve("archive.zip");
            try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
                out.putNextEntry(new ZipEntry("manifest.json"));
                out.write(json.writeValueAsBytes(manifest()));
                out.closeEntry();
                out.putNextEntry(new ZipEntry("database.dump"));
                Files.copy(dump, out);
                out.closeEntry();
                var documents = properties.storage().dir().toAbsolutePath().normalize();
                if (Files.isDirectory(documents)) {
                    try (var files = Files.walk(documents)) {
                        for (var file : files.filter(Files::isRegularFile).toList()) {
                            out.putNextEntry(new ZipEntry("documents/" + documents.relativize(file).toString().replace('\\', '/')));
                            Files.copy(file, out);
                            out.closeEntry();
                        }
                    }
                }
            }
            var name = "ticagou-%s.tcb".formatted(clock.instant().atZone(clock.getZone()).format(STAMP));
            var encrypted = work.resolve(name);
            try (InputStream in = Files.newInputStream(zip); var out = Files.newOutputStream(encrypted)) {
                BackupCrypto.encrypt(in, out, key);
            }
            var folder = properties.backup().dir();
            Files.createDirectories(folder);
            Files.copy(encrypted, folder.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            prune(folder, s);
            finish(runId, Status.REUSSIE, Files.size(encrypted), name, 1, null);
            audit.record(actor, AuditAction.BACKUP_SUCCEEDED, "backup_run", runId, Map.of("file", name));
        } catch (Exception e) {
            LOG.warn("Sauvegarde en échec", e);
            finish(runId, Status.ECHEC, null, null, 0, explain(e));
            audit.record(actor, AuditAction.BACKUP_FAILED, "backup_run", runId, Map.of("detail", explain(e)));
            notifyFailure(runId);
        } finally {
            delete(work);
            running.set(false);
        }
    }

    /** Sauvegarde de la nuit en échec : la direction est prévenue sur son téléphone (ADR 0026). */
    private void notifyFailure(UUID runId) {
        try {
            var automatic = Boolean.TRUE.equals(jdbc.queryForObject("SELECT kind = 'AUTOMATIQUE' FROM backup_run WHERE id = ?",
                    Boolean.class, runId));
            if (!automatic) {
                return;
            }
            var message = new NativePush.Message("Sauvegarde en échec", "La sauvegarde de cette nuit a échoué. Ti’Cagou est prévenu.",
                    "/parametres?onglet=sauvegardes", NotificationService.Category.SAUVEGARDE.name(), Duration.ofHours(12), "sauvegarde");
            jdbc.queryForList("SELECT id FROM structure", UUID.class)
                    .forEach(structureId -> notifications.notifyDirection(structureId, NotificationService.Category.SAUVEGARDE, message));
        } catch (RuntimeException e) {
            LOG.warn("Alerte de sauvegarde non envoyée : {}", e.getMessage());
        }
    }

    /** Nombre de lignes de chaque table, pour contrôler la restauration. */
    private Map<String, Object> manifest() {
        var tables = new LinkedHashMap<String, Long>();
        for (var table : tables()) {
            tables.put(table, jdbc.queryForObject("SELECT count(*) FROM \"" + table + "\"", Long.class));
        }
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("createdAt", clock.instant().toString());
        manifest.put("schema", jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1", String.class));
        manifest.put("tables", tables);
        return manifest;
    }

    private List<String> tables() {
        return jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                ORDER BY table_name""", String.class);
    }

    private void prune(Path folder, Settings s) throws IOException {
        var archives = new ArrayList<BackupRetention.Archive>();
        try (var files = Files.list(folder)) {
            for (var file : files.toList()) {
                var m = ARCHIVE.matcher(file.getFileName().toString());
                if (m.matches()) {
                    archives.add(new BackupRetention.Archive(file.getFileName().toString(), LocalDate.parse(m.group(1))));
                }
            }
        }
        for (var old : BackupRetention.toDelete(archives, LocalDate.now(clock), s.keepDays(), s.keepWeeks(),
                s.keepMonths())) {
            Files.deleteIfExists(folder.resolve(old.name()));
        }
    }

    // ---------------------------------------------------------------- test de restauration

    /** Test de restauration de la dernière archive, en arrière-plan. */
    public RunDto startRestoreTest() {
        var user = CurrentUser.get();
        if (latestArchive().isEmpty()) {
            throw ApiException.rule("backup_none", "Aucune sauvegarde à tester pour le moment.");
        }
        var actor = new Actor(user.structureId(), user.userId(), user.displayName());
        var run = begin(Kind.TEST, user.displayName(), actor);
        executor.submit(() -> restoreTest(run, actor));
        return find(run);
    }

    Optional<Path> latestArchive() {
        var folder = properties.backup().dir();
        if (!Files.isDirectory(folder)) {
            return Optional.empty();
        }
        try (var files = Files.list(folder)) {
            return files.filter(f -> ARCHIVE.matcher(f.getFileName().toString()).matches())
                    .max(Comparator.comparing(f -> f.getFileName().toString()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    void restoreTest(UUID runId, Actor actor) {
        Path work = null;
        var database = "ticagou_test_restauration_" + Long.toHexString(System.nanoTime());
        boolean created = false;
        try {
            var archive = latestArchive().orElseThrow(() -> new IOException("Aucune sauvegarde à tester."));
            work = Files.createTempDirectory("ticagou-restauration");
            var zip = work.resolve("archive.zip");
            try (var in = Files.newInputStream(archive); var out = Files.newOutputStream(zip)) {
                BackupCrypto.decrypt(in, out, recoveryKey(false));
            }
            Map<String, Object> manifest;
            int documents;
            var dump = work.resolve("database.dump");
            try (var file = new ZipFile(zip.toFile())) {
                manifest = json.readValue(file.getInputStream(file.getEntry("manifest.json")), Map.class);
                Files.copy(file.getInputStream(file.getEntry("database.dump")), dump);
                documents = (int) file.stream().filter(e -> e.getName().startsWith("documents/")).count();
            }
            jdbc.execute("CREATE DATABASE " + database);
            created = true;
            pg.restore(dump, database);
            var expected = (Map<String, Number>) manifest.get("tables");
            var c = pg.connection();
            var missing = new ArrayList<String>();
            long rows = 0;
            try (var conn = DriverManager.getConnection("jdbc:postgresql://%s:%d/%s".formatted(c.host(), c.port(), database),
                    c.user(), c.password()); var statement = conn.createStatement()) {
                for (var table : expected.keySet()) {
                    try (var rs = statement.executeQuery("SELECT count(*) FROM \"" + table + "\"")) {
                        rs.next();
                        rows += rs.getLong(1);
                        if (rs.getLong(1) < expected.get(table).longValue()) {
                            missing.add(table);
                        }
                    } catch (SQLException e) {
                        missing.add(table);
                    }
                }
            }
            if (!missing.isEmpty()) {
                throw new IOException("Tables incomplètes après restauration : " + String.join(", ", missing));
            }
            // Base temporaire supprimée avant d'annoncer le résultat : un test « terminé » ne laisse rien derrière lui.
            created = !dropTemporary(database);
            finish(runId, Status.REUSSIE, Files.size(archive), archive.getFileName().toString(), 0,
                    "%d tables, %d lignes et %d document%s relus.".formatted(expected.size(), rows, documents,
                            documents > 1 ? "s" : ""));
            jdbc.update("UPDATE backup_settings SET last_test_on = ?, last_test_ok = TRUE WHERE id = 1", LocalDate.now(clock));
            audit.record(actor, AuditAction.BACKUP_RESTORE_TESTED, "backup_run", runId,
                    Map.of("file", archive.getFileName().toString(), "result", "REUSSIE"));
        } catch (Exception e) {
            LOG.warn("Test de restauration en échec", e);
            if (created) {
                created = !dropTemporary(database);
            }
            finish(runId, Status.ECHEC, null, null, 0, explain(e));
            jdbc.update("UPDATE backup_settings SET last_test_on = ?, last_test_ok = FALSE WHERE id = 1", LocalDate.now(clock));
            audit.record(actor, AuditAction.BACKUP_RESTORE_TESTED, "backup_run", runId, Map.of("result", "ECHEC"));
        } finally {
            if (created) {
                dropTemporary(database);
            }
            delete(work);
            running.set(false);
        }
    }

    /** Supprime la base du test de restauration ; vrai si c'est fait. */
    private boolean dropTemporary(String database) {
        try {
            jdbc.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            return true;
        } catch (Exception e) {
            LOG.warn("Base de test {} non supprimée", database, e);
            return false;
        }
    }

    // ---------------------------------------------------------------- outils

    private void finish(UUID runId, Status status, Long size, String fileName, int copies, String detail) {
        jdbc.update("""
                UPDATE backup_run SET status = ?, finished_at = ?, size_bytes = ?, file_name = ?, copies = ?, detail = ?
                WHERE id = ?""", status.name(), Timestamp.from(clock.instant()), size, fileName, copies,
                detail == null ? null : detail.length() > 500 ? detail.substring(0, 500) : detail, runId);
    }

    RunDto find(UUID id) {
        return overview().history().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    private static String explain(Exception e) {
        var message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    static void delete(Path dir) {
        if (dir == null) {
            return;
        }
        try (var files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // Dossier temporaire : le système le videra.
        }
    }
}
