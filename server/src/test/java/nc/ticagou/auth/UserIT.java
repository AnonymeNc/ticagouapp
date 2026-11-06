package nc.ticagou.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import nc.ticagou.IntegrationTest;

class UserIT extends IntegrationTest {

    String directorToken;

    @BeforeEach
    void installer() throws Exception {
        directorToken = install();
    }

    @Test
    void creeUnCompteTabletteSansMotDePasse() throws Exception {
        var user = createUser(directorToken, "EDUCATRICE", null, null, "4831");

        assertThat(user.get("hasPin").asBoolean()).isTrue();
        assertThat(user.get("hasPassword").asBoolean()).isFalse();
        assertThat(user.has("pinHash")).isFalse();
        assertThat(jdbc.queryForObject("SELECT pin_hash FROM app_user WHERE id = ?::uuid", String.class,
                user.get("id").asText())).startsWith("$argon2id$");
        assertThat(auditCount("USER_CREATED")).isEqualTo(1);
    }

    @Test
    void exigeUnMoyenDeConnexionEtUnIdentifiantPourLeMotDePasse() throws Exception {
        var none = perform(post("/api/v1/users"), directorToken,
                Map.of("firstName", "Paulo", "lastName", "Taufana", "role", "CUISINE"));
        assertThat(status(none)).isEqualTo(400);
        assertThat(body(none).at("/errors/authenticationProvided").asText()).contains("mot de passe");

        var noLogin = perform(post("/api/v1/users"), directorToken,
                Map.of("firstName", "Annie", "lastName", "Dubois", "role", "COMPTABILITE", "password", "Comptes-2026!"));
        assertThat(status(noLogin)).isEqualTo(400);
    }

    @Test
    void refuseLesPinFacilesEtLesIdentifiantsEnDouble() throws Exception {
        var weak = perform(post("/api/v1/users"), directorToken,
                Map.of("firstName", "Paulo", "lastName", "Taufana", "role", "CUISINE", "pin", "1234"));
        assertThat(status(weak)).isEqualTo(422);
        assertThat(body(weak).get("code").asText()).isEqualTo("weak_pin");

        var duplicate = perform(post("/api/v1/users"), directorToken, Map.of("firstName", "Autre", "lastName",
                "Directrice", "role", "DIRECTION", "login", DIRECTOR_LOGIN.toUpperCase(), "password", "Autre-2026-mdp"));
        assertThat(status(duplicate)).isEqualTo(409);
        assertThat(body(duplicate).get("code").asText()).isEqualTo("login_taken");
    }

    @Test
    void laStructureGardeAuMoinsUneDirection() throws Exception {
        var directorId = body(getAs(directorToken, "/api/v1/auth/me")).get("id").asText();
        var demote = new HashMap<String, Object>(Map.of("firstName", "Nathalie", "lastName", "Kaloï",
                "role", "EDUCATRICE", "login", DIRECTOR_LOGIN, "active", true));

        var self = perform(put("/api/v1/users/" + directorId), directorToken, demote);
        assertThat(status(self)).isEqualTo(422);
        assertThat(body(self).get("code").asText()).isEqualTo("self_demotion");

        // Une seconde directrice ne peut pas désactiver la dernière autre direction… sauf s'il en reste une.
        createUser(directorToken, "DIRECTION", "claire.haocas", "Direction-2026!", null);
        var secondToken = accessToken("claire.haocas", "Direction-2026!");
        assertThat(status(perform(put("/api/v1/users/" + directorId), secondToken, demote))).isEqualTo(200);
        var secondId = body(getAs(secondToken, "/api/v1/auth/me")).get("id").asText();
        var deactivateLast = Map.of("firstName", "Claire", "lastName", "Haocas", "role", "DIRECTION",
                "login", "claire.haocas", "active", false);
        var last = perform(put("/api/v1/users/" + secondId), secondToken, deactivateLast);
        assertThat(status(last)).isEqualTo(422);
    }

    @Test
    void redefinirLeMotDePasseFermeLesSessions() throws Exception {
        var user = createUser(directorToken, "COMPTABILITE", "annie.dubois", "Comptes-2026!", null);
        var refresh = login("annie.dubois", "Comptes-2026!").get("refreshToken").asText();

        assertThat(status(perform(put("/api/v1/users/" + user.get("id").asText() + "/password"), directorToken,
                Map.of("password", "Nouveau-Mdp-2026")))).isEqualTo(204);

        assertThat(status(perform(post("/api/v1/auth/refresh"), null, Map.of("refreshToken", refresh))))
                .isEqualTo(401);
        assertThat(status(perform(post("/api/v1/auth/login"), null,
                Map.of("login", "annie.dubois", "password", "Nouveau-Mdp-2026")))).isEqualTo(200);
        assertThat(auditCount("USER_PASSWORD_RESET")).isEqualTo(1);
    }
}
