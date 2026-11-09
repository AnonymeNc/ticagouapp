package nc.ticagou.journal;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import nc.ticagou.document.Uploads;

/** Réponse HTTP d'une photo ou d'une vidéo de transmission, au type réel de son contenu (ADR 0019). */
public final class JournalPhotos {

    private JournalPhotos() {
    }

    public static ResponseEntity<byte[]> response(byte[] content) {
        var detected = Uploads.detectType(content);
        var type = detected != null && (detected.startsWith("video/") || detected.equals("image/png"))
                ? MediaType.parseMediaType(detected) : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore()).body(content);
    }
}
