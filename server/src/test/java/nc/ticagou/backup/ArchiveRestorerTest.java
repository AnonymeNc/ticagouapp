package nc.ticagou.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveRestorerTest {

    private static final String KEY = "ABCD-EFGH-JKLM-NPQR-STUV-WXYZ";

    @TempDir
    Path dir;

    private final ArchiveRestorer restorer = new ArchiveRestorer(new PrintStream(new ByteArrayOutputStream()));

    private Path archive(Map<String, String> entries) throws IOException {
        var zip = new ByteArrayOutputStream();
        try (var out = new ZipOutputStream(zip)) {
            for (var e : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        var file = dir.resolve("ticagou-2026-11-03-0200.tcb");
        Files.write(file, BackupCrypto.encrypt(zip.toByteArray(), KEY));
        return file;
    }

    private Path work() throws IOException {
        return Files.createDirectories(dir.resolve("work"));
    }

    @Test
    void ouvreLArchiveAvecLaCleDeSecoursSaisieLibrement() throws Exception {
        var file = archive(Map.of("manifest.json", "{\"createdAt\":\"2026-11-03T02:00:00Z\",\"schema\":\"12\",\"tables\":{\"child\":3}}",
                "database.dump", "PGDMP", "documents/s1/2026/11/a.bin", "x", "documents/s1/2026/11/b.bin", "y"));
        // Minuscules, espaces : la saisie est tolérante, comme sur la fiche.
        var opened = restorer.open(file, "abcd efgh jklm npqr stuv wxyz", work());
        assertThat(opened.manifest()).containsEntry("schema", "12");
        assertThat(opened.documents()).isEqualTo(2);
        assertThat(opened.beforeMigration()).isFalse();
    }

    @Test
    void cleFausseArchiveIncompleteOuAbsente() throws Exception {
        var file = archive(Map.of("manifest.json", "{}", "database.dump", "PGDMP"));
        assertThatThrownBy(() -> restorer.open(file, "AAAA-AAAA-AAAA-AAAA-AAAA-AAAA", work()))
                .hasMessageContaining("Clé de récupération incorrecte");

        var noDump = archive(Map.of("manifest.json", "{}"));
        assertThatThrownBy(() -> restorer.open(noDump, KEY, work())).hasMessageContaining("incomplète");

        assertThatThrownBy(() -> restorer.open(dir.resolve("absente.tcb"), KEY, work())).hasMessageContaining("introuvable");
    }

    @Test
    void documentsRemplacesEtAnciensGardesACote() throws Exception {
        var file = archive(Map.of("manifest.json", "{}", "database.dump", "PGDMP", "documents/s1/2026/11/a.bin", "nouveau"));
        var storage = Files.createDirectories(dir.resolve("documents"));
        Files.writeString(storage.resolve("ancien.bin"), "ancien");

        var opened = restorer.open(file, KEY, work());
        var previous = restorer.restoreDocuments(opened.zip(), storage);

        assertThat(Files.readString(storage.resolve("s1/2026/11/a.bin"))).isEqualTo("nouveau");
        assertThat(storage.resolve("ancien.bin")).doesNotExist();
        assertThat(previous).isNotNull();
        assertThat(Files.readString(previous.resolve("ancien.bin"))).isEqualTo("ancien");
    }

    @Test
    void cheminQuiSortDuDossierRefuse() throws Exception {
        var file = archive(Map.of("manifest.json", "{}", "database.dump", "PGDMP", "documents/../../piege.txt", "x"));
        var opened = restorer.open(file, KEY, work());
        assertThatThrownBy(() -> restorer.restoreDocuments(opened.zip(), dir.resolve("documents")))
                .hasMessageContaining("Chemin refusé");
        assertThat(dir.resolve("piege.txt")).doesNotExist();
    }

    @Test
    void adresseDeLaBase() {
        assertThat(ArchiveRestorer.target("jdbc:postgresql://localhost:5433/ticagou"))
                .isEqualTo(new ArchiveRestorer.Target("localhost", 5433, "ticagou"));
        assertThat(ArchiveRestorer.target("jdbc:postgresql://srv/ticagou?ssl=false").port()).isEqualTo(5432);
        assertThatThrownBy(() -> ArchiveRestorer.target("jdbc:postgresql://localhost/ti\"cagou"))
                .hasMessageContaining("Nom de base inattendu");
    }
}
