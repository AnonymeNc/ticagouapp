package nc.ticagou.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class AbsenceIT extends BillingFixtures {

    String reason(String label) throws Exception {
        for (var reason : body(getAs(director, "/api/v1/billing/absence-reasons"))) {
            if (reason.get("label").asText().equals(label)) {
                return reason.get("id").asText();
            }
        }
        throw new AssertionError(label);
    }

    Map<String, Object> absence(LocalDate from, LocalDate to, String reasonId, LocalDate notifiedOn) {
        var request = new LinkedHashMap<String, Object>();
        request.put("fromDay", from.toString());
        request.put("toDay", to.toString());
        request.put("reasonId", reasonId);
        request.put("notifiedOn", notifiedOn.toString());
        request.put("note", "Signalée par téléphone");
        return request;
    }

    MvcResult signal(String childId, Map<String, Object> request) throws Exception {
        return perform(post("/api/v1/children/" + childId + "/absences"), director, request);
    }

    @Test
    void absenceDeductibleDeduiteDeLaFacture() throws Exception {
        var evicted = signal(lea, absence(TUESDAY, TUESDAY.plusDays(1), reason("Éviction décidée par la direction"),
                TUESDAY));
        assertThat(status(evicted)).as(evicted.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(body(evicted).get("deductible").asBoolean()).isTrue();
        assertThat(body(evicted).get("invoiced").asBoolean()).isFalse();

        var row = row(preview(), "Famille Wamytan");
        assertThat(row.get("absences").asLong()).as("2 jours de 9 h à 510 F").isEqualTo(2 * 9 * 510);
        assertThat(row.get("familyTotal").asLong()).isEqualTo(monthlyFee(lea) - 2 * 9 * 510);

        var list = body(getAs(director, "/api/v1/absences?from=" + MONTH.atDay(1) + "&to=" + MONTH.atEndOfMonth()));
        assertThat(list).hasSize(1);
        assertThat(list.at("/0/childName").asText()).isEqualTo("Léa Wamytan");
        assertThat(auditCount("ABSENCE_RECORDED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT details::text FROM audit_event WHERE action = 'ABSENCE_RECORDED'", String.class))
                .as("pas de motif d'absence dans le journal").doesNotContain("Éviction").contains("reasonId");
    }

    @Test
    void justificatifManquantBloqueLaFamille() throws Exception {
        signal(tom, absence(TUESDAY, TUESDAY, reason("Hospitalisation de l’enfant"), TUESDAY));
        var preview = preview();
        assertThat(row(preview, "Famille Poédi").get("status").asText()).isEqualTo("ANOMALIE");
        assertThat(preview.at("/anomalies/0/type").asText()).isEqualTo("JUSTIFICATIF_MANQUANT");
    }

    @Test
    void absenceNonDeduiteEtControles() throws Exception {
        var other = reason("Autre absence (non déduite)");
        assertThat(status(signal(lea, absence(TUESDAY, TUESDAY, other, TUESDAY)))).isEqualTo(201);
        assertThat(row(preview(), "Famille Wamytan").get("absences").asLong()).isZero();

        var overlap = signal(lea, absence(TUESDAY, TUESDAY.plusDays(2), other, TUESDAY));
        assertThat(body(overlap).get("code").asText()).isEqualTo("absence_overlap");
        var future = signal(lea, absence(TUESDAY.plusDays(7), TUESDAY.plusDays(7), other, TODAY.plusDays(1)));
        assertThat(body(future).get("code").asText()).isEqualTo("absence_notified_future");
        var reversed = signal(lea, absence(TUESDAY.plusDays(7), TUESDAY.plusDays(6), other, TUESDAY));
        assertThat(status(reversed)).isEqualTo(400);
    }

    @Test
    void uneAbsenceDUnMoisFactureNeChangePlus() throws Exception {
        var created = body(signal(lea, absence(TUESDAY, TUESDAY, reason("Autre absence (non déduite)"), TUESDAY)));
        generated();
        var id = created.get("id").asText();
        assertThat(body(getAs(director, "/api/v1/children/" + lea + "/absences")).at("/0/invoiced").asBoolean()).isTrue();
        var update = perform(put("/api/v1/absences/" + id), director,
                absence(TUESDAY, TUESDAY, reason("Éviction décidée par la direction"), TUESDAY));
        assertThat(status(update)).isEqualTo(422);
        assertThat(body(update).get("code").asText()).isEqualTo("absence_month_invoiced");
        assertThat(status(perform(delete("/api/v1/absences/" + id), director, null))).isEqualTo(422);
        var late = signal(lea, absence(TUESDAY.plusDays(1), TUESDAY.plusDays(1), reason("Autre absence (non déduite)"),
                TUESDAY));
        assertThat(body(late).get("code").asText()).isEqualTo("absence_month_invoiced");
    }

    @Test
    void lEducatriceSignaleLaComptabiliteConsulte() throws Exception {
        createUser(director, "EDUCATRICE", "educ", "Educatrice-2026!", "4822");
        createUser(director, "COMPTABILITE", "compta", "Comptable-2026!", "4821");
        var educ = accessToken("educ", "Educatrice-2026!");
        var compta = accessToken("compta", "Comptable-2026!");
        var request = absence(TUESDAY, TUESDAY, reason("Autre absence (non déduite)"), TUESDAY);
        assertThat(status(perform(post("/api/v1/children/" + lea + "/absences"), educ, request))).isEqualTo(201);
        assertThat(status(getAs(compta, "/api/v1/children/" + lea + "/absences"))).isEqualTo(200);
        assertThat(status(perform(post("/api/v1/children/" + tom + "/absences"), compta, request))).isEqualTo(403);
    }
}
