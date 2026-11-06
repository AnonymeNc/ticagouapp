package nc.ticagou.billing;

/**
 * Arrondis des montants en francs CFP (sans décimale). Seule classe de la facturation qui arrondit : chaque ligne
 * est arrondie au franc (au plus proche, la moitié s'éloignant de zéro), puis le total selon la règle de la
 * structure. Calcul en entiers, jamais en virgule flottante.
 */
public final class Xpf {

    private Xpf() {
    }

    public enum Mode { PROCHE, INFERIEUR, SUPERIEUR }

    /** a / b arrondi selon le mode ; symétrique pour les montants négatifs (avoirs, déductions). */
    public static long divide(long numerator, long denominator, Mode mode) {
        if (denominator <= 0) {
            throw new IllegalArgumentException("Diviseur nul ou négatif");
        }
        if (numerator < 0) {
            var mirrored = switch (mode) {
                case PROCHE -> Mode.PROCHE;
                case INFERIEUR -> Mode.SUPERIEUR;
                case SUPERIEUR -> Mode.INFERIEUR;
            };
            return -divide(-numerator, denominator, mirrored);
        }
        return switch (mode) {
            case PROCHE -> (numerator * 2 + denominator) / (denominator * 2);
            case INFERIEUR -> numerator / denominator;
            case SUPERIEUR -> (numerator + denominator - 1) / denominator;
        };
    }

    /** Durée en minutes au tarif horaire, au franc le plus proche : 75 min à 560 F → 700 F. */
    public static long ofMinutes(long minutes, long hourlyRate) {
        return divide(minutes * hourlyRate, 60, Mode.PROCHE);
    }

    /** Pourcentage exprimé en dixièmes : 200 = 20,0 %. */
    public static long percent(long base, int tenthsOfPercent) {
        return divide(base * tenthsOfPercent, 1000, Mode.PROCHE);
    }

    /** Part {@code part / whole} d'un montant (prorata de jours). */
    public static long prorata(long amount, long part, long whole) {
        if (whole == 0 || part >= whole) {
            return amount;
        }
        return divide(amount * Math.max(part, 0), whole, Mode.PROCHE);
    }

    /** TGC d'une ligne (taux en dixièmes de pour cent) ; négative pour une déduction. */
    public static long tgc(long amount, int tenthsOfPercent) {
        return percent(amount, tenthsOfPercent);
    }

    /** Arrondi du total de la facture au pas (1, 10 ou 100 F) et dans le sens de la règle. */
    public static long roundTotal(long total, int step, Mode mode) {
        if (step <= 1) {
            return total;
        }
        return divide(total, step, mode) * step;
    }

    /** Applique un plafond (nul = sans plafond) ; jamais négatif. */
    public static long cap(long value, Long cap) {
        long capped = cap == null ? value : Math.min(value, cap);
        return Math.max(capped, 0);
    }

    /** Durée arrondie au pas le plus proche (quart d'heure : 17 min → 15 min, 23 min → 30 min). */
    public static int roundMinutes(int minutes, int step) {
        if (step <= 1) {
            return minutes;
        }
        return (int) divide(minutes, step, Mode.PROCHE) * step;
    }
}
