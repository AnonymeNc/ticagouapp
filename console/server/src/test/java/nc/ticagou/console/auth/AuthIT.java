package nc.ticagou.console.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Connexion à la console : premier compte, mot de passe, code TOTP, blocage, CSRF, journal (ADR 0017). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthIT.Config.class)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"console.setup-token=jeton-du-socle", "console.secret-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "console.secure-cookie=true"})
class AuthIT {

    static final String PASSWORD = "un mot de passe assez long";

    /** Horloge que le test fait avancer : un code TOTP ne sert qu'une fois par pas de 30 secondes. */
    static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-09T19:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Pacific/Noumea");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:17-alpine");
        }

        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MovableClock clock;
    @Autowired LoginThrottle throttle;
    @Autowired SecretBox box;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE operator, audit_event");
        throttle.reset();
        clock.advance(Duration.ofMinutes(1));
    }

    @Test
    void sansCompteLaConsoleDemandeLePremierCompte() throws Exception {
        mvc.perform(get("/api/v1/auth/state")).andExpect(status().isOk())
                .andExpect(jsonPath("$.setupRequired").value(true))
                .andExpect(jsonPath("$.login.step").value("MOT_DE_PASSE"))
                .andExpect(jsonPath("$.operator").isEmpty());
    }

    @Test
    void lePremierCompteExigeLeJetonEtNeSeCreeQuUneFois() throws Exception {
        postJson("/api/v1/auth/setup", Map.of("token", "mauvais", "login", "jordan", "password", PASSWORD), new MockHttpSession())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("setup_token"));
        setup("jordan");
        postJson("/api/v1/auth/setup", Map.of("token", "jeton-du-socle", "login", "autre", "password", PASSWORD), new MockHttpSession())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("setup_done"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operator", Integer.class)).isEqualTo(1);
    }

    @Test
    void motDePasseTropCourtRefuse() throws Exception {
        postJson("/api/v1/auth/setup", Map.of("token", "jeton-du-socle", "login", "jordan", "password", "court"), new MockHttpSession())
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("password_weak"));
    }

    @Test
    void premiereConnexionEnregistreLeTotpPuisOuvreLApi() throws Exception {
        setup("jordan");
        var session = new MockHttpSession();
        var step = json.readTree(postJson("/api/v1/auth/login", Map.of("login", "Jordan", "password", PASSWORD), session)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(step.path("step").asText()).isEqualTo("TOTP_ENROLL");
        var secret = step.path("secret").asText();
        assertThat(step.path("uri").asText()).startsWith("otpauth://totp/");

        // Mot de passe seul : l'API reste fermée.
        mvc.perform(get("/api/v1/audit").session(session)).andExpect(status().isUnauthorized());

        postJson("/api/v1/auth/totp", Map.of("code", code(secret)), session).andExpect(status().isOk())
                .andExpect(jsonPath("$.login").value("jordan"));
        mvc.perform(get("/api/v1/audit").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("CONNEXION"))
                .andExpect(jsonPath("$[1].action").value("TOTP_ENREGISTRE"));

        var sealed = jdbc.queryForObject("SELECT totp_secret FROM operator", String.class);
        assertThat(sealed).isNotEqualTo(secret);
        assertThat(box.open(sealed)).isEqualTo(secret);
    }

    @Test
    void connexionSuivanteDemandeLeCodeEtRefuseUnCodeRejoue() throws Exception {
        setup("jordan");
        var secret = enroll("jordan");

        var session = new MockHttpSession();
        postJson("/api/v1/auth/login", Map.of("login", "jordan", "password", PASSWORD), session)
                .andExpect(jsonPath("$.step").value("TOTP")).andExpect(jsonPath("$.secret").isEmpty());
        // Même pas de 30 secondes que l'enregistrement : le code a déjà servi.
        clock.advance(Duration.ofSeconds(-60));
        postJson("/api/v1/auth/totp", Map.of("code", code(secret)), session).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("totp_invalid"));
        clock.advance(Duration.ofSeconds(90));
        postJson("/api/v1/auth/totp", Map.of("code", code(secret)), session).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/state").session(session)).andExpect(jsonPath("$.login.step").value("CONNECTE"))
                .andExpect(jsonPath("$.operator.login").value("jordan"));
    }

    @Test
    void mauvaisMotDePasseRefuseSansDireSiLeCompteExiste() throws Exception {
        setup("jordan");
        postJson("/api/v1/auth/login", Map.of("login", "jordan", "password", "pas le bon mot de passe"), new MockHttpSession())
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("bad_credentials"));
        postJson("/api/v1/auth/login", Map.of("login", "inconnu", "password", PASSWORD), new MockHttpSession())
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("bad_credentials"));
    }

    @Test
    void cinqCodesFauxBloquentLeCompte() throws Exception {
        setup("jordan");
        enroll("jordan");
        var session = new MockHttpSession();
        postJson("/api/v1/auth/login", Map.of("login", "jordan", "password", PASSWORD), session).andExpect(status().isOk());
        for (int i = 0; i < 4; i++) {
            postJson("/api/v1/auth/totp", Map.of("code", "000000"), session).andExpect(status().isUnprocessableEntity());
        }
        postJson("/api/v1/auth/totp", Map.of("code", "000000"), session).andExpect(status().isLocked())
                .andExpect(jsonPath("$.lockedUntil").isNotEmpty());
        postJson("/api/v1/auth/login", Map.of("login", "jordan", "password", PASSWORD), new MockHttpSession())
                .andExpect(status().isLocked());
        clock.advance(Duration.ofMinutes(16));
        postJson("/api/v1/auth/login", Map.of("login", "jordan", "password", PASSWORD), new MockHttpSession())
                .andExpect(status().isOk());
    }

    @Test
    void uneEcritureSansJetonCsrfEstRefusee() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("login", "jordan", "password", PASSWORD))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("csrf"));
    }

    @Test
    void laDeconnexionFermeLaSessionEtLaSessionExpireApresHuitHeures() throws Exception {
        setup("jordan");
        var secret = enroll("jordan");
        clock.advance(Duration.ofMinutes(1));
        var session = new MockHttpSession();
        postJson("/api/v1/auth/login", Map.of("login", "jordan", "password", PASSWORD), session);
        postJson("/api/v1/auth/totp", Map.of("code", code(secret)), session).andExpect(status().isOk());
        clock.advance(Duration.ofHours(8).plusMinutes(1));
        mvc.perform(get("/api/v1/audit").session(session)).andExpect(status().isUnauthorized());

        clock.advance(Duration.ofMinutes(1));
        var other = new MockHttpSession();
        postJson("/api/v1/auth/login", Map.of("login", "jordan", "password", PASSWORD), other);
        postJson("/api/v1/auth/totp", Map.of("code", code(secret)), other).andExpect(status().isOk());
        postJson("/api/v1/auth/logout", Map.of(), other).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/audit").session(other)).andExpect(status().isUnauthorized());
    }

    @Test
    void leCodeRedemandeConfirmeUneActionSensible() throws Exception {
        setup("jordan");
        var secret = enroll("jordan");
        clock.advance(Duration.ofMinutes(1));
        var session = new MockHttpSession();
        postJson("/api/v1/auth/login", Map.of("login", "jordan", "password", PASSWORD), session);
        postJson("/api/v1/auth/totp", Map.of("code", code(secret)), session).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(1));
        postJson("/api/v1/auth/confirm", Map.of("code", code(secret)), session).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'CONFIRMATION'", Integer.class)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- outils

    private void setup(String login) throws Exception {
        postJson("/api/v1/auth/setup", Map.of("token", "jeton-du-socle", "login", login, "displayName", "Jordan Moniot", "password", PASSWORD),
                new MockHttpSession()).andExpect(status().isCreated());
    }

    /** Première connexion complète ; renvoie le secret TOTP enregistré. */
    private String enroll(String login) throws Exception {
        var session = new MockHttpSession();
        var step = json.readTree(postJson("/api/v1/auth/login", Map.of("login", login, "password", PASSWORD), session)
                .andReturn().getResponse().getContentAsString());
        var secret = step.path("secret").asText();
        postJson("/api/v1/auth/totp", Map.of("code", code(secret)), session).andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(30));
        return secret;
    }

    private String code(String secret) {
        return Totp.code(secret, clock.instant().getEpochSecond() / 30);
    }

    private ResultActions postJson(String path, Map<String, ?> body, MockHttpSession session) throws Exception {
        return mvc.perform(post(path).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }
}
