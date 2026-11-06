package nc.ticagou.backup;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import nc.ticagou.config.TiCagouProperties;

/**
 * Sauvegarde automatique avant chaque mise à jour du schéma (Flyway) : si la base existe déjà et que des migrations
 * attendent, un pg_dump chiffré avec la clé de secours est écrit dans le dossier des sauvegardes de l'instance. Sans
 * sauvegarde, le serveur ne démarre pas, sauf réglage
 * {@code ticagou.backup.skip-pre-migration} réservé au dépannage. Les documents ne sont pas concernés par les
 * migrations : ils restent sur le disque.
 */
public class PreMigrationBackup implements FlywayMigrationStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(PreMigrationBackup.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");

    private final JdbcTemplate jdbc;
    private final PgTools pg;
    private final TiCagouProperties properties;
    private final Clock clock;

    /** Archive produite, inscrite dans l'historique une fois le schéma à jour. */
    public record Result(Path file, long size, int copies, String fromVersion, String toVersion) {
    }

    public PreMigrationBackup(DataSource dataSource, PgTools pg, TiCagouProperties properties, Clock clock) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.pg = pg;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void migrate(Flyway flyway) {
        var result = backupIfNeeded(flyway);
        flyway.migrate();
        if (result != null) {
            record(result);
        }
    }

    /** Sauvegarde si une base existante va être migrée ; null sinon. */
    public Result backupIfNeeded(Flyway flyway) {
        var info = flyway.info();
        var current = info.current();
        var pending = info.pending();
        if (current == null || pending.length == 0) {
            return null;
        }
        var from = current.getVersion().getVersion();
        var to = pending[pending.length - 1].getVersion().getVersion();
        if (properties.backup().skipPreMigration()) {
            LOG.warn("Migration V{} → V{} sans sauvegarde préalable (ticagou.backup.skip-pre-migration)", from, to);
            return null;
        }
        if (!pg.available()) {
            throw new IllegalStateException("Mise à jour du schéma V%s → V%s refusée : pg_dump est introuvable pour la sauvegarde préalable. Réglez ticagou.backup.pg-bin."
                    .formatted(from, to));
        }
        Path work = null;
        try {
            work = Files.createTempDirectory("ticagou-avant-migration");
            var dump = work.resolve("database.dump");
            pg.dump(dump);
            var zip = work.resolve("archive.zip");
            try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
                var manifest = new LinkedHashMap<String, Object>();
                manifest.put("createdAt", clock.instant().toString());
                manifest.put("kind", "AVANT_MIGRATION");
                manifest.put("schema", from);
                manifest.put("target", to);
                out.putNextEntry(new ZipEntry("manifest.json"));
                out.write(new ObjectMapper().writeValueAsBytes(manifest));
                out.closeEntry();
                out.putNextEntry(new ZipEntry("database.dump"));
                Files.copy(dump, out);
                out.closeEntry();
            }
            var name = "ticagou-avant-migration-V%s-%s.tcb".formatted(to, clock.instant().atZone(clock.getZone()).format(STAMP));
            var folder = localFolder();
            Files.createDirectories(folder);
            var file = folder.resolve(name);
            try (InputStream in = Files.newInputStream(zip); var out = Files.newOutputStream(file)) {
                BackupCrypto.encrypt(in, out, recoveryKey());
            }
            LOG.info("Sauvegarde avant migration V{} → V{} : {}", from, to, file);
            return new Result(file, Files.size(file), 1, from, to);
        } catch (IOException e) {
            throw new UncheckedIOException("Sauvegarde avant migration impossible : la mise à jour du schéma est annulée", e);
        } finally {
            BackupService.delete(work);
        }
    }

    /** Dossier local, à côté des documents (C:\ProgramData\TiCagou\backups une fois installé). */
    Path localFolder() {
        return properties.backup().dir().toAbsolutePath().normalize();
    }

    /** Clé de secours ; créée si la base n'en a pas encore (la direction sera invitée à l'imprimer). */
    private String recoveryKey() {
        if (!properties.backup().recoveryKey().isBlank()) {
            return properties.backup().recoveryKey();
        }
        var existing = jdbc.query("SELECT value FROM server_secret WHERE name = ?",
                (rs, n) -> new String(rs.getBytes(1), StandardCharsets.UTF_8), BackupService.KEY_SECRET);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        jdbc.update("INSERT INTO server_secret (name, value) VALUES (?, ?)", BackupService.KEY_SECRET,
                BackupCrypto.newRecoveryKey().getBytes(StandardCharsets.UTF_8));
        return recoveryKey();
    }

    private void record(Result result) {
        jdbc.update("""
                INSERT INTO backup_run (id, kind, started_at, finished_at, status, size_bytes, file_name, copies, detail)
                VALUES (?, 'AVANT_MIGRATION', ?, ?, 'REUSSIE', ?, ?, ?, ?)""",
                UUID.randomUUID(), Timestamp.from(clock.instant()), Timestamp.from(clock.instant()), result.size(),
                result.file().getFileName().toString(), result.copies(),
                "Avant la mise à jour du schéma V%s → V%s, dans %s".formatted(result.fromVersion(), result.toVersion(),
                        result.file().getParent()));
    }
}
