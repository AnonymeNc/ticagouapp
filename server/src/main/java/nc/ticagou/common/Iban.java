package nc.ticagou.common;

import java.math.BigInteger;
import java.util.Locale;

/** IBAN : normalisation (sans espaces, en majuscules), contrôle de la clé (modulo 97) et affichage par groupes de 4. */
public final class Iban {

    private Iban() {
    }

    public static String normalize(String value) {
        return value == null ? null : value.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
    }

    public static boolean isValid(String value) {
        var iban = normalize(value);
        if (iban == null || !iban.matches("[A-Z]{2}\\d{2}[A-Z0-9]{10,30}")) {
            return false;
        }
        var rearranged = iban.substring(4) + iban.substring(0, 4);
        var digits = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            digits.append(Character.isLetter(c) ? String.valueOf(c - 'A' + 10) : String.valueOf(c));
        }
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    /** « NC1420041010050500013M02606 » → « NC14 2004 1010 0505 0001 3M02 606 » */
    public static String format(String value) {
        var iban = normalize(value);
        return iban == null ? null : iban.replaceAll("(.{4})(?=.)", "$1 ");
    }
}
