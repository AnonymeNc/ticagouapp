package nc.ticagou.auth;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Codes à usage unique (RFC 6238 : HMAC-SHA1, 6 chiffres, 30 secondes), lus par toute application d'authentification. */
final class Totp {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int PERIOD_SECONDS = 30;
    private static final int SECRET_BYTES = 20;
    /** Un pas de part et d'autre : l'horloge d'un téléphone peut retarder ou avancer de 30 secondes. */
    private static final int DRIFT = 1;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    static String newSecret() {
        var bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return encode(bytes);
    }

    static String uri(String account, String secret) {
        var label = URLEncoder.encode("Ti'Cagou:" + account, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/%s?secret=%s&issuer=Ti%%27Cagou&digits=6&period=%d".formatted(label, secret, PERIOD_SECONDS);
    }

    /** Pas de temps du code s'il est valable et plus récent que {@code lastStep} ; vide sinon. */
    static OptionalLong match(String secret, String code, Instant now, Long lastStep) {
        if (code == null || !code.matches("\\d{6}")) {
            return OptionalLong.empty();
        }
        var current = now.getEpochSecond() / PERIOD_SECONDS;
        var given = code.getBytes(StandardCharsets.US_ASCII);
        for (long step = current - DRIFT; step <= current + DRIFT; step++) {
            if ((lastStep == null || step > lastStep)
                    && MessageDigest.isEqual(given, code(secret, step).getBytes(StandardCharsets.US_ASCII))) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    static String code(String secret, long step) {
        try {
            var mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decode(secret), "HmacSHA1"));
            var hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int value = ByteBuffer.wrap(hash, offset, Integer.BYTES).getInt() & 0x7fffffff;
            return "%06d".formatted(value % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String encode(byte[] bytes) {
        var out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    private static byte[] decode(String value) {
        var out = new byte[value.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (char c : value.toCharArray()) {
            buffer = (buffer << 5) | ALPHABET.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out[index++] = (byte) (buffer >> (bits - 8));
                bits -= 8;
            }
        }
        return out;
    }
}
