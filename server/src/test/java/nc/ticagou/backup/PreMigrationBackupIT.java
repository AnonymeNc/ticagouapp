package nc.ticagou.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.zip.ZipInputStream;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.zaxxer.hikari.HikariDataSource;

import nc.ticagou.IntegrationTest;
import nc.ticagou.config.TiCagouProperties;

/** Sauvegarde avant migration : une base en V9 passe en V10 après un pg_dump chiffré. */
class PreMigrationBackupIT extends IntegrationTest {

    static final String DATABASE = "ticagou_test_migration";

    @Autowired
    DataSource dataSource;

    @Autowired
    TiCagouProperties properties;

    @Autowired
    Clock clock;

    HikariDataSource legacy;
    Path storage;

    @BeforeEach
    void ancienneBase() throws IOException {
        var app = (HikariDataSource) dataSource;
        jdbc.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
        jdbc.execute("CREATE DATABASE " + DATABASE);
        legacy = new HikariDataSource();
        legacy.setJdbcUrl(app.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + DATABASE + "$1"));
        legacy.setUsername(app.getUsername());
        legacy.setPassword(app.getPassword());
        Flyway.configure().dataSource(legacy).target("9").load().migrate();
        new JdbcTemplate(legacy).update("INSERT INTO server_secret (name, value) VALUES ('backup_recovery_key', ?)",
                (Object) BackupCryptoTest.KEY.getBytes());
        storage = Files.createTempDirectory("ticagou-migration").resolve("documents");
    }

    @AfterEach
    void supprimer() {
        legacy.close();
        jdbc.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
    }

    PreMigrationBackup backup(boolean skip) {
        var props = new TiCagouProperties(properties.zone(), properties.allowedOrigins(), properties.appOrigins(), properties.app(), properties.security(),
                new TiCagouProperties.Storage(storage, ""), properties.web(),
                new TiCagouProperties.Backup(storage.getParent().resolve("backups"), properties.backup().dailyTime(), "",
                        properties.backup().pgBin(), skip), properties.setup(), properties.monitoring(),
                properties.license(), properties.demo());
        return new PreMigrationBackup(legacy, new PgTools(props, legacy), props, clock);
    }

    @Test
    void sauvegardeChiffreeAvantLaMigrationPuisHistorique() throws Exception {
        var pg = new PgTools(properties, legacy);
        assumeTrue(pg.available(), "pg_dump absent de ce poste");
        var flyway = Flyway.configure().dataSource(legacy).load();

        backup(false).migrate(flyway);

        var folder = storage.getParent().resolve("backups");
        try (var files = Files.list(folder)) {
            var archive = files.findFirst().orElseThrow();
            assertThat(archive.getFileName().toString()).matches("ticagou-avant-migration-V\\d+-\\d{4}-\\d{2}-\\d{2}-\\d{4}\\.tcb");
            var zip = new ByteArrayOutputStream();
            try (var in = Files.newInputStream(archive)) {
                BackupCrypto.decrypt(in, zip, BackupCryptoTest.KEY);
            }
            var entries = new java.util.ArrayList<String>();
            try (var in = new ZipInputStream(new java.io.ByteArrayInputStream(zip.toByteArray()))) {
                for (var e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                    entries.add(e.getName());
                }
            }
            assertThat(entries).containsExactly("manifest.json", "database.dump");
        }
        var legacyJdbc = new JdbcTemplate(legacy);
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(legacyJdbc.queryForObject("SELECT detail FROM backup_run WHERE kind = 'AVANT_MIGRATION'", String.class))
                .startsWith("Avant la mise à jour du schéma V9 → V");

        // Plus rien à migrer : pas de nouvelle sauvegarde.
        assertThat(backup(false).backupIfNeeded(flyway)).isNull();
    }

    @Test
    void sansPgDumpLaMigrationEstRefuseeSaufDepannage() {
        var props = new TiCagouProperties(properties.zone(), properties.allowedOrigins(), properties.appOrigins(), properties.app(), properties.security(),
                new TiCagouProperties.Storage(storage, ""), properties.web(),
                new TiCagouProperties.Backup(storage.getParent().resolve("backups"), properties.backup().dailyTime(), "",
                        storage.resolve("introuvable").toString(), false), properties.setup(), properties.monitoring(),
                properties.license(), properties.demo());
        var missing = new PgTools(props, legacy) {
            @Override
            public boolean available() {
                return false;
            }
        };
        var flyway = Flyway.configure().dataSource(legacy).load();
        assertThatThrownBy(() -> new PreMigrationBackup(legacy, missing, props, clock).migrate(flyway))
                .hasMessageContaining("pg_dump est introuvable");
        assertThat(flyway.info().pending()).as("schéma inchangé").isNotEmpty();

        backup(true).migrate(flyway);
        assertThat(flyway.info().pending()).isEmpty();
    }
}
