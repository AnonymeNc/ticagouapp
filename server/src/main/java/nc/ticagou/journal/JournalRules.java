package nc.ticagou.journal;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import nc.ticagou.common.ApiException;
import nc.ticagou.journal.DailyEntry.Kind;

/**
 * Détails admis par type de transmission ; tout autre détail est refusé, pour que rien d'imprévu (une consigne
 * médicale glissée dans un champ structuré) ne soit enregistré puis partagé. Un médicament donné a son propre type,
 * limité à un enfant (ADR 0014).
 */
public final class JournalRules {

    public static final int MAX_CHILDREN = 30;
    public static final int MAX_TEXT = 500;
    static final int MAX_BOTTLE_ML = 500;
    static final int MAX_MEDICINE = 100;
    static final int MAX_DOSE = 50;

    static final Set<String> MEALS = Set.of("COLLATION", "DEJEUNER", "GOUTER", "BIBERON");
    static final Set<String> QUANTITIES = Set.of("TOUT", "BIEN", "PEU", "RIEN");
    static final Set<String> CHANGES = Set.of("MOUILLEE", "SELLES", "PROPRE", "POT");

    private JournalRules() {
    }

    /** Détails validés et normalisés ; lève {@code invalid_entry} sinon. */
    public static Map<String, String> details(Kind kind, Map<String, String> input) {
        var in = input == null ? Map.<String, String>of() : input;
        var out = new LinkedHashMap<String, String>();
        switch (kind) {
            case REPAS -> {
                allowOnly(in, Set.of("meal", "quantity", "volumeMl"));
                var meal = oneOf(in.get("meal"), MEALS, "Choisissez le repas.");
                var bottle = "BIBERON".equals(meal);
                out.put("meal", meal);
                out.put("quantity", oneOf(in.get("quantity"), QUANTITIES,
                        bottle ? "Indiquez ce que l’enfant a bu." : "Indiquez ce que l’enfant a mangé."));
                if (in.get("volumeMl") != null && !in.get("volumeMl").isBlank()) {
                    if (!bottle) {
                        throw invalid("La quantité en millilitres concerne un biberon.");
                    }
                    out.put("volumeMl", volume(in.get("volumeMl")));
                }
            }
            case SIESTE -> {
                allowOnly(in, Set.of("from", "to"));
                var from = time(in.get("from"), "Indiquez l’heure d’endormissement.");
                out.put("from", from.toString());
                if (in.get("to") != null && !in.get("to").isBlank()) {
                    var to = time(in.get("to"), "Heure de réveil invalide.");
                    if (to.isBefore(from)) {
                        throw invalid("Le réveil ne peut pas précéder l’endormissement.");
                    }
                    out.put("to", to.toString());
                }
            }
            case CHANGE -> {
                allowOnly(in, Set.of("change"));
                out.put("change", oneOf(in.get("change"), CHANGES, "Indiquez le type de change."));
            }
            case MEDICAMENT -> {
                allowOnly(in, Set.of("medicine", "dose"));
                var medicine = in.get("medicine") == null ? "" : in.get("medicine").strip();
                if (medicine.isEmpty() || medicine.length() > MAX_MEDICINE) {
                    throw invalid("Indiquez le médicament donné (%d caractères au plus).".formatted(MAX_MEDICINE));
                }
                out.put("medicine", medicine);
                var dose = in.get("dose") == null ? "" : in.get("dose").strip();
                if (dose.length() > MAX_DOSE) {
                    throw invalid("La dose est limitée à %d caractères.".formatted(MAX_DOSE));
                }
                if (!dose.isEmpty()) {
                    out.put("dose", dose);
                }
            }
            default -> allowOnly(in, Set.of());
        }
        return out;
    }

    /** Une transmission à plusieurs enfants paraît chez toutes leurs familles : pas un médicament (ADR 0014). */
    public static void checkChildren(Kind kind, int children) {
        if (kind == Kind.MEDICAMENT && children != 1) {
            throw invalid("Un médicament se note enfant par enfant.");
        }
    }

    /** Heure réelle d'une saisie faite après coup, le même jour ; sans heure, celle de la saisie. */
    public static Instant occurredAt(String time, Instant at, ZoneId zone) {
        if (time == null || time.isBlank()) {
            return at;
        }
        var occurred = at.atZone(zone).toLocalDate().atTime(time(time, "Heure invalide.")).atZone(zone).toInstant();
        if (occurred.isAfter(at)) {
            throw invalid("L’heure ne peut pas être dans le futur.");
        }
        return occurred;
    }

    /** Texte libre nettoyé ; obligatoire pour une activité et un mot. */
    public static String text(Kind kind, String text) {
        var trimmed = text == null ? null : text.strip();
        if (trimmed != null && trimmed.length() > MAX_TEXT) {
            throw invalid("Le texte est limité à %d caractères.".formatted(MAX_TEXT));
        }
        if ((kind == Kind.ACTIVITE || kind == Kind.MOT) && (trimmed == null || trimmed.isEmpty())) {
            throw invalid(kind == Kind.ACTIVITE ? "Décrivez l’activité." : "Écrivez le mot pour les parents.");
        }
        return trimmed == null || trimmed.isEmpty() ? null : trimmed;
    }

    private static String oneOf(String value, Set<String> allowed, String message) {
        if (value == null || !allowed.contains(value)) {
            throw invalid(message);
        }
        return value;
    }

    private static String volume(String value) {
        try {
            var ml = Integer.parseInt(value.strip());
            if (ml >= 1 && ml <= MAX_BOTTLE_ML) {
                return String.valueOf(ml);
            }
        } catch (NumberFormatException e) {
            // Message commun ci-dessous.
        }
        throw invalid("Indiquez la quantité du biberon en millilitres, jusqu’à %d.".formatted(MAX_BOTTLE_ML));
    }

    private static LocalTime time(String value, String message) {
        try {
            return LocalTime.parse(value == null ? "" : value);
        } catch (DateTimeParseException e) {
            throw invalid(message);
        }
    }

    private static void allowOnly(Map<String, String> in, Set<String> keys) {
        for (var key : in.keySet()) {
            if (!keys.contains(key)) {
                throw invalid("Détail inconnu : " + key);
            }
        }
    }

    private static ApiException invalid(String message) {
        return ApiException.rule("invalid_entry", message);
    }
}
