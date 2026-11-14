package nc.ticagou.notification;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * JWT signés pour APNs (ES256) et Google OAuth (RS256), avec le JDK seul (ADR 0026). Pour ES256, le JDK fournit
 * directement la signature au format JOSE (r‖s, 64 octets) : « SHA256withECDSAinP1363Format ».
 */
public final class JwtSigner {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private JwtSigner() {
    }

    public static String es256(String headerJson, String claimsJson, PrivateKey key) {
        return sign(headerJson, claimsJson, key, "SHA256withECDSAinP1363Format");
    }

    public static String rs256(String headerJson, String claimsJson, PrivateKey key) {
        return sign(headerJson, claimsJson, key, "SHA256withRSA");
    }

    private static String sign(String headerJson, String claimsJson, PrivateKey key, String algorithm) {
        var input = B64.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8)) + "."
                + B64.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8));
        try {
            var signature = Signature.getInstance(algorithm);
            signature.initSign(key);
            signature.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + B64.encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Signature du jeton impossible", e);
        }
    }

    /** Clé privée PKCS#8 donnée en PEM (« -----BEGIN PRIVATE KEY----- ») ou en Base64 du DER. */
    public static PrivateKey privateKey(String text, String algorithm) {
        var base64 = text.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\\\n|\\s", "");
        try {
            return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Clé privée " + algorithm + " illisible", e);
        }
    }
}
