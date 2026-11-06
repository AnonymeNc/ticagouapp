package nc.ticagou.dataimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import nc.ticagou.common.ApiException;

/** Lecture des classeurs : valeurs telles qu'Excel les range, fichiers refusés avec un message utile. */
class ImportReaderTest {

    private static String code(Throwable e) {
        return ((ApiException) e).code();
    }

    @Test
    void lesCellulesDevienentDuTexteCleParColonne() throws IOException {
        var file = ImportReader.read(ImportWorkbooks.workbook(Map.of("Enfants", List.of(
                List.<Object>of("Réf. Enfant *", "Téléphone", "Naissance", "Présent"),
                Arrays.<Object>asList("  E1 ", 251234, LocalDate.of(2024, 3, 15), true),
                Arrays.<Object>asList(null, null, null, null),
                Arrays.<Object>asList("E2", null, null, false)))));

        var sheet = file.sheet("enfants").orElseThrow();

        assertThat(sheet.columns()).containsExactly("Réf. Enfant *", "Téléphone", "Naissance", "Présent");
        assertThat(sheet.rows()).hasSize(2);
        var first = sheet.rows().getFirst();
        assertThat(first.number()).isEqualTo(2);
        assertThat(first.values()).containsEntry("refenfant", "E1").containsEntry("telephone", "251234")
                .containsEntry("present", "oui");
        var serial = Integer.parseInt(first.values().get("naissance"));
        assertThat(file.dateOrigin().plusDays(serial)).isEqualTo(LocalDate.of(2024, 3, 15));
        assertThat(sheet.rows().get(1).number()).as("numéro de ligne du tableur, ligne vide comprise").isEqualTo(4);
        assertThat(sheet.rows().get(1).values()).containsEntry("present", "non").doesNotContainKey("telephone");
    }

    @Test
    void unXlsEstRefuseAvecLaMarcheASuivre() {
        byte[] ole2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0, 0};

        assertThatThrownBy(() -> ImportReader.read(ole2)).isInstanceOf(ApiException.class)
                .hasMessageContaining("enregistrez-le en .xlsx");
    }

    @Test
    void unAutreFichierEstRefuse() {
        var csv = "Nom;Prénom\nGope;Lila\n".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ImportReader.read(csv)).satisfies(e -> assertThat(code(e)).isEqualTo("import_format"));
        assertThatThrownBy(() -> ImportReader.read(new byte[0])).satisfies(e -> assertThat(code(e)).isEqualTo("import_size"));
    }

    @Test
    void uneArchiveQuiNEstPasUnClasseurEstRefusee() throws IOException {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("lisez-moi.txt"));
            zip.write("Bonjour".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        assertThatThrownBy(() -> ImportReader.read(out.toByteArray()))
                .satisfies(e -> assertThat(code(e)).isEqualTo("import_unreadable"));
    }

    @Test
    void uneFeuilleTropLongueEstRefusee() throws IOException {
        var rows = new ArrayList<List<Object>>();
        rows.add(List.of("Réf enfant"));
        for (int i = 0; i <= ImportReader.MAX_ROWS; i++) {
            rows.add(List.of("E" + i));
        }
        var content = ImportWorkbooks.workbook(Map.of("Enfants", rows));

        assertThatThrownBy(() -> ImportReader.read(content))
                .satisfies(e -> assertThat(code(e)).isEqualTo("import_too_long"));
    }
}
