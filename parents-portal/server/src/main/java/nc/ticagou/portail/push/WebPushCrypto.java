package nc.ticagou.portail.push;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Chiffrement d'une notification Web Push (RFC 8291, contenu {@code aes128gcm} du RFC 8188) et outils P-256, avec le
 * seul JDK (ADR 0018). Vérifié par le vecteur de l'annexe A du RFC 8291.
 */
public final class WebPushCrypto {

    /** Taille d'enregistrement annoncée ; une notification tient dans un seul enregistrement. */
    static final int RECORD_SIZE = 4096;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Decoder B64 = Base64.getUrlDecoder();
    private static final Base64.Encoder B64_OUT = Base64.getUrlEncoder().withoutPadding();

    private WebPushCrypto() {
    }

    /** Chiffre {@code plaintext} pour l'abonnement (clé publique {@code p256dh} et secret {@code auth}, en Base64 URL). */
    public static byte[] encrypt(byte[] plaintext, String p256dh, String auth) throws GeneralSecurityException {
        var salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(plaintext, decode(p256dh), decode(auth), newKeyPair(), salt);
    }

    static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret, KeyPair asKeys, byte[] salt)
            throws GeneralSecurityException {
        if (plaintext.length > RECORD_SIZE - 17 - 86) {
            throw new IllegalArgumentException("Notification trop longue");
        }
        var asPublic = uncompressed((ECPublicKey) asKeys.getPublic());
        var agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(asKeys.getPrivate());
        agreement.doPhase(publicKey(uaPublic), true);
        var ecdhSecret = agreement.generateSecret();

        // RFC 8291 § 3.4 : IKM = HKDF(auth_secret, ecdh_secret, "WebPush: info" || 0x00 || ua_public || as_public, 32).
        var keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic);
        var ikm = hkdf(authSecret, ecdhSecret, keyInfo, 32);
        // RFC 8188 § 2.2 et 2.3.
        var cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        var nonce = hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);

        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        // Dernier (et seul) enregistrement : délimiteur 0x02, sans bourrage.
        var encrypted = cipher.doFinal(concat(plaintext, new byte[] {2}));

        return ByteBuffer.allocate(16 + 4 + 1 + asPublic.length + encrypted.length)
                .put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic).put(encrypted).array();
    }

    // --- P-256 --------------------------------------------------------------------------------

    public static KeyPair newKeyPair() throws GeneralSecurityException {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    static ECParameterSpec params() throws GeneralSecurityException {
        return ((ECPublicKey) newKeyPair().getPublic()).getParams();
    }

    /** Point non compressé (0x04 || X || Y, 65 octets). */
    public static byte[] uncompressed(ECPublicKey key) {
        var out = new byte[65];
        out[0] = 4;
        copyFixed(key.getW().getAffineX(), out, 1);
        copyFixed(key.getW().getAffineY(), out, 33);
        return out;
    }

    static ECPublicKey publicKey(byte[] uncompressed) throws GeneralSecurityException {
        if (uncompressed.length != 65 || uncompressed[0] != 4) {
            throw new GeneralSecurityException("Clé publique P-256 invalide");
        }
        var point = new ECPoint(new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33)),
                new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65)));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, params()));
    }

    static ECPrivateKey privateKey(byte[] scalar) throws GeneralSecurityException {
        return (ECPrivateKey) KeyFactory.getInstance("EC")
                .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), params()));
    }

    public static byte[] decode(String base64Url) {
        return B64.decode(base64Url.strip().replace('+', '-').replace('/', '_').replace("=", ""));
    }

    public static String encode(byte[] bytes) {
        return B64_OUT.encodeToString(bytes);
    }

    // --- Outils -------------------------------------------------------------------------------

    /** HKDF-SHA-256 pour une sortie d'au plus 32 octets (un seul bloc). */
    static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
        var prk = hmac(salt, ikm);
        return Arrays.copyOf(hmac(prk, concat(info, new byte[] {1})), length);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    static byte[] concat(byte[]... parts) {
        int length = 0;
        for (var p : parts) {
            length += p.length;
        }
        var out = new byte[length];
        int offset = 0;
        for (var p : parts) {
            System.arraycopy(p, 0, out, offset, p.length);
            offset += p.length;
        }
        return out;
    }

    private static void copyFixed(BigInteger value, byte[] out, int offset) {
        var bytes = value.toByteArray();
        int start = Math.max(0, bytes.length - 32);
        int length = bytes.length - start;
        System.arraycopy(bytes, start, out, offset + 32 - length, length);
    }
}
