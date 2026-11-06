package nc.ticagou.journal;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import nc.ticagou.document.Uploads;

/** Réponse HTTP d'une photo de transmission, au type réel de son contenu. */
public final class JournalPhotos {

    private JournalPhotos() {
    }

    public static ResponseEntity<byte[]> response(byte[] content) {
        var type = "image/png".equals(Uploads.detectType(content)) ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore()).body(content);
    }
}
