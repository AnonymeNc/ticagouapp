package nc.ticagou.dataimport;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Contenu d'un fichier de reprise, indépendant de son format (ADR 0016). Toutes les cellules sont du texte ; une date Excel
 * reste un numéro de série, compté depuis {@code dateOrigin}.
 */
record ImportFile(List<Sheet> sheets, LocalDate dateOrigin) {

    Optional<Sheet> sheet(String name) {
        var key = ImportModel.key(name);
        return sheets.stream().filter(s -> ImportModel.key(s.name()).equals(key)).findFirst();
    }

    /** {@code columns} : en-têtes tels qu'écrits dans le fichier. */
    record Sheet(String name, List<String> columns, List<Row> rows) {
    }

    /** {@code number} : numéro de ligne dans le tableur ; {@code values} : texte par clé de colonne ({@link ImportModel#key}). */
    record Row(int number, Map<String, String> values) {
    }
}
