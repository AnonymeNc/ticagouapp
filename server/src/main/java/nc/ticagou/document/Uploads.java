package nc.ticagou.document;

import java.util.Set;

import nc.ticagou.common.ApiException;

/**
 * Types et tailles acceptés pour les photos et documents joints. Le type est déduit des premiers
 * octets du fichier, jamais du type annoncé par le client.
 */
public final class Uploads {

    public static final long MAX_PHOTO_BYTES = 2L * 1024 * 1024;
    public static final long MAX_DOCUMENT_BYTES = 10L * 1024 * 1024;
    private static final Set<String> PHOTO_TYPES = Set.of("image/png", "image/jpeg");
    private static final Set<String> DOCUMENT_TYPES = Set.of("application/pdf", "image/png", "image/jpeg");

    private Uploads() {
    }

    /** Vérifie une photo et renvoie son type réel. */
    public static String checkPhoto(byte[] content) {
        if (content.length == 0 || content.length > MAX_PHOTO_BYTES) {
            throw ApiException.rule("photo_size", "La photo doit peser au plus 2 Mo.");
        }
        var type = detect(content);
        if (type == null || !PHOTO_TYPES.contains(type)) {
            throw ApiException.rule("photo_type", "La photo doit être une image JPEG ou PNG.");
        }
        return type;
    }

    /** Vérifie un document et renvoie son type réel. */
    public static String checkDocument(byte[] content) {
        if (content.length == 0 || content.length > MAX_DOCUMENT_BYTES) {
            throw ApiException.rule("document_size", "Le document doit peser au plus 10 Mo.");
        }
        var type = detect(content);
        if (type == null || !DOCUMENT_TYPES.contains(type)) {
            throw ApiException.rule("document_type", "Le document doit être un PDF ou une image JPEG ou PNG.");
        }
        return type;
    }

    /** Type réel d'un contenu déjà vérifié, d'après ses premiers octets ; {@code null} s'il est inconnu. */
    public static String detectType(byte[] content) {
        return detect(content);
    }

    static String detect(byte[] c) {
        if (startsWith(c, 0x25, 0x50, 0x44, 0x46)) {
            return "application/pdf";
        }
        if (startsWith(c, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        if (startsWith(c, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        return null;
    }

    private static boolean startsWith(byte[] content, int... prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((content[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** Nom de fichier proposé au téléchargement, sans chemin ni caractères dangereux. */
    public static String safeFileName(String original, String fallback) {
        if (original == null || original.isBlank()) {
            return fallback;
        }
        var name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"<>:|?*]", "_").trim();
        if (name.isEmpty() || name.startsWith(".")) {
            return fallback;
        }
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    /** Extension conventionnelle d'un type accepté. */
    public static String extension(String contentType) {
        return switch (contentType) {
            case "application/pdf" -> "pdf";
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            default -> "bin";
        };
    }
}
