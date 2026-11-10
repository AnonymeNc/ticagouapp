package nc.ticagou.staffing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import nc.ticagou.IntegrationTest;

/** Taux d'encadrement en temps réel : règles datées, dépassements tracés, flux SSE. */
class StaffingIT extends IntegrationTest {

    private String director;
    private String bebes;

    @BeforeEach
    void setUp() throws Exception {
        director = install();
        bebes = sectionId(director, "Bébés");
    }

    private String staff(String firstName, String pin) throws Exception {
        var request = new LinkedHashMap<String, Object>();
        request.put("firstName", firstName);
        request.put("lastName", "Tehei");
        request.put("role", "EDUCATRICE");
        request.put("pin", pin);
        request.put("homeSectionId", bebes);
        return body(perform(post("/api/v1/users"), director, request)).get("id").asText();
    }

    private void babiesArrive(int count) throws Exception {
        var birth = LocalDate.now().minusMonths(9);
        for (int i = 0; i < count; i++) {
            var id = createChild(director, "Bébé" + i, "Famille" + i, birth.toString(), bebes,
                    birth.plusMonths(3).toString());
            assertThat(status(perform(post("/api/v1/attendance/children/" + id + "/arrival"), director, Map.of())))
                    .isEqualTo(201);
        }
    }

    private void punch(Device tablet, String pin) throws Exception {
        var result = perform(post("/api/v1/kiosk/staff-punch"), tablet.token(),
                Map.of("clientId", UUID.randomUUID().toString(), "pin", pin));
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
    }

    /** Les dépassements sont tenus par la diffusion, sur son propre fil : on attend qu'elle ait fini. */
    private long openBreaches() {
        staffingBroadcaster.awaitPending();
        return jdbc.queryForObject("SELECT count(*) FROM ratio_breach WHERE ended_at IS NULL", Long.class);
    }

    @Test
    void reglesParDefautCreeesALInstallation() throws Exception {
        var rules = body(getAs(director, "/api/v1/ratio-rules"));
        assertThat(rules).hasSize(2);
        assertThat(rules.findValuesAsText("label")).containsExactly("Enfants qui ne marchent pas", "Enfants qui marchent");
        assertThat(rules.findValuesAsText("state")).containsOnly("EN_VIGUEUR");
        assertThat(body(getAs(director, "/api/v1/staffing")).get("rules").get(0).asText())
                .isEqualTo("1 adulte pour 5 enfants qui ne marchent pas");
    }

    @Test
    void regleQuiChevaucheRefuseeEtRegleAVenirAcceptee() throws Exception {
        var walkers = body(getAs(director, "/api/v1/ratio-rules")).get(1);
        var overlapping = new LinkedHashMap<String, Object>();
        overlapping.put("label", "Enfants qui marchent");
        overlapping.put("minAgeMonths", 18);
        overlapping.put("childrenPerAdult", 6);
        overlapping.put("validFrom", "2027-01-01");
        var refused = perform(post("/api/v1/ratio-rules"), director, overlapping);
        assertThat(status(refused)).isEqualTo(422);
        assertThat(body(refused).get("code").asText()).isEqualTo("ratio_rule_overlap");

        var closed = new LinkedHashMap<String, Object>();
        closed.put("label", "Enfants qui marchent");
        closed.put("minAgeMonths", 18);
        closed.put("childrenPerAdult", 8);
        closed.put("validFrom", walkers.get("validFrom").asText());
        closed.put("validTo", "2026-12-31");
        assertThat(status(perform(put("/api/v1/ratio-rules/" + walkers.get("id").asText()), director, closed)))
                .isEqualTo(200);
        var future = perform(post("/api/v1/ratio-rules"), director, overlapping);
        assertThat(status(future)).isEqualTo(201);
        assertThat(body(future).get("state").asText()).isEqualTo(
                LocalDate.now().isBefore(LocalDate.of(2027, 1, 1)) ? "A_VENIR" : "EN_VIGUEUR");
        assertThat(auditCount("RATIO_RULE_CREATED")).isEqualTo(1);

        var educatrice = createUser(director, "EDUCATRICE", "malia.t", "Moyens-2026!", null);
        assertThat(educatrice).isNotNull();
        assertThat(status(getAs(accessToken("malia.t", "Moyens-2026!"), "/api/v1/ratio-rules"))).isEqualTo(403);
    }

