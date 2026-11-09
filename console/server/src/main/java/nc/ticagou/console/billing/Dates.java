package nc.ticagou.console.billing;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** Dates des pièces : JJ/MM/AAAA. */
public final class Dates {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private Dates() {
    }

    public static String format(LocalDate day) {
        return day == null ? "" : DAY.format(day);
    }

    /** « 01/11/2026 – 31/10/2027 ». */
    public static String period(LocalDate from, LocalDate to) {
        return format(from) + " – " + format(to);
    }
}
