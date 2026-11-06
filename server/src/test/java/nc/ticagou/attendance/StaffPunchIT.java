package nc.ticagou.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import nc.ticagou.IntegrationTest;

/** Pointage du personnel par son seul code sur une tablette appairée. */
class StaffPunchIT extends IntegrationTest {

    private String director;
    private Device tablet;
    private String moyens;

    @BeforeEach
    void setUp() throws Exception {
        director = install();
        tablet = pairDevice(director, "Accueil — entrée");
        moyens = sectionId(director, "Moyens");
    }

    private String createStaff(String firstName, String pin, String homeSectionId) throws Exception {
        var request = new LinkedHashMap<String, Object>();
        request.put("firstName", firstName);
        request.put("lastName", "Kaouma");
        request.put("role", "EDUCATRICE");
        request.put("pin", pin);
        request.put("jobTitle", "Éducatrice de jeunes enfants");
        request.put("homeSectionId", homeSectionId);
        var result = perform(post("/api/v1/users"), director, request);
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(201);
        var user = body(result);
        assertThat(user.get("countsInRatio").asBoolean()).isTrue();
        return user.get("id").asText();
    }

    private MvcResult punch(String pin, String sectionId) throws Exception {
        var request = new LinkedHashMap<String, Object>();
        request.put("clientId", UUID.randomUUID().toString());
        request.put("pin", pin);
        request.put("sectionId", sectionId);
        return perform(post("/api/v1/kiosk/staff-punch"), tablet.token(), request);
    }

    @Test
    void leCodeSeulBasculeEntreArriveeEtDepart() throws Exception {
        var leaId = createStaff("Léa", "4831", moyens);
        var arrival = punch("4831", null);
        assertThat(status(arrival)).isEqualTo(200);
        assertThat(body(arrival).get("userId").asText()).isEqualTo(leaId);
        assertThat(body(arrival).get("action").asText()).isEqualTo("ARRIVEE");
        assertThat(body(arrival).get("sectionId").asText()).isEqualTo(moyens);

        var staffing = body(getAs(director, "/api/v1/staffing"));
        assertThat(staffing.at("/global/adults").asInt()).isEqualTo(1);

        var departure = punch("4831", null);
        assertThat(body(departure).get("action").asText()).isEqualTo("DEPART");
        assertThat(body(getAs(director, "/api/v1/staffing")).at("/global/adults").asInt()).isZero();
        assertThat(auditCount("STAFF_ARRIVAL")).isEqualTo(1);
        assertThat(auditCount("STAFF_DEPARTURE")).isEqualTo(1);
    }

    @Test
    void memeEnvoiRepeteNeBasculePasDeuxFois() throws Exception {
        createStaff("Léa", "4831", moyens);
        var request = Map.of("clientId", UUID.randomUUID().toString(), "pin", "4831");
        assertThat(body(perform(post("/api/v1/kiosk/staff-punch"), tablet.token(), request)).get("action").asText())
                .isEqualTo("ARRIVEE");
        assertThat(body(perform(post("/api/v1/kiosk/staff-punch"), tablet.token(), request)).get("action").asText())
                .isEqualTo("ARRIVEE");
        assertThat(body(getAs(director, "/api/v1/attendance")).get("staff")).hasSize(1);
    }

    @Test
    void sansSectionHabituelleLaTabletteDemandeLaSection() throws Exception {
        createStaff("Sandrine", "4831", null);
        var result = punch("4831", null);
        assertThat(status(result)).isEqualTo(422);
        assertThat(body(result).get("code").asText()).isEqualTo("section_required");
        assertThat(body(punch("4831", moyens)).get("sectionId").asText()).isEqualTo(moyens);
    }

    @Test
    void cinqCodesInconnusBloquentLePointage() throws Exception {
        createStaff("Léa", "4831", moyens);
        for (int i = 0; i < 4; i++) {
            assertThat(status(punch("139" + i, null))).isEqualTo(401);
        }
        assertThat(status(punch("1394", null))).isEqualTo(429);
        assertThat(status(punch("4831", null))).isEqualTo(429);
        assertThat(auditCount("STAFF_PIN_FAILED")).isEqualTo(5);
    }

    @Test
    void codeDejaUtiliseRefuseALaSaisie() throws Exception {
        var leaId = createStaff("Léa", "4831", moyens);
        var duplicate = new LinkedHashMap<String, Object>();
        duplicate.put("firstName", "Tania");
        duplicate.put("lastName", "Wéa");
        duplicate.put("role", "EDUCATRICE");
        duplicate.put("pin", "4831");
        var created = perform(post("/api/v1/users"), director, duplicate);
        assertThat(status(created)).isEqualTo(409);
        assertThat(body(created).get("code").asText()).isEqualTo("pin_taken");

        var taniaId = createStaff("Tania", "7316", null);
        assertThat(status(perform(put("/api/v1/users/" + taniaId + "/pin"), director, Map.of("pin", "4831"))))
                .isEqualTo(409);
        // Le même code redéfini pour la même personne reste possible.
        assertThat(status(perform(put("/api/v1/users/" + leaId + "/pin"), director, Map.of("pin", "4831"))))
                .isEqualTo(204);
    }

    @Test
    void codePartageParDeuxPersonnesAmbigu() throws Exception {
        var leaId = createStaff("Léa", "4831", moyens);
        var taniaId = createStaff("Tania", "7316", moyens);
        // Doublon hérité d'avant le contrôle d'unicité.
        jdbc.update("UPDATE app_user SET pin_hash = (SELECT pin_hash FROM app_user WHERE id = ?::uuid) WHERE id = ?::uuid",
                leaId, taniaId);
        var result = punch("4831", null);
        assertThat(status(result)).isEqualTo(409);
        assertThat(body(result).get("code").asText()).isEqualTo("pin_ambiguous");
    }

    @Test
    void reserveAuxTablettes() throws Exception {
        createStaff("Léa", "4831", moyens);
        assertThat(status(perform(post("/api/v1/kiosk/staff-punch"), director,
                Map.of("clientId", UUID.randomUUID().toString(), "pin", "4831")))).isEqualTo(403);
        assertThat(status(perform(post("/api/v1/kiosk/staff-punch"), null,
                Map.of("clientId", UUID.randomUUID().toString(), "pin", "4831")))).isEqualTo(401);
    }
}
