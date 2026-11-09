package nc.ticagou.enrollment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import nc.ticagou.IntegrationTest;

/**
 * Pré-inscription en ligne (lot 3, ADR 0020) : formulaire public fermé par défaut, questions de la direction, anti-abus
 * silencieux, demande marquée « En ligne » dans le tableau, effacement des demandes sans suite après 12 mois.
 */
class OnlineEnrollmentIT extends IntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(java.time.ZoneId.of("Pacific/Noumea"));

    @Autowired
    private OnlineEnrollmentService online;

    private String director;

    @BeforeEach
    void installer() throws Exception {
        director = install();
        online.resetThrottle();
    }

    private Map<String, Object> submission(String firstName, Map<String, String> answers, boolean consent, String website,
                                           long startedAt) {
        var start = TODAY.plusMonths(2).withDayOfMonth(1);
        var form = new LinkedHashMap<String, Object>();
        form.put("childFirstName", firstName);
        form.put("childLastName", "Tein");
        form.put("birthDate", start.minusMonths(18).toString());
        form.put("desiredStart", start.toString());
        form.put("careType", "REGULIER");
        form.put("arrivesAt", "07:30");
        form.put("leavesAt", "16:30");
        form.put("days", List.of(1, 2, 4));
        form.put("guardianFirstName", "Vaïana");
        form.put("guardianLastName", "Tein");
        form.put("relationship", "Mère");
        form.put("phone", "77.12.34");
        form.put("criteria", List.of());
        var body = new LinkedHashMap<String, Object>();
        body.put("request", form);
        body.put("answers", answers);
        body.put("consent", consent);
        body.put("website", website);
        body.put("startedAt", startedAt);
        return body;
    }

    private long online() {
        return jdbc.queryForObject("SELECT count(*) FROM enrollment_request WHERE source = 'EN_LIGNE'", Long.class);
    }

    @Test
    void formulairePublicDeLaCreche() throws Exception {
        var ago = Instant.now().minusSeconds(30).toEpochMilli();
        // Fermé par défaut : rien n'entre.
        assertThat(body(perform(get("/api/v1/public/preinscription"), null, null)).get("enabled").asBoolean()).isFalse();
        assertThat(body(perform(post("/api/v1/public/preinscription"), null, submission("Lou", Map.of(), true, "", ago)))
                .get("code").asText()).isEqualTo("online_closed");

        perform(put("/api/v1/enrollment/online"), director, Map.of("enabled", true, "intro", "Bienvenue aux P’tits Niaoulis."));
        var choice = body(perform(post("/api/v1/enrollment/questions"), director, Map.of("label", "Comment nous avez-vous connus ?",
                "kind", "CHOIX", "options", List.of("Bouche-à-oreille", "Internet"), "required", true, "displayOrder", 1)));
        var text = body(perform(post("/api/v1/enrollment/questions"), director, Map.of("label", "Frères et sœurs à la crèche ?",
                "kind", "TEXTE", "required", false, "displayOrder", 2)));
        var form = body(perform(get("/api/v1/public/preinscription"), null, null));
        assertThat(form.get("intro").asText()).isEqualTo("Bienvenue aux P’tits Niaoulis.");
        assertThat(form.get("questions")).hasSize(2);
        assertThat(form.at("/questions/0/options/1").asText()).isEqualTo("Internet");

        var choiceId = choice.get("id").asText();
        assertThat(body(perform(post("/api/v1/public/preinscription"), null, submission("Lou", Map.of(choiceId, "Internet"), false, "", ago)))
                .get("code").asText()).isEqualTo("consent_required");
        assertThat(body(perform(post("/api/v1/public/preinscription"), null, submission("Lou", Map.of(), true, "", ago)))
                .get("code").asText()).isEqualTo("answer_required");
        assertThat(body(perform(post("/api/v1/public/preinscription"), null, submission("Lou", Map.of(choiceId, "Télé"), true, "", ago)))
                .get("code").asText()).isEqualTo("answer_invalid");

        // Robots : même réponse, rien n'entre.
        assertThat(status(perform(post("/api/v1/public/preinscription"), null,
                submission("Bot", Map.of(choiceId, "Internet"), true, "https://spam.example", ago)))).isEqualTo(202);
        assertThat(status(perform(post("/api/v1/public/preinscription"), null,
                submission("Bot", Map.of(choiceId, "Internet"), true, "", Instant.now().toEpochMilli())))).isEqualTo(202);
        assertThat(online()).isZero();

        var sent = perform(post("/api/v1/public/preinscription"), null,
                submission("Lou", Map.of(choiceId, "Internet", text.get("id").asText(), "Son grand frère Tom"), true, "", ago));
        assertThat(status(sent)).as(sent.getResponse().getContentAsString()).isEqualTo(202);
        assertThat(online()).isEqualTo(1);

        var board = body(getAs(director, "/api/v1/enrollment-requests"));
        var card = board.get("requests").get(0);
        assertThat(card.get("childFirstName").asText()).isEqualTo("Lou");
        assertThat(card.get("tags").get(0).asText()).isEqualTo("En ligne");
        var record = body(getAs(director, "/api/v1/enrollment-requests/" + card.get("id").asText()));
        assertThat(record.get("source").asText()).isEqualTo("EN_LIGNE");
        assertThat(record.get("consentAt").isNull()).isFalse();
        assertThat(record.get("answers")).extracting(a -> a.get("value").asText()).containsExactly("Internet", "Son grand frère Tom");
        assertThat(auditCount("ENROLLMENT_REQUEST_CREATED")).isEqualTo(1);

        // Question retirée : plus posée ; les réponses reçues restent.
        assertThat(status(perform(delete("/api/v1/enrollment/questions/" + choiceId), director, null))).isEqualTo(204);
        assertThat(body(perform(get("/api/v1/public/preinscription"), null, null)).get("questions")).hasSize(1);
        assertThat(body(getAs(director, "/api/v1/enrollment-requests/" + card.get("id").asText())).get("answers")).hasSize(2);
    }

    @Test
    void demandesSansSuiteEffaceesApres12Mois() throws Exception {
        perform(put("/api/v1/enrollment/online"), director, Map.of("enabled", true));
        var ago = Instant.now().minusSeconds(30).toEpochMilli();
        perform(post("/api/v1/public/preinscription"), null, submission("Ancienne", Map.of(), true, "", ago));
        perform(post("/api/v1/public/preinscription"), null, submission("Récente", Map.of(), true, "", ago));
        jdbc.update("UPDATE enrollment_request SET stage_changed_on = stage_changed_on - INTERVAL '13 months', "
                + "received_on = received_on - INTERVAL '13 months' WHERE child_first_name = 'Ancienne'");
        assertThat(online.purge()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT child_first_name FROM enrollment_request", String.class)).containsExactly("Récente");
        assertThat(auditCount("ENROLLMENT_REQUESTS_PURGED")).isEqualTo(1);
    }
}
