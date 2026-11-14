package nc.ticagou.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import nc.ticagou.IntegrationTest;
import nc.ticagou.license.DesktopClientService;

/** Applications mobiles : jeton porteur, CORS, session de 30 jours, téléphones hors du compte des PC (ADR 0024, 0027, 0028). */
class MobileAppIT extends IntegrationTest {

    static final String IPHONE = "direction/1.0.0 (ios)";

    String director;

    @BeforeEach
    void installer() throws Exception {
        director = install();
    }

    MvcResult loginFrom(String clientId, String name, String app) throws Exception {
        var request = post("/api/v1/auth/login").header(DesktopClientService.HEADER, clientId)
                .header(DesktopClientService.NAME_HEADER, name);
        if (app != null) {
            request.header(AppClient.HEADER, app);
        }
        return perform(request, null, Map.of("login", DIRECTOR_LOGIN, "password", DIRECTOR_PASSWORD));
    }

    Instant refreshExpiry(String rawToken) {
        return jdbc.queryForObject("SELECT expires_at FROM refresh_token WHERE token_hash = ?", Timestamp.class,
                TokenService.hash(rawToken)).toInstant();
    }

    @Test
    void compatibilitePubliqueAvecLeNomDeLaCreche() throws Exception {
        var compat = body(mvc.perform(get("/api/v1/app/compat")).andReturn());
        assertThat(compat.get("api").asInt()).isEqualTo(1);
        assertThat(compat.get("minimum").get("direction").asText()).isEqualTo("0.0.0");
        assertThat(compat.get("minimum").get("pointage").asText()).isEqualTo("0.0.0");
        assertThat(compat.get("creche").asText()).isNotBlank();
    }

    @Test
    void lesOriginesCapacitorSontAdmisesSansCookie() throws Exception {
        for (var origin : new String[] {"capacitor://localhost", "https://localhost"}) {
            var preflight = mvc.perform(options("/api/v1/auth/login").header("Origin", origin)
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "content-type, x-ticagou-app, authorization")).andReturn();
            assertThat(status(preflight)).isEqualTo(200);
            assertThat(preflight.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo(origin);
            assertThat(preflight.getResponse().getHeader("Access-Control-Allow-Credentials")).isNull();
        }
        var other = mvc.perform(options("/api/v1/auth/login").header("Origin", "https://pirate.example")
                .header("Access-Control-Request-Method", "POST")).andReturn();
        assertThat(status(other)).isEqualTo(403);
    }

    @Test
    void lApplicationRecoitSonJetonDansLaReponseEtUneSessionDeTrenteJours() throws Exception {
        var app = loginFrom("iphone-nathalie", "iPhone%20de%20Nathalie", IPHONE);
        assertThat(status(app)).isEqualTo(200);
        assertThat(app.getResponse().getHeader("Set-Cookie")).isNull();
        var refresh = body(app).get("refreshToken").asText();
        assertThat(refreshExpiry(refresh)).isAfter(Instant.now().plus(Duration.ofDays(29)));

        // Le renouvellement garde la durée de l'application.
        var rotated = perform(post("/api/v1/auth/refresh").header(DesktopClientService.HEADER, "iphone-nathalie")
                .header(AppClient.HEADER, IPHONE), null, Map.of("refreshToken", refresh));
        assertThat(status(rotated)).isEqualTo(200);
        assertThat(refreshExpiry(body(rotated).get("refreshToken").asText())).isAfter(Instant.now().plus(Duration.ofDays(29)));

        // Un navigateur garde sa semaine.
        var browser = body(loginFrom("pc-accueil", "Accueil", null)).get("refreshToken").asText();
        assertThat(refreshExpiry(browser)).isBefore(Instant.now().plus(Duration.ofDays(8)));
    }

    @Test
    void lesTelephonesNePrennentPasDePlaceDePc() throws Exception {
        // L'installation a enregistré un PC ; l'évaluation en permet deux.
        assertThat(status(loginFrom("pc-accueil", "Accueil", null))).isEqualTo(200);
        assertThat(status(loginFrom("pc-bureau", "Bureau", null))).isEqualTo(422);
        assertThat(status(loginFrom("iphone-nathalie", "iPhone%20de%20Nathalie", IPHONE))).isEqualTo(200);
        assertThat(status(loginFrom("pixel", "Pixel%208", "direction/1.0.0 (android)"))).isEqualTo(200);

        var license = body(getAs(director, "/api/v1/system/license"));
        assertThat(license.get("desktops").asInt()).isEqualTo(2);
        assertThat(license.get("mobiles").asInt()).isEqualTo(2);
        assertThat(license.get("mobileLimit").asInt()).isEqualTo(10);

        var phones = body(getAs(director, "/api/v1/system/desktops")).findParents("kind").stream()
                .filter(n -> n.get("kind").asText().equals("MOBILE")).toList();
        assertThat(phones).hasSize(2);
        assertThat(phones).anySatisfy(n -> {
            assertThat(n.get("label").asText()).isEqualTo("iPhone de Nathalie");
            assertThat(n.get("app").asText()).isEqualTo("Ti’Cagou Direction 1.0.0 · iOS");
        });
    }

    @Test
    void auDelaDuPlafondUnNouveauTelephoneEstRefuse() throws Exception {
        var structure = jdbc.queryForObject("SELECT id FROM structure LIMIT 1", UUID.class);
        for (int i = 0; i < 10; i++) {
            jdbc.update("""
                    INSERT INTO desktop_client (id, structure_id, client_hash, label, first_seen_at, last_seen_at, kind)
                    VALUES (gen_random_uuid(), ?, ?, ?, now(), now(), 'MOBILE')""", structure, "h" + i, "Téléphone " + i);
        }
        var refused = loginFrom("iphone-nathalie", "iPhone", IPHONE);
        assertThat(status(refused)).isEqualTo(422);
        assertThat(body(refused).get("code").asText()).isEqualTo("license_mobiles");

        // Un téléphone retiré libère sa place.
        jdbc.update("UPDATE desktop_client SET revoked_at = now() WHERE label = 'Téléphone 0'");
        assertThat(status(loginFrom("iphone-nathalie", "iPhone", IPHONE))).isEqualTo(200);
    }
}
