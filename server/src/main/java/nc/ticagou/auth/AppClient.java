package nc.ticagou.auth;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Application mobile qui s'annonce par l'en-tête {@value #HEADER} (ADR 0024, 0027) :
 * « direction/1.4.0 (ios) », « pointage/1.4.0 (android) ». Ce n'est pas une preuve d'identité : il sert à la durée de
 * la session, au type de poste (ADR 0028), à la version minimale et au journal.
 *
 * @param app      direction, pointage ou parents
 * @param version  version de l'application (majeure.mineure.correctif)
 * @param platform ios ou android
 */
public record AppClient(String app, String version, String platform) {

    public static final String HEADER = "X-Ticagou-App";

    private static final Pattern FORMAT = Pattern.compile("^(direction|pointage|parents)/(\\d{1,4}\\.\\d{1,4}\\.\\d{1,6}) \\((ios|android)\\)$");

    /** Vide pour un navigateur, un en-tête absent ou mal formé. */
    public static Optional<AppClient> parse(String header) {
        if (header == null) {
            return Optional.empty();
        }
        var matcher = FORMAT.matcher(header.strip());
        return matcher.matches()
                ? Optional.of(new AppClient(matcher.group(1), matcher.group(2), matcher.group(3)))
                : Optional.empty();
    }

    /** Application Direction installée sur un téléphone ou une tablette : poste mobile, session longue. */
    public boolean isDirection() {
        return "direction".equals(app);
    }

    /** Version strictement antérieure à {@code minimum} (comparaison numérique, « 1.10.0 » &gt; « 1.9.0 »). */
    public boolean olderThan(String minimum) {
        return compare(version, minimum) < 0;
    }

    static int compare(String a, String b) {
        var left = a.split("\\.");
        var right = b.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int x = i < left.length ? Integer.parseInt(left[i]) : 0;
            int y = i < right.length ? Integer.parseInt(right[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    /** Libellé lisible pour la liste des postes : « Ti’Cagou Direction 1.4.0 · iOS ». */
    public String label() {
        var name = switch (app) {
            case "direction" -> "Ti’Cagou Direction";
            case "pointage" -> "Ti’Cagou Pointage";
            default -> "Ti’Cagou Parents";
        };
        return "%s %s · %s".formatted(name, version, "ios".equals(platform) ? "iOS" : "Android");
    }
}
