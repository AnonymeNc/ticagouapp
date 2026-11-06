package nc.ticagou.dataimport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.dhatim.fastexcel.Workbook;

/** Classeurs de test : une feuille par entrée, première ligne = en-têtes, {@code null} = cellule vide. */
final class ImportWorkbooks {

    private ImportWorkbooks() {
    }

    static byte[] workbook(Map<String, List<List<Object>>> sheets) throws IOException {
        var out = new ByteArrayOutputStream();
        var workbook = new Workbook(out, "Ti'Cagou", "1.0");
        for (var sheet : sheets.entrySet()) {
            var worksheet = workbook.newWorksheet(sheet.getKey());
            var rows = sheet.getValue();
            for (int r = 0; r < rows.size(); r++) {
                var cells = rows.get(r);
                for (int c = 0; c < cells.size(); c++) {
                    switch (cells.get(c)) {
                        case null -> { }
                        case LocalDate date -> {
                            worksheet.value(r, c, date);
                            worksheet.style(r, c).format("dd/mm/yyyy").set();
                        }
                        case Number number -> worksheet.value(r, c, number);
                        case Boolean flag -> worksheet.value(r, c, flag);
                        default -> worksheet.value(r, c, cells.get(c).toString());
                    }
                }
            }
        }
        workbook.finish();
        return out.toByteArray();
    }
}
