package nc.ticagou.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import nc.ticagou.IntegrationTest;

class SetupIT extends IntegrationTest {

    @Test
    void installeLaStructureLesSectionsEtLaDirection() throws Exception {
        assertThat(body(getAs(null, "/api/v1/setup/status")).get("initialized").asBoolean()).isFalse();

        var result = perform(post("/api/v1/setup"), null, setupRequest());

        assertThat(status(result)).isEqualTo(201);
        var session = body(result);
        assertThat(session.get("accessToken").asText()).isNotBlank();
        assertThat(session.get("refreshToken").asText()).isNotBlank();
        assertThat(session.at("/user/role").asText()).isEqualTo("DIRECTION");
        var status = body(getAs(null, "/api/v1/setup/status"));
        assertThat(status.get("initialized").asBoolean()).isTrue();
        assertThat(status.get("structureName").asText()).isEqualTo("Les P’tits Niaoulis");

        var token = session.get("accessToken").asText();
        var structure = body(getAs(token, "/api/v1/structure"));
        assertThat(structure.get("name").asText()).isEqualTo("Les P’tits Niaoulis");
        assertThat(structure.get("ridet").asText()).isEqualTo("1234567.001");
        assertThat(structure.get("openingHours")).hasSize(5);
        assertThat(body(getAs(token, "/api/v1/sections"))).extracting(s -> s.get("name").asText())
                .containsExactly("Bébés", "Moyens", "Grands");
        assertThat(auditCount("SETUP_COMPLETED")).isEqualTo(1);
    }

    @Test
    void uneSeuleInstallationPossible() throws Exception {
        install();

        var second = perform(post("/api/v1/setup"), null, setupRequest());

        assertThat(status(second)).isEqualTo(409);
        assertThat(body(second).get("code").asText()).isEqualTo("already_initialized");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM structure", Integer.class)).isEqualTo(1);
    }

    @Test
    void refuseLInstallationDepuisUnAutrePoste() throws Exception {
        var request = post("/api/v1/setup").with(r -> {
            r.setRemoteAddr("192.168.1.20");
            return r;
        });

        var result = perform(request, null, setupRequest());

        assertThat(status(result)).isEqualTo(403);
        assertThat(body(result).get("code").asText()).isEqualTo("setup_token");
        assertThat(body(getAs(null, "/api/v1/setup/status")).get("initialized").asBoolean()).isFalse();
    }

    @Test
    void sectionsIncoherentesAnnulentToutLInstallation() throws Exception {
        var request = new HashMap<>(setupRequest());
        request.put("sections", List.of(
                section("Bébés", 2, 20, 12, "#9e2f66", 0),
                section("Moyens", 18, 30, 16, "#924510", 1)));

        var result = perform(post("/api/v1/setup"), null, request);

        assertThat(status(result)).isEqualTo(422);
        assertThat(body(result).get("code").asText()).isEqualTo("section_overlap");
        assertThat(body(getAs(null, "/api/v1/setup/status")).get("initialized").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM structure", Integer.class)).isZero();
    }

    @Test
    void valideLesChampsEnFrancais() throws Exception {
        var request = new HashMap<>(setupRequest());
        request.put("structure", Map.of("name", "", "ridet", "12-34", "capacity", 0));

        var result = perform(post("/api/v1/setup"), null, request);

        assertThat(status(result)).isEqualTo(400);
        var errors = body(result).get("errors");
        assertThat(errors.get("structure.ridet").asText()).startsWith("Le RIDET comporte 7 chiffres");
        assertThat(errors.has("structure.name")).isTrue();
        assertThat(errors.has("structure.capacity")).isTrue();
    }

    @Test
    void identifiantTropCourtRefuse() throws Exception {
        var request = new HashMap<>(setupRequest());
        request.put("admin", Map.of("firstName", "Nathalie", "lastName", "Kaloï", "login", "nk",
                "password", DIRECTOR_PASSWORD));

        var result = perform(post("/api/v1/setup"), null, request);

        assertThat(status(result)).isEqualTo(400);
        assertThat(body(result).at("/errors/admin.login").asText()).contains("3 à 60 caractères");
    }

    @Test
    void motDePasseTropFaibleRefuseSansRienCreer() throws Exception {
        var request = new HashMap<>(setupRequest());
        request.put("admin", Map.of("firstName", "Nathalie", "lastName", "Kaloï", "login", DIRECTOR_LOGIN,
                "password", "abc"));

        var result = perform(post("/api/v1/setup"), null, request);

        assertThat(status(result)).isEqualTo(422);
        assertThat(body(result).get("code").asText()).isEqualTo("weak_password");
        assertThat(body(getAs(null, "/api/v1/setup/status")).get("initialized").asBoolean()).isFalse();
    }
}
