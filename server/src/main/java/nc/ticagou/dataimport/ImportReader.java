package nc.ticagou.dataimport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.ExcelReaderException;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;

import nc.ticagou.common.ApiException;

/** Lit un classeur .xlsx en mémoire : le fichier n'est jamais écrit sur disque (ADR 0016). */
final class ImportReader {

    /** Une crèche compte rarement plus de 100 enfants : au-delà, ce n'est pas un fichier de reprise. */
    static final int MAX_ROWS = 2000;
    static final long MAX_BYTES = 10L * 1024 * 1024;

    private static final LocalDate ORIGIN_1900 = LocalDate.of(1899, 12, 30);
    private static final LocalDate ORIGIN_1904 = LocalDate.of(1904, 1, 1);

    private ImportReader() {
    }

    static ImportFile read(byte[] content) {
        if (content.length == 0 || content.length > MAX_BYTES) {
            throw ApiException.rule("import_size", "Le fichier doit peser au plus 10 Mo.");
        }
        var header = Arrays.copyOf(content, 8);
        if (ReadableWorkbook.isOLE2Header(header)) {
            throw ApiException.rule("import_format",
                    "Ce fichier est au format .xls ou protégé par un mot de passe : enregistrez-le en .xlsx, sans mot de passe.");
        }
        if (!ReadableWorkbook.isOOXMLZipHeader(header)) {
            throw ApiException.rule("import_format", "Ce fichier n’est pas un classeur Excel (.xlsx).");
        }
        try (var workbook = new ReadableWorkbook(new ByteArrayInputStream(content))) {
            var sheets = new ArrayList<ImportFile.Sheet>();
            for (var sheet : workbook.getSheets().toList()) {
                sheets.add(read(sheet));
            }
            return new ImportFile(sheets, workbook.isDate1904() ? ORIGIN_1904 : ORIGIN_1900);
        } catch (IOException | ExcelReaderException e) {
            throw ApiException.rule("import_unreadable", "Ce classeur ne peut pas être lu : ouvrez-le et enregistrez-le de nouveau.");
        }
    }

    private static ImportFile.Sheet read(Sheet sheet) throws IOException {
        // Une ligne de plus que la limite, en-tête compris, suffit à savoir que le fichier la dépasse.
        List<Row> rows;
        try (var stream = sheet.openStream()) {
            rows = stream.limit(MAX_ROWS + 2L).toList();
        }
        if (rows.size() > MAX_ROWS + 1) {
            throw ApiException.rule("import_too_long",
                    "La feuille « %s » dépasse %d lignes.".formatted(sheet.getName(), MAX_ROWS));
        }
        if (rows.isEmpty()) {
            return new ImportFile.Sheet(sheet.getName(), List.of(), List.of());
        }
        var headerRow = rows.getFirst();
        var columns = new ArrayList<String>();
        for (int i = 0; i < headerRow.getCellCount(); i++) {
            columns.add(text(sheet, headerRow, i));
        }
        var lines = new ArrayList<ImportFile.Row>();
        for (var row : rows.subList(1, rows.size())) {
            var values = new HashMap<String, String>();
            for (int i = 0; i < columns.size(); i++) {
                var value = text(sheet, row, i);
                if (!columns.get(i).isEmpty() && !value.isEmpty()) {
                    values.putIfAbsent(ImportModel.key(columns.get(i)), value);
                }
            }
            if (!values.isEmpty()) {
                lines.add(new ImportFile.Row(row.getRowNum(), values));
            }
        }
        return new ImportFile.Sheet(sheet.getName(), columns.stream().filter(c -> !c.isEmpty()).toList(), lines);
    }

    private static String text(Sheet sheet, Row row, int index) {
        var cell = row.getOptionalCell(index).orElse(null);
        return cell == null ? "" : text(sheet, cell);
    }

    private static String text(Sheet sheet, Cell cell) {
        return switch (cell.getType()) {
            case EMPTY -> "";
            case BOOLEAN -> Boolean.TRUE.equals(cell.getValue()) ? "oui" : "non";
            case NUMBER -> number(cell.asNumber());
            case STRING -> clean(cell.asString());
            case FORMULA -> clean(cell.getRawValue());
            case ERROR -> throw ApiException.rule("import_cell_error",
                    "La cellule %s de la feuille « %s » contient une erreur de formule.".formatted(cell.getAddress(), sheet.getName()));
        };
    }

    /** « 251234 » et non « 2.51234E+5 » : un téléphone saisi comme un nombre doit rester lisible. */
    private static String number(BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace(' ', ' ').strip();
    }
}
