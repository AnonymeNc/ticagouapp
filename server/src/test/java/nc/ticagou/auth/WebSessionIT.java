package nc.ticagou.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.http.Cookie;
import nc.ticagou.IntegrationTest;

/** Sur internet : assistant par jeton, double authentification obligatoire, session par cookie, limite par adresse. */
@TestPropertySource(properties = {"ticagou.setup.token=jeton-de-test", "ticagou.security.mfa-required=true"})
class WebSessionIT extends IntegrationTest {

    private MvcResult installWithToken() throws Exception {
        return perform(post("/api/v1/setup").header("X-Ticagou-Setup", "jeton-de-test"), null, setupRequest());
    }

    private MvcResult loginWith(String code) throws Exception {
        var request = new HashMap<String, Object>(Map.of("login", DIRECTOR_LOGIN, "password", DIRECTOR_PASSWORD));
        if (code != null) {
            request.put("code", code);
        }
        return perform(post("/api/v1/auth/login"), null, request);
    }

    private String secretOf(String login) {
        return jdbc.queryForObject("SELECT totp_secret FROM app_user WHERE login = ?", String.class, login);
    }

    private static String currentCode(String secret, int stepsAhead) {
        return Totp.code(secret, Instant.now().getEpochSecond() / 30 + stepsAhead);
    }

    @Test
    void lAssistantDemandeLeJetonDeDemarrage() throws Exception {
        var refused = perform(post("/api/v1/setup"), null, setupRequest());
        assertThat(status(refused)).isEqualTo(403);
        assertThat(body(refused).get("code").asText()).isEqualTo("setup_token");

        var wrong = perform(post("/api/v1/setup").header("X-Ticagou-Setup", "autre"), null, setupRequest());
        assertThat(status(wrong)).isEqualTo(403);

        assertThat(status(installWithToken())).isEqualTo(201);
        assertThat(status(installWithToken())).as("une seule fois").isEqualTo(409);
    }

    @Test
    void laDirectionNAccedeARienAvantDAvoirEnregistreSonTelephone() throws Exception {
        var session = body(installWithToken());
        assertThat(session.at("/user/mfaEnrollmentRequired").asBoolean()).isTrue();
        var token = session.get("accessToken").asText();

        var blocked = getAs(token, "/api/v1/children");
        assertThat(status(blocked)).isEqualTo(403);
        assertThat(body(blocked).get("code").asText()).isEqualTo("mfa_enrollment_required");

        var setup = body(perform(post("/api/v1/auth/mfa/start"), token, null));
        assertThat(setup.get("uri").asText()).startsWith("otpauth://totp/");
        var secret = setup.get("secret").asText();
        var wrong = perform(post("/api/v1/auth/mfa/confirm"), token, Map.of("code", "000000"));
        assertThat(body(wrong).get("code").asText()).isEqualTo("mfa_invalid");
        assertThat(status(perform(post("/api/v1/auth/mfa/confirm"), token, Map.of("code", currentCode(secret, -1))))).isEqualTo(204);
        assertThat(auditCount("MFA_ENABLED")).isEqualTo(1);

        var missing = loginWith(null);
        assertThat(status(missing)).isEqualTo(401);
        assertThat(body(missing).get("code").asText()).isEqualTo("mfa_required");
        assertThat(body(loginWith("000000")).get("code").asText()).isEqualTo("mfa_invalid");

        var code = currentCode(secret, 0);
        var logged = loginWith(code);
        assertThat(status(logged)).isEqualTo(200);
        assertThat(body(logged).at("/user/mfaEnabled").asBoolean()).isTrue();
        assertThat(status(getAs(body(logged).get("accessToken").asText(), "/api/v1/children"))).isEqualTo(200);
        assertThat(body(loginWith(code)).get("code").asText()).as("un code ne sert qu'une fois").isEqualTo("mfa_invalid");
    }

    @Test
    void laDirectionReinitialiseLeTelephoneDUnePersonne() throws Exception {
        var token = body(installWithToken()).get("accessToken").asText();
        var secret = body(perform(post("/api/v1/auth/mfa/start"), token, null)).get("secret").asText();
        perform(post("/api/v1/auth/mfa/confirm"), token, Map.of("code", currentCode(secret, 0)));
        var director = body(loginWith(currentCode(secret, 1))).get("accessToken").asText();
        var user = createUser(director, "COMPTABILITE", "annie.dubois", "Comptes-2026!", null);
        var id = UUID.fromString(user.get("id").asText());

        var compta = accessToken("annie.dubois", "Comptes-2026!");
        var other = body(perform(post("/api/v1/auth/mfa/start"), compta, null)).get("secret").asText();
        perform(post("/api/v1/auth/mfa/confirm"), compta, Map.of("code", currentCode(other, 0)));
        assertThat(secretOf("annie.dubois")).isEqualTo(other);

        assertThat(status(perform(delete("/api/v1/auth/mfa/" + id), director, null))).isEqualTo(204);

        assertThat(jdbc.queryForObject("SELECT totp_enabled_at IS NULL FROM app_user WHERE id = ?", Boolean.class, id)).isTrue();
        assertThat(auditCount("MFA_RESET")).isEqualTo(1);
    }

    @Test
    void leNavigateurGardeSaSessionDansUnCookieInaccessibleAuJavaScript() throws Exception {
        installWithToken();
        var logged = perform(post("/api/v1/auth/login").header(SessionCookies.HEADER, "cookie"), null,
                Map.of("login", DIRECTOR_LOGIN, "password", DIRECTOR_PASSWORD));

        assertThat(body(logged).get("refreshToken").isNull()).as("pas de jeton dans la réponse").isTrue();
        var header = logged.getResponse().getHeader("Set-Cookie");
        assertThat(header).contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth");
        var value = logged.getResponse().getCookie(SessionCookies.NAME).getValue();

        var refreshed = perform(post("/api/v1/auth/refresh").cookie(new Cookie(SessionCookies.NAME, value)), null, null);
        assertThat(status(refreshed)).isEqualTo(200);
        assertThat(body(refreshed).get("refreshToken").isNull()).isTrue();
        var next = refreshed.getResponse().getCookie(SessionCookies.NAME).getValue();
        assertThat(next).isNotEqualTo(value);

        assertThat(status(perform(post("/api/v1/auth/refresh"), null, null))).as("sans cookie").isEqualTo(401);

        var out = perform(post("/api/v1/auth/logout").cookie(new Cookie(SessionCookies.NAME, next)), null, null);
        assertThat(status(out)).isEqualTo(204);
        assertThat(out.getResponse().getCookie(SessionCookies.NAME).getMaxAge()).isZero();
        assertThat(status(perform(post("/api/v1/auth/refresh").cookie(new Cookie(SessionCookies.NAME, next)), null, null)))
                .as("session fermée").isEqualTo(401);
    }

    @Test
    void uneAdresseQuiEnchaineLesEchecsEstBloqueeSurTousLesComptes() throws Exception {
        installWithToken();
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
            perform(post("/api/v1/auth/login"), null, Map.of("login", "inconnu" + i, "password", "Mauvais-2026!"));
        }

        var blocked = perform(post("/api/v1/auth/login"), null, Map.of("login", DIRECTOR_LOGIN, "password", DIRECTOR_PASSWORD));

        assertThat(status(blocked)).isEqualTo(429);
        assertThat(body(blocked).get("code").asText()).isEqualTo("login_throttled");
    }
}