    @Test
    void depassementOuvertPuisFermeQuandUnAdulteArrive() throws Exception {
        var tablet = pairDevice(director, "Salle des Bébés");
        staff("Mereana", "4831");
        staff("Sylvie", "7316");
        punch(tablet, "4831");
        babiesArrive(6);

        var staffing = body(getAs(director, "/api/v1/staffing"));
        var section = staffing.get("sections").get(0);
        assertThat(section.get("name").asText()).isEqualTo("Bébés");
        assertThat(section.at("/load/status").asText()).isEqualTo("INSUFFISANT");
        assertThat(section.at("/load/required").asInt()).isEqualTo(2);
        assertThat(section.at("/staff/0/displayName").asText()).isEqualTo("Mereana T.");
        assertThat(openBreaches()).isEqualTo(2);
        assertThat(auditCount("RATIO_BREACH_STARTED")).isEqualTo(2);

        punch(tablet, "7316");
        var after = body(getAs(director, "/api/v1/staffing")).get("sections").get(0);
        assertThat(after.at("/load/status").asText()).isEqualTo("CONFORME");
        assertThat(after.at("/load/coveredPlaces").asInt()).isEqualTo(10);
        assertThat(openBreaches()).isZero();
        staffingBroadcaster.awaitPending();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ratio_breach WHERE ended_at IS NOT NULL", Long.class))
                .isEqualTo(2);
    }

    @Test
    void fluxSseRecoitLEncadrementApresUneArrivee() throws Exception {
        var stream = mvc.perform(get("/api/v1/staffing/stream").header(HttpHeaders.AUTHORIZATION, "Bearer " + director))
                .andExpect(request().asyncStarted())
                .andReturn();
        assertThat(stream.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("event:ratio");

        babiesArrive(1);
        staffingBroadcaster.awaitPending();

        var content = stream.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(content).contains("event:attendance");
        assertThat(content).contains("event:alert");
        assertThat(content).contains("\"status\":\"INSUFFISANT\"");
    }

    @Test
    void pointagesSimultanesSansBlocage() throws Exception {
        // Quinze arrivées à la fois (une tablette qui vide sa file hors connexion) : plus que de connexions à la base.
        // Avant la diffusion sur son propre fil, chacune attendait une deuxième connexion et tout restait bloqué 30 s.
        var birth = LocalDate.now().minusMonths(9);
        var ids = new java.util.ArrayList<String>();
        for (int i = 0; i < 15; i++) {
            ids.add(createChild(director, "Bébé" + i, "Famille" + i, birth.toString(), bebes, birth.plusMonths(3).toString()));
        }
        var pool = java.util.concurrent.Executors.newFixedThreadPool(ids.size());
        try {
            var started = System.nanoTime();
            var calls = ids.stream().map(id -> pool.submit(() ->
                    status(perform(post("/api/v1/attendance/children/" + id + "/arrival"), director, Map.of())))).toList();
            for (var call : calls) {
                assertThat(call.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(201);
            }
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(15));
        } finally {
            pool.shutdownNow();
        }
        staffingBroadcaster.awaitPending();
        assertThat(body(getAs(director, "/api/v1/staffing")).get("sections").get(0).at("/load/children").asInt()).isEqualTo(15);
    }

    @Test
    void reglesReserveesALaDirection() throws Exception {
        var rule = body(getAs(director, "/api/v1/ratio-rules")).get(0).get("id").asText();
        assertThat(status(perform(delete("/api/v1/ratio-rules/" + rule), director, null))).isEqualTo(204);
        assertThat(body(getAs(director, "/api/v1/ratio-rules"))).hasSize(1);
        assertThat(body(perform(put("/api/v1/ratio-rules/settings"), director, Map.of("alertDesktop", false)))
                .get("alertDesktop").asBoolean()).isFalse();
        assertThat(body(getAs(director, "/api/v1/staffing")).get("alertDesktop").asBoolean()).isFalse();
    }
}
