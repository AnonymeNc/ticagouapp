package nc.ticagou.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** En-tête X-Ticagou-App des applications mobiles (ADR 0024, 0027). */
class AppClientTest {

    @Test
    void litLesApplicationsConnues() {
        var app = AppClient.parse("direction/1.4.0 (ios)").orElseThrow();
        assertThat(app).isEqualTo(new AppClient("direction", "1.4.0", "ios"));
        assertThat(app.isDirection()).isTrue();
        assertThat(app.label()).isEqualTo("Ti’Cagou Direction 1.4.0 · iOS");
        assertThat(AppClient.parse(" pointage/2.0.13 (android) ").orElseThrow().isDirection()).isFalse();
    }

    @Test
    void ignoreUnNavigateurOuUnEnTeteMalForme() {
        assertThat(AppClient.parse(null)).isEmpty();
        assertThat(AppClient.parse("")).isEmpty();
        assertThat(AppClient.parse("direction/1.4 (ios)")).isEmpty();
        assertThat(AppClient.parse("direction/1.4.0 (windows)")).isEmpty();
        assertThat(AppClient.parse("admin/1.4.0 (ios)")).isEmpty();
        assertThat(AppClient.parse("direction/1.4.0 (ios)\r\nX: y")).isEmpty();
    }

    @Test
    void compareLesVersionsNumeriquement() {
        var app = new AppClient("direction", "1.9.0", "ios");
        assertThat(app.olderThan("1.10.0")).isTrue();
        assertThat(app.olderThan("1.9.0")).isFalse();
        assertThat(app.olderThan("1.8.12")).isFalse();
        assertThat(AppClient.compare("2.0.0", "1.99.99")).isPositive();
    }
}
