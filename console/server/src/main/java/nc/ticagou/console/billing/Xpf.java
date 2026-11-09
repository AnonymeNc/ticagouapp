package nc.ticagou.console.billing;

/**
 * Arrondis des montants en francs CFP, sans décimale, en entiers : jamais de virgule flottante. Repris de
 * {@code server/billing/Xpf} (même règle : au franc le plus proche, la moitié s'éloignant de zéro).
 */
public final class Xpf {

    private Xpf() {
    }

    /** a / b au franc le plus proche ; symétrique pour les montants négatifs. */
    public static long divide(long numerator, long denominator) {
        if (denominator <= 0) {
            throw new IllegalArgumentException("Diviseur nul ou négatif");
        }
        if (numerator < 0) {
            return -divide(-numerator, denominator);
        }
        return (numerator * 2 + denominator) / (denominator * 2);
    }

    /** Pourcentage exprimé en dixièmes : 110 = 11,0 %. */
    public static long percent(long base, int tenthsOfPercent) {
        return divide(base * tenthsOfPercent, 1000);
    }

    /** Part {@code part / whole} d'un montant (prorata de jours). */
    public static long prorata(long amount, long part, long whole) {
        if (whole <= 0 || part >= whole) {
            return amount;
        }
        return divide(amount * Math.max(part, 0), whole);
    }

    /** « 179 820 F » (espace insécable, lisible par les polices des PDF). */
    public static String format(long amount) {
        var digits = Long.toString(Math.abs(amount));
        var text = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                text.append(' ');
            }
            text.append(digits.charAt(i));
        }
        return (amount < 0 ? "-" : "") + text + " F";
    }

    /** « 11 % », « 5,5 % ». */
    public static String rate(int tenths) {
        return (tenths % 10 == 0 ? Integer.toString(tenths / 10) : (tenths / 10) + "," + (tenths % 10)) + " %";
    }
}
