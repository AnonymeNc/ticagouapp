package nc.ticagou.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import nc.ticagou.IntegrationTest;

/**
 * Espace pro (lot 4) : demandes du personnel et réponse de la direction, compteurs, cahier d'équipe, relevés HACCP,
 * agenda partagé par lien privé.
 */
class StaffSpaceIT extends IntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Pacific/Noumea"));

    private String director;
    private String educatrice;
    private String educatriceId;

    @BeforeEach
    void setUp() throws Exception {
        director = install();
        educatriceId = createUser(director, "EDUCATRICE", "lina.wema", "Tamanou-2026!", null).get("id").asText();
        educatrice = accessToken("lina.wema", "Tamanou-2026!");
    }

    @Test
    void demandesDuPersonnelEtCompteurs() throws Exception {
        var monday = TODAY.plusWeeks(2).with(java.time.DayOfWeek.MONDAY);
        var leave = perform(post("/api/v1/staff-requests"), educatrice, Map.of("kind", "CONGES", "fromDay", monday.toString(),
                "toDay", monday.plusDays(2).toString(), "note", "Mariage de ma sœur"));
        assertThat(status(leave)).as(leave.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(body(perform(post("/api/v1/staff-requests"), educatrice, Map.of("kind", "HEURES_SUP",
                "fromDay", TODAY.plusDays(1).toString(), "toDay", TODAY.plusDays(1).toString(), "minutes", 60))).get("code").asText())
                .isEqualTo("overtime_future");
        var overtime = body(perform(post("/api/v1/staff-requests"), educatrice, Map.of("kind", "HEURES_SUP",
                "fromDay", TODAY.toString(), "toDay", TODAY.toString(), "minutes", 90, "note", "Réunion de parents")));
        assertThat(status(getAs(educatrice, "/api/v1/staff-requests"))).isEqualTo(403);

        assertThat(body(getAs(director, "/api/v1/staff-requests"))).hasSize(2);
        var answer = body(perform(post("/api/v1/staff-requests/" + body(leave).get("id").asText() + "/accept"), director, null));
        assertThat(answer.at("/request/status").asText()).isEqualTo("ACCEPTEE");
        assertThat(answer.get("shortageDays").isArray()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_absence WHERE user_id = ?::uuid AND kind = 'CONGES'",
                Long.class, educatriceId)).isEqualTo(1);
        perform(post("/api/v1/staff-requests/" + overtime.get("id").asText() + "/accept"), director, null);
        assertThat(body(perform(post("/api/v1/staff-requests/" + overtime.get("id").asText() + "/refuse"), director,
                Map.of("reason", "x"))).get("code").asText()).isEqualTo("request_handled");
        assertThat(auditCount("STAFF_REQUEST_ANSWERED")).isEqualTo(2);

        var counters = body(getAs(educatrice, "/api/v1/staff-counters"));
        assertThat(counters.get("overtimeMinutes").asInt()).isEqualTo(90);
        assertThat(counters.get("balanceMinutes").asInt()).isEqualTo(90);
        if (monday.getYear() == TODAY.getYear() && monday.plusDays(2).getYear() == TODAY.getYear()) {
            assertThat(counters.get("leaveDaysTaken").asDouble()).isEqualTo(3.0);
        }
        assertThat(body(getAs(educatrice, "/api/v1/staff-requests/mine"))).extracting(r -> r.get("status").asText())
                .containsOnly("ACCEPTEE");
        // Les compteurs des autres : la direction seule.
        assertThat(status(getAs(educatrice, "/api/v1/staff-counters?userId=" + java.util.UUID.randomUUID())))
                .isEqualTo(403);
    }

    @Test
    void cahierDEquipeAvecLecture() throws Exception {
        var note = body(perform(post("/api/v1/team-notes"), educatrice, Map.of("body", "Le portail du jardin ferme mal.")));
        assertThat(note.get("read").asBoolean()).isTrue();
        var seen = body(getAs(director, "/api/v1/team-notes"));
        assertThat(seen.at("/0/read").asBoolean()).isFalse();
        assertThat(seen.at("/0/authorName").asText()).isNotBlank();
        perform(post("/api/v1/team-notes/" + note.get("id").asText() + "/read"), director, null);
        assertThat(body(getAs(director, "/api/v1/team-notes")).at("/0/readers").asInt()).isEqualTo(2);
    }

    @Test
    void relevesDeTemperaturesHorsSeuilTraces() throws Exception {
        var point = body(perform(post("/api/v1/haccp/points"), director, Map.of("label", "Frigo de la cuisine", "kind", "FRIGO",
                "minTenths", 0, "maxTenths", 40)));
        var id = point.get("id").asText();
        assertThat(status(perform(post("/api/v1/haccp/readings"), educatrice, Map.of("pointId", id, "tenths", 35)))).isEqualTo(201);
        assertThat(body(perform(post("/api/v1/haccp/readings"), educatrice, Map.of("pointId", id, "tenths", 62))).get("code").asText())
                .isEqualTo("corrective_action");
        var out = body(perform(post("/api/v1/haccp/readings"), educatrice, Map.of("pointId", id, "tenths", 62,
                "action", "Porte mal fermée ; produits jetés")));
        assertThat(out.get("outOfRange").asBoolean()).isTrue();
        assertThat(auditCount("HACCP_OUT_OF_RANGE")).isEqualTo(1);
        var month = YearMonth.now(ZoneId.of("Pacific/Noumea"));
        var csv = getAs(educatrice, "/api/v1/haccp/readings/" + month + ".csv");
        var text = new String(csv.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(text).contains("\"Frigo de la cuisine\";3,5;Non").contains(";6,2;Oui;\"Porte mal fermée ; produits jetés\"");
        assertThat(status(perform(post("/api/v1/haccp/points"), educatrice, Map.of("label", "x", "kind", "FRIGO",
                "minTenths", 0, "maxTenths", 40)))).isEqualTo(403);
    }

    @Test
    void agendaParLienPrive() throws Exception {
        var token = body(perform(post("/api/v1/staff-calendar"), educatrice, null)).get("token").asText();
        var ics = perform(get("/api/v1/public/calendar/" + token + ".ics"), null, null);
        assertThat(status(ics)).isEqualTo(200);
        assertThat(ics.getResponse().getContentType()).startsWith("text/calendar");
        assertThat(ics.getResponse().getContentAsString()).startsWith("BEGIN:VCALENDAR").contains("END:VCALENDAR");
        assertThat(status(perform(get("/api/v1/public/calendar/inconnu.ics"), null, null))).isEqualTo(404);
        assertThat(status(perform(delete("/api/v1/staff-calendar"), educatrice, null))).isEqualTo(204);
        assertThat(status(perform(get("/api/v1/public/calendar/" + token + ".ics"), null, null))).isEqualTo(404);
    }
}
