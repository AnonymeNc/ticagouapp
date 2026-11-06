package nc.ticagou.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import nc.ticagou.IntegrationTest;

class AuthIT extends IntegrationTest {

    String directorToken;

    @BeforeEach
    void installer() throws Exception {
        directorToken = install();
    }

    @Test
    void connexionParMotDePasse() throws Exception {
        var result = perform(post("/api/v1/auth/login"), null,
                Map.of("login", "NATHALIE.KALOI", "password", DIRECTOR_PASSWORD));

        assertThat(status(result)).isEqualTo(200);
        var session = body(result);
        assertThat(session.get("expiresIn").asLong()).isEqualTo(900);
        assertThat(session.at("/user/firstName").asText()).isEqualTo("Nathalie");
        assertThat(session.at("/user/authMethod").asText()).isEqualTo("PASSWORD");

        var me = body(getAs(session.get("accessToken").asText(), "/api/v1/auth/me"));
        assertThat(me.get("role").asText()).isEqualTo("DIRECTION");
        assertThat(me.get("lastLoginAt").isNull()).isFalse();
    }

    @Test
    void mauvaisMotDePasseOuIdentifiantInconnuMemeReponse() throws Exception {
        var wrongPassword = perform(post("/api/v1/auth/login"), null,
                Map.of("login", DIRECTOR_LOGIN, "password", "mauvais-mot-de-passe"));
        var unknownLogin = perform(post("/api/v1/auth/login"), null,
                Map.of("login", "inconnu", "password", "mauvais-mot-de-passe"));

        assertThat(status(wrongPassword)).isEqualTo(401);
        assertThat(status(unknownLogin)).isEqualTo(401);
        assertThat(body(wrongPassword).get("detail")).isEqualTo(body(unknownLogin).get("detail"));
        assertThat(auditCount("LOGIN_FAILED")).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'LOGIN_FAILED' AND structure_id IS NULL",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void bloqueApresCinqEchecs() throws Exception {
        for (int i = 0; i < 4; i++) {
            assertThat(status(perform(post("/api/v1/auth/login"), null,
                    Map.of("login", DIRECTOR_LOGIN, "password", "mauvais-" + i)))).isEqualTo(401);
        }
        var fifth = perform(post("/api/v1/auth/login"), null, Map.of("login", DIRECTOR_LOGIN, "password", "mauvais"));
        assertThat(status(fifth)).isEqualTo(429);
        assertThat(body(fifth).get("lockedUntil").asText()).isNotBlank();

        // Même le bon mot de passe est refusé pendant le blocage.
        var locked = perform(post("/api/v1/auth/login"), null,
                Map.of("login", DIRECTOR_LOGIN, "password", DIRECTOR_PASSWORD));
        assertThat(status(locked)).isEqualTo(429);
        assertThat(auditCount("LOGIN_LOCKED")).isEqualTo(1);
    }

    @Test
    void rafraichissementTournantEtDetectionDeReutilisation() throws Exception {
        var first = login(DIRECTOR_LOGIN, DIRECTOR_PASSWORD).get("refreshToken").asText();

        var rotated = perform(post("/api/v1/auth/refresh"), null, Map.of("refreshToken", first));
        assertThat(status(rotated)).isEqualTo(200);
        var second = body(rotated).get("refreshToken").asText();
        assertThat(second).isNotEqualTo(first);
        assertThat(status(getAs(body(rotated).get("accessToken").asText(), "/api/v1/auth/me"))).isEqualTo(200);

        // Réutiliser l'ancien jeton révoque toute la session, y compris le jeton le plus récent.
        var replay = perform(post("/api/v1/auth/refresh"), null, Map.of("refreshToken", first));
        assertThat(status(replay)).isEqualTo(401);
        assertThat(body(replay).get("code").asText()).isEqualTo("session_expired");
        assertThat(status(perform(post("/api/v1/auth/refresh"), null, Map.of("refreshToken", second))))
                .isEqualTo(401);
        assertThat(auditCount("REFRESH_TOKEN_REUSED")).isEqualTo(1);
    }

    @Test
    void deconnexionRevoqueLeJeton() throws Exception {
        var refresh = login(DIRECTOR_LOGIN, DIRECTOR_PASSWORD).get("refreshToken").asText();

        assertThat(status(perform(post("/api/v1/auth/logout"), null, Map.of("refreshToken", refresh))))
                .isEqualTo(204);

        assertThat(status(perform(post("/api/v1/auth/refresh"), null, Map.of("refreshToken", refresh))))
                .isEqualTo(401);
        assertThat(auditCount("LOGOUT")).isEqualTo(1);
    }

    @Test
    void jetonInvalideOuAbsentRefuse() throws Exception {
        assertThat(status(getAs(null, "/api/v1/auth/me"))).isEqualTo(401);
        var forged = getAs("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.signature", "/api/v1/auth/me");
        assertThat(status(forged)).isEqualTo(401);
        assertThat(body(forged).get("code").asText()).isEqualTo("unauthenticated");
    }

