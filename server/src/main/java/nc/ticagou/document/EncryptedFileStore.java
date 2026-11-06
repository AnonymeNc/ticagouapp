package nc.ticagou.document;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import nc.ticagou.config.TiCagouProperties;

/**
 * Fichiers joints chiffrés sur le disque du serveur (ADR 0003) : AES-256-GCM, IV aléatoire par
 * fichier, en-tête « TCG1 ». Écritures et suppressions suivent la transaction : un fichier écrit
 * dans une transaction annulée est effacé ; un fichier supprimé ne l'est qu'après validation.
 */
@Service
public class EncryptedFileStore {

    private static final Logger log = LoggerFactory.getLogger(EncryptedFileStore.class);
    private static final byte[] MAGIC = "TCG1".getBytes(StandardCharsets.US_ASCII);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String SECRET_NAME = "document-key";

    private final StoredFileRepository files;
    private final Path root;
    private final SecretKey key;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public EncryptedFileStore(StoredFileRepository files, TiCagouProperties properties, JdbcTemplate jdbc,
                              Clock clock) throws IOException {
        this.files = files;
        this.root = properties.storage().dir().toAbsolutePath().normalize();
        this.key = loadKey(properties.storage().key(), jdbc);
        this.clock = clock;
        Files.createDirectories(root);
    }

    private static SecretKey loadKey(String configured, JdbcTemplate jdbc) {
        byte[] bytes;
        if (configured.isBlank()) {
            var generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            jdbc.update("INSERT INTO server_secret (name, value) VALUES (?, ?) ON CONFLICT (name) DO NOTHING",
                    SECRET_NAME, generated);
            bytes = jdbc.queryForObject("SELECT value FROM server_secret WHERE name = ?", byte[].class, SECRET_NAME);
        } else {
            bytes = Base64.getDecoder().decode(configured);
        }
        if (bytes == null || bytes.length != 32) {
            throw new IllegalStateException("La clé de chiffrement des documents doit faire 32 octets (Base64)");
        }
        return new SecretKeySpec(bytes, "AES");
    }

    /** Chiffre et écrit le contenu ; à appeler dans une transaction. */
    public StoredFile store(UUID structureId, byte[] content, String contentType) {
        var id = UUID.randomUUID();
        var now = clock.instant();
        var date = now.atZone(ZoneOffset.UTC);
        var relative = "%s/%d/%02d/%s.bin".formatted(structureId, date.getYear(), date.getMonthValue(), id);
        var target = resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, encrypt(content, id), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("Écriture du document impossible", e);
        }
        afterRollback(() -> deleteQuietly(target));
        return files.save(new StoredFile(id, structureId, relative, contentType, content.length, sha256(content), now));
    }

    public byte[] read(StoredFile file) {
        try {
            var plain = decrypt(Files.readAllBytes(resolve(file.getStoragePath())), file.getId());
            if (!sha256(plain).equals(file.getSha256())) {
                throw new IllegalStateException("Empreinte du document incorrecte : " + file.getId());
            }
            return plain;
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture du document impossible", e);
        }
    }

    /** Supprime les métadonnées tout de suite, les fichiers après validation de la transaction. */
    public void delete(Collection<StoredFile> toDelete) {
        if (toDelete.isEmpty()) {
            return;
        }
        var paths = toDelete.stream().map(f -> resolve(f.getStoragePath())).toList();
        files.deleteAll(toDelete);
        afterCommit(() -> paths.forEach(EncryptedFileStore::deleteQuietly));
    }

    public void delete(StoredFile file) {
        delete(List.of(file));
    }

    private Path resolve(String relative) {
        var path = root.resolve(relative).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Chemin de document invalide");
        }
        return path;
    }

    // L'identifiant du fichier sert de données associées : un contenu chiffré ne peut pas être
    // échangé avec celui d'un autre document.
    private byte[] encrypt(byte[] plain, UUID id) {
        try {
            var iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(id.toString().getBytes(StandardCharsets.US_ASCII));
            var encrypted = cipher.doFinal(plain);
            return ByteBuffer.allocate(MAGIC.length + IV_BYTES + encrypted.length)
                    .put(MAGIC).put(iv).put(encrypted).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Chiffrement impossible", e);
        }
    }

    private byte[] decrypt(byte[] stored, UUID id) {
        if (stored.length < MAGIC.length + IV_BYTES || !Arrays.equals(Arrays.copyOf(stored, MAGIC.length), MAGIC)) {
            throw new IllegalStateException("Format de document inconnu : " + id);
        }
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, stored, MAGIC.length, IV_BYTES));
            cipher.updateAAD(id.toString().getBytes(StandardCharsets.US_ASCII));
            int offset = MAGIC.length + IV_BYTES;
            return cipher.doFinal(stored, offset, stored.length - offset);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Document illisible ou altéré : " + id, e);
        }
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void afterRollback(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        action.run();
                    }
                }
            });
        }
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Fichier non supprimé : {}", path, e);
        }
    }
}
