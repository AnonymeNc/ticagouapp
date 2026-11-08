package nc.ticagou.console.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import nc.ticagou.console.ConsoleProperties;

/** Chiffrement des secrets TOTP en base (AES-256-GCM, IV aléatoire de 12 octets, Base64 de IV ‖ texte chiffré). */
@Component
public class SecretBox {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    @Autowired
    public SecretBox(ConsoleProperties properties) {
        this(properties.secretKey());
    }

    SecretBox(String base64Key) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key == null ? "" : base64Key.strip());
        } catch (IllegalArgumentException e) {
            raw = new byte[0];
        }
        if (raw.length != 32) {
            throw new IllegalStateException("CONSOLE_SECRET_KEY manquante ou invalide : 32 octets en Base64 (openssl rand -base64 32).");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String seal(String plain) {
        try {
            var iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            var sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String open(String sealed) {
        try {
            var bytes = Base64.getDecoder().decode(sealed);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES));
            return new String(cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Secret illisible : la clé CONSOLE_SECRET_KEY a-t-elle changé ?", e);
        }
    }
}
