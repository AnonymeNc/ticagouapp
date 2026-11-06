package nc.ticagou.common;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Formats d'affichage côté serveur (messages, historique, PDF) : JJ/MM/AAAA, « 78 901 F », « 39 h 30 ». */
public final class Formats {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRENCH);

    private Formats() {
    }

    public static String date(LocalDate date) {
        return date == null ? "" : date.format(DATE);
    }

    public static String time(LocalTime time) {
        return time == null ? "" : time.format(TIME);
    }

    /** Mois facturé : « octobre 2026 ». */
    public static String monthYear(YearMonth month) {
        return month == null ? "" : month.format(MONTH);
    }

    /** Montant en francs CFP, milliers séparés par une espace insécable : « 78 901 F ». */
    public static String xpf(long amount) {
        var digits = Long.toString(Math.abs(amount));
        var grouped = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                grouped.append(' ');
            }
            grouped.append(digits.charAt(i));
        }
        return (amount < 0 ? "− " : "") + grouped + " F";
    }

    /** Durée en minutes : « 39 h 30 », « 9 h 00 ». */
    public static String duration(long minutes) {
        return "%d h %02d".formatted(minutes / 60, minutes % 60);
    }
}
