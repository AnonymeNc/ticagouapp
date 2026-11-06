package nc.ticagou.backup;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.springframework.stereotype.Component;

import com.zaxxer.hikari.HikariDataSource;

import nc.ticagou.config.TiCagouProperties;

/**
 * pg_dump et pg_restore, installés dans l'image de l'instance. Les paramètres de connexion sont ceux du
 * pool de l'application ; le mot de passe passe par la variable PGPASSWORD, jamais par la ligne de commande.
 */
@Component
public class PgTools {

    private static final Pattern JDBC_URL = Pattern.compile("jdbc:postgresql://([^:/?]+)(?::(\\d+))?/([^?]+).*");

    public record Connection(String host, int port, String database, String user, String password) {
    }

    private final TiCagouProperties properties;
    private final DataSource dataSource;

    public PgTools(TiCagouProperties properties, DataSource dataSource) {
        this.properties = properties;
        this.dataSource = dataSource;
    }

    public boolean available() {
        return binary("pg_dump").isPresent() && binary("pg_restore").isPresent();
    }

    public Connection connection() {
        HikariDataSource hikari;
        try {
            hikari = dataSource.unwrap(HikariDataSource.class);
        } catch (SQLException e) {
            throw new IllegalStateException("Pool de connexions inattendu", e);
        }
        var matcher = JDBC_URL.matcher(hikari.getJdbcUrl());
        if (!matcher.matches()) {
            throw new IllegalStateException("Adresse de base non reconnue : " + hikari.getJdbcUrl());
        }
        return new Connection(matcher.group(1), matcher.group(2) == null ? 5432 : Integer.parseInt(matcher.group(2)),
                matcher.group(3), hikari.getUsername(), hikari.getPassword());
    }

    /** Copie complète de la base, format « custom » de pg_dump (compressé, restaurable par pg_restore). */
    public void dump(Path target) throws IOException {
        var c = connection();
        run(List.of(require("pg_dump").toString(), "--format=custom", "--no-owner", "--no-privileges",
                "--host=" + c.host(), "--port=" + c.port(), "--username=" + c.user(), "--file=" + target, c.database()), c);
    }

    /** Restauration dans une autre base (test de restauration : jamais la base en service). */
    public void restore(Path dump, String database) throws IOException {
        var c = connection();
        if (database.equals(c.database())) {
            throw new IllegalArgumentException("Refus de restaurer sur la base en service.");
        }
        run(List.of(require("pg_restore").toString(), "--no-owner", "--no-privileges", "--exit-on-error",
                "--host=" + c.host(), "--port=" + c.port(), "--username=" + c.user(), "--dbname=" + database,
                dump.toString()), c);
    }

    private void run(List<String> command, Connection c) throws IOException {
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("PGPASSWORD", c.password() == null ? "" : c.password());
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!process.waitFor(2, TimeUnit.HOURS)) {
                process.destroyForcibly();
                throw new IOException(command.getFirst() + " n’a pas terminé.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrompu.", e);
        }
        if (process.exitValue() != 0) {
            var message = output.strip();
            throw new IOException(message.length() > 300 ? message.substring(0, 300) : message);
        }
    }

    private Path require(String name) throws IOException {
        return binary(name).orElseThrow(() -> new IOException(
                "%s est introuvable : indiquez le dossier des outils PostgreSQL (ticagou.backup.pg-bin).".formatted(name)));
    }

    Optional<Path> binary(String name) {
        var exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? name + ".exe" : name;
        var candidates = new ArrayList<Path>();
        if (!properties.backup().pgBin().isBlank()) {
            candidates.add(Path.of(properties.backup().pgBin(), exe));
        }
        var path = System.getenv("PATH");
        if (path != null) {
            for (var dir : path.split(File.pathSeparator)) {
                if (!dir.isBlank()) {
                    candidates.add(Path.of(dir, exe));
                }
            }
        }
        return candidates.stream().filter(Files::isExecutable).findFirst();
    }
}
