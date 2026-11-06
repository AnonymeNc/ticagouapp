package nc.ticagou.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import nc.ticagou.IntegrationTest;

/**
 * Reprise après sinistre : une vraie sauvegarde chiffrée est restaurée, avec la clé de secours, dans une autre base
 * (comme sur un PC neuf), et les données comme les documents y sont retrouvés.
 */
class RestoreIT extends IntegrationTest {

    private static final String RESTORED = "ticagou_restauration_it";

    @Autowired
    private PgTools pg;

    @AfterEach
    void dropRestored() {
        jdbc.execute("DROP DATABASE IF EXISTS " + RESTORED + " WITH (FORCE)");
    }

    private Path backup(String director) throws Exception {
        var run = body(perform(post("/api/v1/backups/run"), director, null)).get("id").asText();
        for (int i = 0; i < 240; i++) {
            for (var r : body(getAs(director, "/api/v1/backups")).get("history")) {
                if (r.get("id").asText().equals(run) && !r.get("status").asText().equals("EN_COURS")) {
                    assertThat(r.get("status").asText()).as(r.toString()).isEqualTo("REUSSIE");
                    try (Stream<Path> files = Files.list(BACKUPS)) {
                        return files.findFirst().orElseThrow();
                    }
                }
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Sauvegarde trop longue");
    }

    @Test
    void sauvegardeRestaureeSurUneAutreBaseAvecLaCleDeSecours() throws Exception {
        assumeTrue(pg.available(), "pg_dump et pg_restore absents de ce poste");
        var director = install();
        createChild(director, "Kaïna", "Wamytan", "2024-03-02", sectionId(director, "Grands"));
        var archive = backup(director);
        var key = jdbc.queryForObject("SELECT convert_from(value, 'UTF8') FROM server_secret WHERE name = 'backup_recovery_key'",
                String.class);

        var c = pg.connection();
        var url = "jdbc:postgresql://%s:%d/%s".formatted(c.host(), c.port(), RESTORED);
        var storage = Files.createTempDirectory("ticagou-pc-neuf").resolve("documents");
        var log = new ByteArrayOutputStream();
        var restorer = new ArchiveRestorer(new PrintStream(log, true, StandardCharsets.UTF_8));

        // Clé fausse : rien n'est touché.
        assertThatThrownBy(() -> restorer.restore(new ArchiveRestorer.Options(archive, "AAAA-BBBB-CCCC-DDDD-EEEE-FFFF", url,
                c.user(), c.password(), null, storage, false))).hasMessageContaining("Clé de récupération incorrecte");

        var result = restorer.restore(new ArchiveRestorer.Options(archive, key, url, c.user(), c.password(), null, storage, false));
        assertThat(result.tables()).isGreaterThan(10);
        assertThat(log.toString(StandardCharsets.UTF_8)).contains("pg_restore");

        try (var conn = DriverManager.getConnection(url, c.user(), c.password()); var st = conn.createStatement();
             var rs = st.executeQuery("SELECT first_name FROM child")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("Kaïna");
        }
        try (var conn = DriverManager.getConnection(url, c.user(), c.password()); var st = conn.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM server_secret WHERE name = 'backup_recovery_key'")) {
            rs.next();
            assertThat(rs.getInt(1)).as("la clé de secours suit les données").isEqualTo(1);
        }

        // Une base qui contient déjà une crèche n'est pas écrasée sans --remplacer.
        assertThatThrownBy(() -> restorer.restore(new ArchiveRestorer.Options(archive, key, url, c.user(), c.password(), null,
                storage, false))).hasMessageContaining("contient déjà une crèche");
        var again = restorer.restore(new ArchiveRestorer.Options(archive, key, url, c.user(), c.password(), null, storage, true));
        assertThat(again.rows()).isEqualTo(result.rows());
    }
}
