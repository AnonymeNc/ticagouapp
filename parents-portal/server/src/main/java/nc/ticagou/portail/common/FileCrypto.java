package nc.ticagou.portail.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import nc.ticagou.portail.PortalProperties;

/**
 * Chiffrement au repos des photos et PDF (AES-256-GCM). La clé de chaque structure est dérivée de la clé maîtresse
 * (HMAC-SHA256 de l'identifiant de structure) ; l'identifiant de l'élément sert de données associées, pour qu'un
 * fichier ne puisse pas être servi à la place d'un autre.
 */
@Component
public class FileCrypto {

    private static final Logger log = LoggerFactory.getLogger(FileCrypto.class);
    private static final byte[] MAGIC = "TCP1".getBytes(StandardCharsets.US_ASCII);
    private static final int IV_BYTES = 12;

    private final byte[] master;
    private final SecureRandom random = new SecureRandom();

    public FileCrypto(PortalProperties properties) {
        if (properties.fileKey() == null || properties.fileKey().isBlank()) {
            // Développement et tests : clé éphémère, les fichiers ne survivent pas à un redémarrage.
            log.warn("PORTAL_FILE_KEY absente : clé de chiffrement éphémère (développement seulement).");
            master = new byte[32];
            random.nextBytes(master);
        } else {
            master = Base64.getDecoder().decode(properties.fileKey().strip());
            if (master.length != 32) {
                throw new IllegalStateException("PORTAL_FILE_KEY doit faire 32 octets (Base64).");
            }
        }
    }

    public byte[] encrypt(UUID structureId, UUID itemId, byte[] plain) {
        try {
            var iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(structureId), new GCMParameterSpec(128, iv));
            cipher.updateAAD(itemId.toString().getBytes(StandardCharsets.US_ASCII));
            var encrypted = cipher.doFinal(plain);
            return ByteBuffer.allocate(MAGIC.length + IV_BYTES + encrypted.length).put(MAGIC).put(iv).put(encrypted).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Chiffrement impossible", e);
        }
    }

    public byte[] decrypt(UUID structureId, UUID itemId, byte[] stored) {
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(structureId), new GCMParameterSpec(128, stored, MAGIC.length, IV_BYTES));
            cipher.updateAAD(itemId.toString().getBytes(StandardCharsets.US_ASCII));
            int offset = MAGIC.length + IV_BYTES;
            return cipher.doFinal(stored, offset, stored.length - offset);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Fichier illisible ou altéré", e);
        }
    }

    private SecretKey key(UUID structureId) throws GeneralSecurityException {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(master, "HmacSHA256"));
        return new SecretKeySpec(mac.doFinal(structureId.toString().getBytes(StandardCharsets.US_ASCII)), "AES");
    }
}
