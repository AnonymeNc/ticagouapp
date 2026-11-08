package nc.ticagou.console.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class TotpTest {

    /** Secret du jeu d'essai de la RFC 6238, en Base32. */
    static final String RFC_SECRET = Totp.encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void codesDeLaRfc6238() {
        assertThat(Totp.code(RFC_SECRET, 59 / 30)).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, 1111111109L / 30)).isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, 1234567890L / 30)).isEqualTo("005924");
    }

    @Test
    void acceptePasDeTrenteSecondesDeChaqueCoteEtPasPlus() {
        var now = Instant.ofEpochSecond(1_234_567_890L);
        var step = now.getEpochSecond() / 30;
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, step), now, null)).hasValue(step);
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, step - 1), now, null)).hasValue(step - 1);
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, step + 1), now, null)).hasValue(step + 1);
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, step - 2), now, null)).isEmpty();
    }

    @Test
    void unCodeNeSertQuUneFois() {
        var now = Instant.ofEpochSecond(1_234_567_890L);
        var step = now.getEpochSecond() / 30;
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, step), now, step)).isEmpty();
    }

    @Test
    void refuseCeQuiNEstPasUnCode() {
        var now = Instant.ofEpochSecond(1_234_567_890L);
        assertThat(Totp.match(RFC_SECRET, null, now, null)).isEmpty();
        assertThat(Totp.match(RFC_SECRET, "abcdef", now, null)).isEmpty();
    }

    @Test
    void lAdresseEstLueParLesApplications() {
        var secret = Totp.newSecret();
        assertThat(secret).matches("[A-Z2-7]{32}");
        assertThat(Totp.uri("jordan", secret)).startsWith("otpauth://totp/Ti%27Cagou%20Console%3Ajordan?secret=" + secret)
                .contains("issuer=Ti%27Cagou%20Console");
    }
}