    @Test
    void connexionParPinSurLaTablette() throws Exception {
        var educatrice = createUser(directorToken, "EDUCATRICE", null, null, "4831");
        createUser(directorToken, "COMPTABILITE", "annie.dubois", "Comptes-2026!", null);

        assertThat(status(getAs(null, "/api/v1/auth/pin-users"))).isEqualTo(401);
        var device = pairDevice(directorToken, "Accueil — entrée");
        var pinUsers = body(getAs(device.token(), "/api/v1/auth/pin-users"));
        assertThat(pinUsers).hasSize(1);
        assertThat(pinUsers.get(0).get("displayName").asText()).isEqualTo("PrénomEDUCATRICE N.");
        assertThat(pinUsers.get(0).has("lastName")).isFalse();

        // Sans tablette appairée, la connexion par PIN est refusée.
        assertThat(status(perform(post("/api/v1/auth/pin"), null,
                Map.of("userId", educatrice.get("id").asText(), "pin", "4831")))).isEqualTo(401);
        assertThat(status(perform(post("/api/v1/auth/pin"), directorToken,
                Map.of("userId", educatrice.get("id").asText(), "pin", "4831")))).isEqualTo(403);

        var result = perform(post("/api/v1/auth/pin"), device.token(),
                Map.of("userId", educatrice.get("id").asText(), "pin", "4831"));
        assertThat(status(result)).isEqualTo(200);
        assertThat(body(result).get("sessionId").asText()).isNotBlank();
        assertThat(body(result).at("/user/authMethod").asText()).isEqualTo("PIN");
        assertThat(auditCount("PIN_LOGIN_SUCCEEDED")).isEqualTo(1);
    }

    @Test
    void pinBloqueApresCinqEchecs() throws Exception {
        var userId = createUser(directorToken, "CUISINE", null, null, "5208").get("id").asText();
        var tablet = pairDevice(directorToken, "Accueil — entrée").token();
        for (int i = 0; i < 4; i++) {
            assertThat(status(perform(post("/api/v1/auth/pin"), tablet, Map.of("userId", userId, "pin", "000" + i))))
                    .isEqualTo(401);
        }
        assertThat(status(perform(post("/api/v1/auth/pin"), tablet, Map.of("userId", userId, "pin", "0009"))))
                .isEqualTo(429);
        assertThat(status(perform(post("/api/v1/auth/pin"), tablet, Map.of("userId", userId, "pin", "5208"))))
                .isEqualTo(429);

        // La direction redéfinit le PIN : le blocage est levé.
        assertThat(status(perform(put("/api/v1/users/" + userId + "/pin"), directorToken, Map.of("pin", "7316"))))
                .isEqualTo(204);
        assertThat(status(perform(post("/api/v1/auth/pin"), tablet, Map.of("userId", userId, "pin", "7316"))))
                .isEqualTo(200);
    }

    @Test
    void pinInconnuRefuse() throws Exception {
        var tablet = pairDevice(directorToken, "Accueil — entrée").token();
        var result = perform(post("/api/v1/auth/pin"), tablet, Map.of("userId", UUID.randomUUID(), "pin", "4831"));
        assertThat(status(result)).isEqualTo(401);
    }

    @Test
    void compteDesactiveNePeutPlusSeConnecter() throws Exception {
        var user = createUser(directorToken, "COMPTABILITE", "annie.dubois", "Comptes-2026!", null);
        var refresh = login("annie.dubois", "Comptes-2026!").get("refreshToken").asText();

        var update = Map.of("firstName", "Annie", "lastName", "Dubois", "role", "COMPTABILITE",
                "login", "annie.dubois", "active", false);
        assertThat(status(perform(put("/api/v1/users/" + user.get("id").asText()), directorToken, update)))
                .isEqualTo(200);

        assertThat(status(perform(post("/api/v1/auth/login"), null,
                Map.of("login", "annie.dubois", "password", "Comptes-2026!")))).isEqualTo(401);
        assertThat(status(perform(post("/api/v1/auth/refresh"), null, Map.of("refreshToken", refresh))))
                .isEqualTo(401);
    }

    @Test
    void changerSonMotDePasse() throws Exception {
        var request = Map.of("currentPassword", DIRECTOR_PASSWORD, "newPassword", "Cagou-Lagon-2027");

        assertThat(status(perform(put("/api/v1/auth/me/password"), directorToken, request))).isEqualTo(204);

        assertThat(status(perform(post("/api/v1/auth/login"), null,
                Map.of("login", DIRECTOR_LOGIN, "password", "Cagou-Lagon-2027")))).isEqualTo(200);
        var wrongCurrent = perform(put("/api/v1/auth/me/password"), directorToken,
                Map.of("currentPassword", "faux", "newPassword", "Autre-Mot-2027"));
        assertThat(status(wrongCurrent)).isEqualTo(422);
    }
}
