package nc.ticagou.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.fasterxml.jackson.databind.JsonNode;

import nc.ticagou.IntegrationTest;

class ContractIT extends IntegrationTest {

    static final LocalDate TODAY = LocalDate.now(ZoneId.of("Pacific/Noumea"));
    /** Début du contrat : le premier lundi dans trois mois. */
    static final LocalDate START = TODAY.plusMonths(3).withDayOfMonth(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));

    String director;
    String moyens;

    @BeforeEach
    void installer() throws Exception {
        director = install();
        moyens = sectionId(director, "Moyens");
        createVolumeTariff(director, "2020-01-01");
    }

    static Map<String, Object> day(int day, String arrives, String leaves) {
        return Map.of("dayOfWeek", day, "arrivesAt", arrives, "leavesAt", leaves);
    }

    /** Planning de Maëlys : 39 h 30 par semaine. */
    static final List<Map<String, Object>> MAELYS = List.of(day(1, "07:30", "16:30"), day(2, "07:30", "16:30"),
            day(3, "07:30", "12:30"), day(4, "07:30", "16:30"), day(5, "07:30", "15:00"));

    static Map<String, Object> planning(List<Map<String, Object>> days) {
        var planning = new LinkedHashMap<String, Object>();
        planning.put("mode", "FIXE");
        planning.put("days", days);
        return planning;
    }

    static Map<String, Object> draft(LocalDate start, LocalDate end) {
        var draft = new LinkedHashMap<String, Object>();
        draft.put("startDate", start.toString());
        draft.put("endDate", end.toString());
        draft.put("noticeMonths", 1);
        draft.put("planning", planning(MAELYS));
        draft.put("adaptationBilling", "AU_TEMPS");
        var monday = start.minusWeeks(1);
        draft.put("adaptation", List.of(
                Map.of("date", monday.toString(), "startsAt", "09:00", "endsAt", "10:00", "note", "avec un parent"),
                Map.of("date", monday.plusDays(1).toString(), "startsAt", "09:00", "endsAt", "11:00")));
        return draft;
    }

    String requestFor(String firstName) throws Exception {
        var form = new LinkedHashMap<String, Object>();
        form.put("childFirstName", firstName);
        form.put("childLastName", "Garnier");
        form.put("birthDate", "2024-09-02");
        form.put("sectionId", moyens);
        form.put("desiredStart", START.toString());
        form.put("careType", "REGULIER");
        form.put("days", List.of(1, 2, 3, 4, 5));
        form.put("guardianFirstName", "Laura");
        form.put("guardianLastName", "Garnier");
        form.put("relationship", "Mère");
        form.put("phone", "77 12 34");
        form.put("criteria", List.of());
        return body(perform(post("/api/v1/enrollment-requests"), director, form)).get("id").asText();
    }

    /** Contrat depuis une demande : la fiche enfant et sa famille sont créées. */
    String contractFromRequest(String requestId) throws Exception {
        var child = new LinkedHashMap<String, Object>();
        child.put("firstName", "Maëlys");
        child.put("lastName", "Garnier");
        child.put("birthDate", "2024-09-02");
        child.put("sectionId", moyens);
        var guardian = new LinkedHashMap<String, Object>();
        guardian.put("firstName", "Laura");
        guardian.put("lastName", "Garnier");
        guardian.put("relationship", "Mère");
        guardian.put("legalGuardian", true);
        guardian.put("payer", true);
        guardian.put("mobilePhone", "77 12 34");
        var newChild = Map.of("child", child, "family", Map.of(
                "family", Map.of("label", "Famille Garnier", "city", "Nouméa"), "guardian", guardian));
        var result = perform(post("/api/v1/contracts"), director, Map.of(
                "requestId", requestId, "newChild", newChild, "contract", draft(START, START.plusYears(1).minusDays(1))));
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return body(result).get("id").asText();
    }

    JsonNode contract(String id) throws Exception {
        return body(getAs(director, "/api/v1/contracts/" + id));
    }

    int sign(String id, int version, byte[] scan) throws Exception {
        var request = multipart("/api/v1/contracts/" + id + "/versions/" + version + "/sign");
        request.param("signedOn", TODAY.toString());
        if (scan != null) {
            request.file(new MockMultipartFile("file", "contrat-signe.pdf", "application/pdf", scan));
        }
        return status(perform(request, director, null));
    }

    @Test
    void calculDuForfaitSansEnregistrer() throws Exception {
        var quote = perform(post("/api/v1/contracts/quote"), director, Map.of("effectiveFrom", START.toString(),
                "planning", planning(MAELYS), "adaptationBilling", "PREMIERE_HEURE_OFFERTE",
                "adaptation", List.of(Map.of("date", START.minusDays(7).toString(), "startsAt", "09:00", "endsAt", "11:00"))));

        assertThat(status(quote)).isEqualTo(200);
        var q = body(quote);
        assertThat(q.get("weeklyMinutes").asInt()).isEqualTo(2370);
        assertThat(q.get("monthlyMinutes").asInt()).isEqualTo(9282);
        assertThat(q.get("hourlyRate").asLong()).isEqualTo(510);
        assertThat(q.get("monthlyFee").asLong()).isEqualTo(78_901);
        assertThat(q.get("registrationFee").asLong()).isEqualTo(10_000);
        assertThat(q.get("adaptationCost").asLong()).as("1 h facturée à 620 F").isEqualTo(620);
        assertThat(q.get("problems")).isEmpty();
    }

    @Test
    void formuleDeFacturationALaPlaceDuCalculALHeure() throws Exception {
        var withFormula = planning(MAELYS);
        withFormula.put("formula", "Temps plein");
        withFormula.put("billingMode", "REEL");

        var q = body(perform(post("/api/v1/contracts/quote"), director, Map.of("effectiveFrom", START.toString(),
                "planning", withFormula)));

        assertThat(q.get("monthlyFee").asLong()).as("montant de la formule").isEqualTo(98_500);
        assertThat(q.get("hourlyRate").asLong()).as("tarif des heures complémentaires").isEqualTo(510);
        assertThat(q.get("formula").asText()).isEqualTo("Temps plein");
        assertThat(q.get("hourlyFee").asLong()).as("forfait à l’heure, pour comparer").isEqualTo(78_901);
        assertThat(q.get("billingMode").asText()).as("une formule est mensualisée").isEqualTo("MENSUALISE");
        assertThat(q.get("formulas")).extracting(f -> f.get("label").asText()).containsExactly("Temps plein", "Matins seulement");

        var draft = draft(START, START.plusYears(1).minusDays(1));
        var matins = planning(List.of(day(1, "07:30", "12:00"), day(2, "07:30", "12:00"), day(3, "07:30", "12:00"),
                day(4, "07:30", "12:00"), day(5, "07:30", "12:00")));
        matins.put("formula", "Matins seulement");
        draft.put("planning", matins);
        var created = perform(post("/api/v1/contracts"), director, Map.of("childId",
                createChild(director, "Noa", "Wahéo", "2024-07-22", moyens), "contract", draft));
        assertThat(status(created)).as(created.getResponse().getContentAsString()).isEqualTo(201);
        var version = contract(body(created).get("id").asText()).get("versions").get(0);
        assertThat(version.get("formula").asText()).isEqualTo("Matins seulement");
        assertThat(version.get("monthlyFee").asLong()).isEqualTo(60_000);
    }

    @Test
    void formuleInconnueOuPlanningVariableRefuses() throws Exception {
        var unknown = planning(MAELYS);
        unknown.put("formula", "Mercredis");
        var refused = perform(post("/api/v1/contracts/quote"), director, Map.of("effectiveFrom", START.toString(), "planning", unknown));
        assertThat(status(refused)).isEqualTo(422);
        assertThat(body(refused).get("code").asText()).isEqualTo("formula_unknown");

        var variable = new LinkedHashMap<String, Object>();
        variable.put("mode", "VARIABLE");
        variable.put("days", List.of());
        variable.put("monthlyReservedMinutes", 80 * 60);
        variable.put("formula", "Temps plein");
        assertThat(status(perform(post("/api/v1/contracts/quote"), director,
                Map.of("effectiveFrom", START.toString(), "planning", variable)))).isEqualTo(400);
    }

    @Test
    void problemesDePlanningSignales() throws Exception {
        var quote = body(perform(post("/api/v1/contracts/quote"), director, Map.of("effectiveFrom", START.toString(),
                "planning", planning(List.of(day(1, "06:00", "16:30"), day(6, "08:00", "12:00"))))));

        assertThat(quote.get("problems")).extracting(JsonNode::asText).containsExactly(
                "Lundi : la structure est ouverte de 06:30 à 17:30.", "Samedi : la structure est fermée ce jour-là.");
    }

    @Test
    void contratDepuisUneDemandeJusquALaSignature() throws Exception {
        var requestId = requestFor("Maëlys");
        var id = contractFromRequest(requestId);

        var draft = contract(id);
        assertThat(draft.get("number").asText()).isEqualTo(START.getYear() + "-001");
        assertThat(draft.get("state").asText()).isEqualTo("BROUILLON");
        assertThat(draft.at("/versions/0/status").asText()).isEqualTo("BROUILLON");
        assertThat(draft.at("/versions/0/monthlyFee").asLong()).isEqualTo(78_901);
        assertThat(draft.get("adaptationCost").asLong()).as("3 h à 620 F").isEqualTo(1_860);
        var childId = draft.get("childId").asText();
        var child = body(getAs(director, "/api/v1/children/" + childId));
        assertThat(child.get("entryDate").asText()).isEqualTo(START.toString());
        assertThat(child.at("/family/label").asText()).isEqualTo("Famille Garnier");
        var request = body(getAs(director, "/api/v1/enrollment-requests/" + requestId));
        assertThat(request.get("childId").asText()).isEqualTo(childId);
        assertThat(request.at("/contracts/0/number").asText()).isEqualTo(draft.get("number").asText());

        var pdf = getAs(director, "/api/v1/contracts/" + id + "/versions/0/pdf");
        assertThat(status(pdf)).isEqualTo(200);
        assertThat(new String(pdf.getResponse().getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        assertThat(sign(id, 0, "%PDF-1.7 signé".getBytes(StandardCharsets.UTF_8))).isEqualTo(204);

        var signed = contract(id);
        assertThat(signed.get("state").asText()).isEqualTo("A_VENIR");
        assertThat(signed.at("/versions/0/status").asText()).isEqualTo("SIGNE");
        assertThat(signed.at("/versions/0/inForce").asBoolean()).isTrue();
        assertThat(body(getAs(director, "/api/v1/enrollment-requests/" + requestId)).get("stage").asText()).isEqualTo("SIGNEE");
        var documents = body(getAs(director, "/api/v1/children/" + childId)).get("documents");
        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).get("category").asText()).isEqualTo("CONTRAT");
        assertThat(status(perform(delete("/api/v1/documents/" + documents.get(0).get("id").asText()), director, null)))
                .as("l'exemplaire signé reste au dossier").isEqualTo(409);
        assertThat(auditCount("CONTRACT_SIGNED")).isEqualTo(1);
    }

    @Test
    void versionSigneeImmuable() throws Exception {
        var id = contractFromRequest(requestFor("Maëlys"));
        sign(id, 0, null);

        var update = perform(put("/api/v1/contracts/" + id), director, draft(START, START.plusYears(1)));
        assertThat(status(update)).isEqualTo(422);
        assertThat(body(update).get("code").asText()).isEqualTo("contract_signed");
        assertThat(sign(id, 0, null)).isEqualTo(409);
        assertThatThrownBy(() -> jdbc.update("UPDATE contract_version SET monthly_fee = 1 WHERE contract_id = ?::uuid", id))
                .hasMessageContaining("signée");
    }

    @Test
    void avenantRecalculeLeForfaitAPartirDeSaDateDEffet() throws Exception {
        var id = contractFromRequest(requestFor("Maëlys"));
        var wednesdayFullDay = List.of(day(1, "07:30", "16:30"), day(2, "07:30", "16:30"), day(3, "07:30", "16:30"),
                day(4, "07:30", "16:30"), day(5, "07:30", "15:00"));
        var amendment = Map.of("effectiveFrom", START.plusMonths(3).toString(), "reason", "Mercredi à la journée",
                "planning", planning(wednesdayFullDay));

        assertThat(status(perform(post("/api/v1/contracts/" + id + "/versions"), director, amendment)))
                .as("contrat initial pas encore signé").isEqualTo(422);
        sign(id, 0, null);
        var tooEarly = perform(post("/api/v1/contracts/" + id + "/versions"), director,
                Map.of("effectiveFrom", START.toString(), "reason", "Trop tôt", "planning", planning(wednesdayFullDay)));
        assertThat(body(tooEarly).get("code").asText()).isEqualTo("amendment_date");

        var created = perform(post("/api/v1/contracts/" + id + "/versions"), director, amendment);
        assertThat(status(created)).isEqualTo(201);
        assertThat(body(created).asInt()).isEqualTo(1);
        assertThat(body(perform(post("/api/v1/contracts/" + id + "/versions"), director, amendment)).get("code").asText())
                .isEqualTo("draft_pending");

        var versions = contract(id).get("versions");
        assertThat(versions).hasSize(2);
        assertThat(versions.get(1).get("monthlyFee").asLong()).isEqualTo(86_891);
        assertThat(versions.get(1).get("weeklyMinutes").asInt()).isEqualTo(43 * 60 + 30);
        assertThat(versions.get(1).get("registrationFee").asLong()).isZero();
        assertThat(versions.get(0).get("monthlyFee").asLong()).as("la version signée ne change pas").isEqualTo(78_901);

        assertThat(status(getAs(director, "/api/v1/contracts/" + id + "/versions/1/pdf"))).isEqualTo(200);
        assertThat(sign(id, 1, null)).isEqualTo(204);
        assertThat(auditCount("CONTRACT_AMENDMENT_CREATED")).isEqualTo(1);
    }

    @Test
    void brouillonDAvenantSupprimable() throws Exception {
        var id = contractFromRequest(requestFor("Maëlys"));
        sign(id, 0, null);
        perform(post("/api/v1/contracts/" + id + "/versions"), director, Map.of("effectiveFrom",
                START.plusMonths(1).toString(), "reason", "Essai", "planning", planning(MAELYS)));

        assertThat(status(perform(delete("/api/v1/contracts/" + id + "/versions/1"), director, null))).isEqualTo(204);
        assertThat(status(perform(delete("/api/v1/contracts/" + id + "/versions/0"), director, null))).isEqualTo(422);
        assertThat(contract(id).get("versions")).hasSize(1);
    }

    @Test
    void contratSigneOccupeUnePlace() throws Exception {
        var id = contractFromRequest(requestFor("Maëlys"));
        var before = body(getAs(director, "/api/v1/capacity"));

        sign(id, 0, null);

        var after = body(getAs(director, "/api/v1/capacity"));
        int month = before.get("months").size() - 1;
        // Née le 02/09/2024 : chez les Grands (30 mois) au dernier mois de la période, selon la date du jour.
        long changed = 0;
        for (int s = 0; s < before.get("sections").size(); s++) {
            var b = before.get("sections").get(s).get("days").get(0).get("free").get(month).asInt();
            var a = after.get("sections").get(s).get("days").get(0).get("free").get(month).asInt();
            changed += b - a;
        }
        assertThat(changed).as("une place de moins le lundi au dernier mois").isEqualTo(1);
    }

    @Test
    void deuxContratsDuMemeEnfantNeSeChevauchentPas() throws Exception {
        var id = contractFromRequest(requestFor("Maëlys"));
        var childId = contract(id).get("childId").asText();

        var overlap = perform(post("/api/v1/contracts"), director,
                Map.of("childId", childId, "contract", draft(START.plusMonths(6), START.plusMonths(18))));

        assertThat(status(overlap)).isEqualTo(422);
        assertThat(body(overlap).get("code").asText()).isEqualTo("contract_overlap");
    }

    @Test
    void purgeDeLaFamilleSupprimeContratsEtDemandes() throws Exception {
        var noa = createChild(director, "Noa", "Wahéo", "2024-07-22", moyens);
        var past = TODAY.minusMonths(10).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        var id = body(perform(post("/api/v1/contracts"), director,
                Map.of("childId", noa, "contract", draft(past, TODAY.minusMonths(1))))).get("id").asText();
        sign(id, 0, "%PDF-1.7".getBytes(StandardCharsets.UTF_8));
        var child = body(getAs(director, "/api/v1/children/" + noa));
        perform(put("/api/v1/children/" + noa), director, Map.of("firstName", "Noa", "lastName", "Wahéo",
                "birthDate", "2024-07-22", "sectionId", moyens, "entryDate", "2025-02-01",
                "exitDate", TODAY.minusMonths(1).toString()));
        var familyId = child.at("/family/id").asText();

        var export = getAs(director, "/api/v1/families/" + familyId + "/export");
        assertThat(status(export)).isEqualTo(200);

        var purge = perform(post("/api/v1/families/" + familyId + "/purge"), director, Map.of("confirmation", "Famille Wahéo"));
        assertThat(status(purge)).as(purge.getResponse().getContentAsString()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM contract", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM contract_version", Long.class)).isZero();
    }

    @Test
    void droitsParRole() throws Exception {
        var id = contractFromRequest(requestFor("Maëlys"));
        createUser(director, "COMPTABILITE", "compta", "Mot-de-passe-compta", null);
        createUser(director, "EDUCATRICE", "educ", "Mot-de-passe-educ", null);
        var compta = accessToken("compta", "Mot-de-passe-compta");
        var educ = accessToken("educ", "Mot-de-passe-educ");

        assertThat(status(getAs(compta, "/api/v1/contracts/" + id))).isEqualTo(200);
        assertThat(status(getAs(compta, "/api/v1/contracts/" + id + "/versions/0/pdf"))).isEqualTo(200);
        assertThat(status(perform(put("/api/v1/contracts/" + id), compta, draft(START, START.plusYears(1))))).isEqualTo(403);
        assertThat(status(getAs(educ, "/api/v1/contracts/" + id))).isEqualTo(403);
    }
}
