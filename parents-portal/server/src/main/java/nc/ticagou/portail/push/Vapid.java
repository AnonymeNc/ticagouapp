package nc.ticagou.portail.push;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;

/** Identification du portail auprès des services de notification (VAPID, RFC 8292) : jeton ES256 signé. */
public final class Vapid {

    private Vapid() {
    }

    /** {@code audience} : origine du service (« https://fcm.googleapis.com ») ; {@code expiresAt} en secondes. */
    static String token(KeyPair keys, String audience, String subject, long expiresAt) throws GeneralSecurityException {
        var header = WebPushCrypto.encode("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        var claims = WebPushCrypto.encode("{\"aud\":\"%s\",\"exp\":%d,\"sub\":\"%s\"}"
                .formatted(json(audience), expiresAt, json(subject)).getBytes(StandardCharsets.UTF_8));
        var signer = Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign(keys.getPrivate());
        signer.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
        return header + "." + claims + "." + WebPushCrypto.encode(signer.sign());
    }

    /** En-tête {@code Authorization} pour un abonnement. */
    static String authorization(KeyPair keys, String endpoint, String subject, long expiresAt) throws GeneralSecurityException {
        var uri = URI.create(endpoint);
        var audience = uri.getScheme() + "://" + uri.getRawAuthority();
        return "vapid t=" + token(keys, audience, subject, expiresAt) + ", k="
                + WebPushCrypto.encode(WebPushCrypto.uncompressed((ECPublicKey) keys.getPublic()));
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
