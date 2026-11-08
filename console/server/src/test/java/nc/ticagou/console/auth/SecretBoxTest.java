package nc.ticagou.console.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import nc.ticagou.console.common.ApiException;

class SecretBoxTest {

    static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    static final String OTHER_KEY = "HwECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @Test
    void chiffreEtDechiffre() {
        var box = new SecretBox(KEY);
        var sealed = box.seal("JBSWY3DPEHPK3PXP");
        assertThat(sealed).doesNotContain("JBSWY3DPEHPK3PXP");
        assertThat(box.seal("JBSWY3DPEHPK3PXP")).isNotEqualTo(sealed);
        assertThat(box.open(sealed)).isEqualTo("JBSWY3DPEHPK3PXP");
    }

    @Test
    void uneAutreCleNeLitPasLeSecret() {
        var sealed = new SecretBox(KEY).seal("JBSWY3DPEHPK3PXP");
        assertThatThrownBy(() -> new SecretBox(OTHER_KEY).open(sealed)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refuseUneCleAbsenteOuDeMauvaiseTaille() {
        assertThatThrownBy(() -> new SecretBox("")).hasMessageContaining("CONSOLE_SECRET_KEY");
        assertThatThrownBy(() -> new SecretBox("AAEC")).hasMessageContaining("CONSOLE_SECRET_KEY");
        assertThatThrownBy(() -> new SecretBox("pas du base64 !")).hasMessageContaining("CONSOLE_SECRET_KEY");
    }

    @Test
    void confirmationValableCinqMinutes() {
        var at = Instant.parse("2026-11-09T19:00:00Z");
        assertThat(SensitiveAction.isFresh(at, at.plusSeconds(300))).isTrue();
        assertThat(SensitiveAction.isFresh(at, at.plusSeconds(301))).isFalse();
        assertThat(SensitiveAction.isFresh(null, at)).isFalse();
    }

    @Test
    void reglesDeIdentifiantEtDuMotDePasse() {
        assertThat(AuthService.checkLogin(" Jordan ")).isEqualTo("jordan");
        assertThatThrownBy(() -> AuthService.checkLogin("j")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> AuthService.checkLogin("jordan moniot")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> AuthService.checkPassword("onze caract", "jordan")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> AuthService.checkPassword("jordan-et-la-suite", "jordan")).isInstanceOf(ApiException.class);
        AuthService.checkPassword("douze caract", "jordan");
    }
}
