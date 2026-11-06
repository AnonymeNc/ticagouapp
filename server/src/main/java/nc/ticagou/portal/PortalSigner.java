package nc.ticagou.portal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Signature des requêtes de synchronisation (ADR 0010) : Ed25519 de
 * {@code MÉTHODE\nchemin?requête\ndate\nSHA-256 hexadécimal du corps}. Le portail vérifie avec la clé publique
 * enregistrée à l'activation ; aucun secret partagé ne circule.
 */
public final class PortalSigner {

    public static final String HEADER_STRUCTURE = "X-Ticagou-Structure";
    public static final String HEADER_DATE = "X-Ticagou-Date";
    public static final String HEADER_SIGNATURE = "X-Ticagou-Signature";

    private PortalSigner() {
    }

    public static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String canonical(String method, String pathAndQuery, Instant date, byte[] body) {
        return method.toUpperCase() + "\n" + pathAndQuery + "\n" + date + "\n" + sha256(body == null ? new byte[0] : body);
    }

    public static String sign(PrivateKey key, String method, String pathAndQuery, Instant date, byte[] body) {
        try {
            var signer = Signature.getInstance("Ed25519");
            signer.initSign(key);
            signer.update(canonical(method, pathAndQuery, date, body).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verify(PublicKey key, String method, String pathAndQuery, Instant date, byte[] body,
                                 String signature) {
        try {
            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(canonical(method, pathAndQuery, date, body).getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
