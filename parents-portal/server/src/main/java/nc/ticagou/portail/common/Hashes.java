package nc.ticagou.portail.common;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Empreintes et jetons aléatoires : seuls les hachés des jetons, codes et codes d'activation sont stockés. */
public final class Hashes {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Hashes() {
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    /** Jeton de {@code bytes} octets aléatoires, en Base64 URL sans remplissage. */
    public static String token(int bytes) {
        var buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    /** Code à {@code digits} chiffres. */
    public static String digits(int digits) {
        var out = new StringBuilder();
        for (int i = 0; i < digits; i++) {
            out.append(RANDOM.nextInt(10));
        }
        return out.toString();
    }

    /** Comparaison en temps constant. */
    public static boolean equal(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
