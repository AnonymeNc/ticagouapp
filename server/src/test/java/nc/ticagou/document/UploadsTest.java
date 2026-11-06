package nc.ticagou.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import nc.ticagou.common.ApiException;

class UploadsTest {

    static final byte[] PDF = "%PDF-1.7 contenu".getBytes();
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0};
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0};

    @Test
    void typeDeduitDuContenu() {
        assertThat(Uploads.checkDocument(PDF)).isEqualTo("application/pdf");
        assertThat(Uploads.checkDocument(PNG)).isEqualTo("image/png");
        assertThat(Uploads.checkPhoto(JPEG)).isEqualTo("image/jpeg");
    }

    @Test
    void refuseUnFauxPdfEtUnePhotoPdf() {
        assertThatThrownBy(() -> Uploads.checkDocument("<script>alert(1)</script>".getBytes()))
                .isInstanceOf(ApiException.class).hasMessageContaining("PDF");
        assertThatThrownBy(() -> Uploads.checkPhoto(PDF)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Uploads.checkDocument(new byte[0])).isInstanceOf(ApiException.class);
    }

    @Test
    void nomDeFichierSansChemin() {
        assertThat(Uploads.safeFileName("C:\\Users\\x\\PAI 2026.pdf", "document.pdf")).isEqualTo("PAI 2026.pdf");
        assertThat(Uploads.safeFileName("../../etc/passwd", "document.pdf")).isEqualTo("passwd");
        assertThat(Uploads.safeFileName(".htaccess", "document.pdf")).isEqualTo("document.pdf");
        assertThat(Uploads.safeFileName("a\"b<c>.pdf", "document.pdf")).isEqualTo("a_b_c_.pdf");
    }
}
