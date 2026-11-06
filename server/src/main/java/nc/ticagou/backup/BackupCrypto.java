package nc.ticagou.backup;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Chiffrement des archives de sauvegarde (ADR 0007), sans dépendance externe : clé AES-256 dérivée de la clé de
 * récupération (PBKDF2-HMAC-SHA256, sel aléatoire), puis AES-GCM par blocs de 1 Mio pour chiffrer des archives de
 * plusieurs gigaoctets sans tout garder en mémoire. Chaque bloc a son propre nonce (préfixe aléatoire + compteur) ;
 * le numéro du bloc et l'indicateur « dernier bloc » sont authentifiés, si bien qu'un bloc retiré, déplacé ou une
 * archive tronquée sont détectés.
 *
 * <pre>« TICAGOU-BK1 » | sel (16) | préfixe de nonce (8) | blocs : longueur (4) + chiffré + étiquette (16)</pre>
 */
public final class BackupCrypto {

    static final byte[] MAGIC = "TICAGOU-BK1".getBytes(StandardCharsets.US_ASCII);
    static final int CHUNK = 1024 * 1024;
    static final int ITERATIONS = 210_000;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private BackupCrypto() {
    }

    /** Clé de récupération imprimable : 6 groupes de 4 caractères sans ambiguïté (0/O, 1/I), ~120 bits. */
    public static String newRecoveryKey() {
        var key = new StringBuilder();
        for (int i = 0; i < 24; i++) {
            if (i > 0 && i % 4 == 0) {
                key.append('-');
            }
            key.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return key.toString();
    }

    /** Saisie tolérante : majuscules, sans espaces ni tirets. */
    static char[] normalize(String recoveryKey) {
        return recoveryKey.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT).toCharArray();
    }

    public static void encrypt(InputStream in, OutputStream out, String recoveryKey) throws IOException {
        var salt = random(16);
        var prefix = random(8);
        var key = derive(recoveryKey, salt);
        var data = new DataOutputStream(out);
        data.write(MAGIC);
        data.write(salt);
        data.write(prefix);
        byte[] current = in.readNBytes(CHUNK);
        int counter = 0;
        while (true) {
            byte[] next = current.length < CHUNK ? new byte[0] : in.readNBytes(CHUNK);
            boolean last = next.length == 0;
            byte[] sealed = seal(key, prefix, counter, last, current, Cipher.ENCRYPT_MODE);
            data.writeInt(sealed.length);
            data.write(sealed);
            if (last) {
                break;
            }
            current = next;
            counter++;
        }
        data.flush();
    }

    public static void decrypt(InputStream in, OutputStream out, String recoveryKey) throws IOException {
        var data = new DataInputStream(in);
        var magic = data.readNBytes(MAGIC.length);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException("Ce fichier n’est pas une sauvegarde Ti’Cagou.");
        }
        var salt = data.readNBytes(16);
        var prefix = data.readNBytes(8);
        var key = derive(recoveryKey, salt);
        int counter = 0;
        while (true) {
            int length;
            try {
                length = data.readInt();
            } catch (EOFException e) {
                throw new IOException("Sauvegarde incomplète : le dernier bloc manque.");
            }
            if (length < 16 || length > CHUNK + 16) {
                throw new IOException("Sauvegarde endommagée.");
            }
            var sealed = data.readNBytes(length);
            byte[] plain;
            boolean last;
            try {
                plain = open(key, prefix, counter, false, sealed);
                last = false;
            } catch (IOException notMiddle) {
                plain = open(key, prefix, counter, true, sealed);
                last = true;
            }
            out.write(plain);
            if (last) {
                if (data.read() != -1) {
                    throw new IOException("Sauvegarde endommagée : des données suivent le dernier bloc.");
                }
                out.flush();
                return;
            }
            counter++;
        }
    }

    /** Chiffre un petit contenu en mémoire (tests, empreintes de contrôle). */
    public static byte[] encrypt(byte[] content, String recoveryKey) throws IOException {
        var out = new ByteArrayOutputStream();
        encrypt(new ByteArrayInputStream(content), out, recoveryKey);
        return out.toByteArray();
    }

    public static byte[] decrypt(byte[] content, String recoveryKey) throws IOException {
        var out = new ByteArrayOutputStream();
        decrypt(new ByteArrayInputStream(content), out, recoveryKey);
        return out.toByteArray();
    }

    private static byte[] open(SecretKeySpec key, byte[] prefix, int counter, boolean last, byte[] sealed)
            throws IOException {
        try {
            return crypt(key, prefix, counter, last, sealed, Cipher.DECRYPT_MODE);
        } catch (GeneralSecurityException e) {
            throw new IOException("Clé de récupération incorrecte ou sauvegarde endommagée.", e);
        }
    }

    private static byte[] seal(SecretKeySpec key, byte[] prefix, int counter, boolean last, byte[] plain, int mode)
            throws IOException {
        try {
            return crypt(key, prefix, counter, last, plain, mode);
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private static byte[] crypt(SecretKeySpec key, byte[] prefix, int counter, boolean last, byte[] input, int mode)
            throws GeneralSecurityException {
        var nonce = ByteBuffer.allocate(12).put(prefix).putInt(counter).array();
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(ByteBuffer.allocate(5).putInt(counter).put((byte) (last ? 1 : 0)).array());
        return cipher.doFinal(input);
    }

    private static SecretKeySpec derive(String recoveryKey, byte[] salt) throws IOException {
        try {
            var spec = new PBEKeySpec(normalize(recoveryKey), salt, ITERATIONS, 256);
            var bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(bytes, "AES");
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private static byte[] random(int length) {
        var bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
