package nc.ticagou.enrollment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import nc.ticagou.IntegrationTest;

class EnrollmentIT extends IntegrationTest {

    static final LocalDate TODAY = LocalDate.now(ZoneId.of("Pacific/Noumea"));

    String director;
    String moyens;

    @BeforeEach
    void installer() throws Exception {
        director = install();
        moyens = sectionId(director, "Moyens");
    }

    /** Demande pour un enfant de 18 mois à l'entrée souhaitée, dans deux mois. */
    Map<String, Object> form(String firstName, List<String> criteria) {
        var start = TODAY.plusMonths(2).withDayOfMonth(1);
        var form = new LinkedHashMap<String, Object>();
        form.put("childFirstName", firstName);
        form.put("childLastName", "Tein");
        form.put("birthDate", start.minusMonths(18).toString());
        form.put("sectionId", moyens);
        form.put("desiredStart", start.toString());
        form.put("careType", "REGULIER");
        form.put("arrivesAt", "07:30");
        form.put("leavesAt", "16:30");
        form.put("days", List.of(1, 2, 4));
        form.put("guardianFirstName", "Vaïana");
        form.put("guardianLastName", "Tein");
        form.put("relationship", "Mère");
        form.put("phone", "77.12.34");
        form.put("email", "vaiana.tein@exemple.nc");
        form.put("criteria", criteria);
        return form;
    }

    String create(Map<String, Object> form) throws Exception {
        var result = perform(post("/api/v1/enrollment-requests"), director, form);
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return body(result).get("id").asText();
    }

    JsonNode record(String id) throws Exception {
        return body(getAs(director, "/api/v1/enrollment-requests/" + id));
    }

    int stage(String id, Map<String, Object> change) throws Exception {
        return status(perform(put("/api/v1/enrollment-requests/" + id + "/stage"), director, change));
    }

    List<String> criteria() throws Exception {
        var ids = new ArrayList<String>();
        body(getAs(director, "/api/v1/enrollment-criteria")).forEach(c -> ids.add(c.get("id").asText()));
        return ids;
    }

    @Test
    void parcoursDUneDemande() throws Exception {
        var id = create(form("Hinano", List.of()));

        var board = body(getAs(director, "/api/v1/enrollment-requests"));
        assertThat(board.at("/stats/inProgress").asInt()).isEqualTo(1);
        var card = board.get("requests").get(0);
        assertThat(card.get("stage").asText()).isEqualTo("NOUVELLE");
        assertThat(card.at("/capacity/verdict").asText()).isEqualTo("ACCEPTABLE");

        assertThat(stage(id, Map.of("stage", "A_RECONTACTER", "followUpOn", TODAY.minusDays(5).toString()))).isEqualTo(204);
        assertThat(body(getAs(director, "/api/v1/enrollment-requests")).at("/stats/overdueFollowUps").asInt()).isEqualTo(1);

        assertThat(stage(id, Map.of("stage", "VISITE_PREVUE"))).as("visite sans date").isEqualTo(422);
        assertThat(stage(id, Map.of("stage", "VISITE_PREVUE", "visitAt", TODAY.plusDays(3) + "T09:30"))).isEqualTo(204);
        assertThat(status(perform(post("/api/v1/enrollment-requests/" + id + "/visit/done"), director, null))).isEqualTo(204);
        assertThat(status(perform(post("/api/v1/enrollment-requests/" + id + "/notes"), director,
                Map.of("body", "Préfère visiter un mercredi matin.")))).isEqualTo(201);
        assertThat(stage(id, Map.of("stage", "PLACE_PROPOSEE", "offerExpiresOn", TODAY.plusDays(7).toString()))).isEqualTo(204);

        var record = record(id);
        assertThat(record.get("stage").asText()).isEqualTo("PLACE_PROPOSEE");
        assertThat(record.get("visitDone").asBoolean()).isTrue();
        assertThat(record.get("notes")).hasSize(1);
        assertThat(record.get("notes").get(0).get("author").asText()).isEqualTo("Nathalie Kaloï");
        assertThat(record.get("history")).extracting(e -> e.get("label").asText())
                .contains("Demande reçue au bureau", "Visite faite")
                .anyMatch(l -> l.startsWith("Visite fixée au "))
                .anyMatch(l -> l.startsWith("Place proposée, réponse attendue avant le "));
        assertThat(auditCount("ENROLLMENT_STAGE_CHANGED")).isEqualTo(3);
    }

    @Test
    void contratSigneUniquementParLaSignature() throws Exception {
        var id = create(form("Hinano", List.of()));

        assertThat(stage(id, Map.of("stage", "SIGNEE"))).isEqualTo(422);
    }

    @Test
    void refusAvecMotifPuisReouverture() throws Exception {
        var id = create(form("Sacha", List.of()));

        assertThat(stage(id, Map.of("stage", "REFUSEE"))).isEqualTo(422);
        assertThat(stage(id, Map.of("stage", "REFUSEE", "reason", "Horaires hors amplitude d’ouverture"))).isEqualTo(204);
        assertThat(record(id).get("refusalReason").asText()).isEqualTo("Horaires hors amplitude d’ouverture");
        assertThat(body(getAs(director, "/api/v1/enrollment-requests")).at("/stats/inProgress").asInt()).isZero();

        assertThat(stage(id, Map.of("stage", "NOUVELLE"))).isEqualTo(204);
        var record = record(id);
        assertThat(record.get("refusalReason").isNull()).isTrue();
        assertThat(record.at("/history/0/label").asText()).isEqualTo("Demande rouverte, passée à « Nouvelle demande »");
    }

    @Test
    void scoreEtRangParmiLesDemandesDeLaSectionEtDuMois() throws Exception {
        var criteria = criteria();
        assertThat(criteria).hasSize(6);
        // Fratrie (3) + domicile (2) contre Parent seul (2)
        var first = create(form("Hinano", List.of(criteria.get(0), criteria.get(1))));
        var second = create(form("Mathis", List.of(criteria.get(3))));

        var record = record(second);
        assertThat(record.get("score").asInt()).isEqualTo(2);
        assertThat(record.at("/ranking/rank").asInt()).isEqualTo(2);
        assertThat(record.at("/ranking/of").asInt()).isEqualTo(2);
        assertThat(record(first).get("score").asInt()).isEqualTo(5);
        var card = body(getAs(director, "/api/v1/enrollment-requests?q=hinano")).get("requests");
        assertThat(card).hasSize(1);
        assertThat(card.get(0).get("tags")).extracting(JsonNode::asText)
                .containsExactly("Fratrie déjà accueillie", "Famille domiciliée dans la commune");
    }

    @Test
    void placeProposeeReserveLesJoursDemandes() throws Exception {
        var id = create(form("Raphaël", List.of()));
        var before = mondayFree(body(getAs(director, "/api/v1/capacity")));

        stage(id, Map.of("stage", "PLACE_PROPOSEE"));

        var after = mondayFree(body(getAs(director, "/api/v1/capacity")));
        assertThat(after[2]).isEqualTo(before[2] - 1);
        assertThat(after[0]).as("avant l'entrée souhaitée").isEqualTo(before[0]);
    }

    /** Places libres des Moyens le lundi, mois par mois. */
    int[] mondayFree(JsonNode capacity) {
        for (var section : capacity.get("sections")) {
            if (section.get("sectionId").asText().equals(moyens)) {
                var free = section.get("days").get(0).get("free");
                var result = new int[free.size()];
                for (int i = 0; i < free.size(); i++) {
                    result[i] = free.get(i).asInt();
                }
                return result;
            }
        }
        throw new IllegalStateException();
    }

    @Test
    void enfantANaitreEtControlesDeSaisie() throws Exception {
        var unborn = form("Tiaré", List.of());
        unborn.remove("birthDate");
        unborn.put("expectedBirthDate", TODAY.plusMonths(2).toString());
        create(unborn);

        var both = form("Tiaré", List.of());
        both.put("expectedBirthDate", TODAY.plusMonths(2).toString());
        assertThat(status(perform(post("/api/v1/enrollment-requests"), director, both))).isEqualTo(400);

        var unreachable = form("Tiaré", List.of());
        unreachable.put("phone", null);
        unreachable.put("email", null);
        assertThat(status(perform(post("/api/v1/enrollment-requests"), director, unreachable))).isEqualTo(400);
    }

    @Test
    void lErreurDeSaisieDitQuelChampCorrigerEtPourquoi() throws Exception {
        var future = form("Tiaré", List.of());
        future.put("birthDate", TODAY.plusDays(3).toString());
        var one = perform(post("/api/v1/enrollment-requests"), director, future);
        assertThat(status(one)).isEqualTo(400);
        assertThat(body(one).get("detail").asText()).isEqualTo("Date de naissance : ne peut pas être dans le futur.");
        assertThat(body(one).get("errors").get("birthDate").asText()).isEqualTo("Date de naissance : ne peut pas être dans le futur.");

        var several = form("", List.of());
        several.put("email", "pas-une-adresse");
        several.put("phone", null);
        var detail = body(perform(post("/api/v1/enrollment-requests"), director, several)).get("detail").asText();
        assertThat(detail).startsWith("Certaines informations sont à corriger : ")
                .contains("Prénom de l’enfant : à renseigner.").contains("E-mail : adresse e-mail invalide.");

        var unreadable = form("Tiaré", List.of());
        unreadable.put("desiredStart", "31/02/2027");
        var bad = perform(post("/api/v1/enrollment-requests"), director, unreadable);
        assertThat(status(bad)).isEqualTo(400);
        assertThat(body(bad).get("detail").asText()).isEqualTo("Date d’entrée souhaitée : valeur au mauvais format.");

        // Erreur produite par Spring (identifiant mal formé) : message en français, pas « Failed to convert… ».
        var malformed = getAs(director, "/api/v1/enrollment-requests/pas-un-identifiant");
        assertThat(status(malformed)).isEqualTo(400);
        assertThat(body(malformed).get("code").asText()).isEqualTo("bad_request");
        assertThat(body(malformed).get("detail").asText()).startsWith("La demande est incomplète ou mal formée");
    }

    @Test
    void droitsParRole() throws Exception {
        var id = create(form("Hinano", List.of()));
        createUser(director, "COMPTABILITE", "compta", "Mot-de-passe-compta", null);
        createUser(director, "EDUCATRICE", "educ", "Mot-de-passe-educ", null);
        createUser(director, "CUISINE", "cuisine", "Mot-de-passe-cuisine", null);
        var compta = accessToken("compta", "Mot-de-passe-compta");

        assertThat(status(getAs(compta, "/api/v1/enrollment-requests"))).isEqualTo(200);
        assertThat(status(getAs(compta, "/api/v1/enrollment-requests/" + id))).isEqualTo(200);
        assertThat(status(getAs(compta, "/api/v1/capacity"))).isEqualTo(200);
        assertThat(status(perform(post("/api/v1/enrollment-requests"), compta, form("Lucas", List.of())))).isEqualTo(403);
        assertThat(status(perform(put("/api/v1/enrollment-requests/" + id + "/stage"), compta,
                Map.of("stage", "A_RECONTACTER")))).isEqualTo(403);
        for (var login : List.of("educ", "cuisine")) {
            var token = accessToken(login, "Mot-de-passe-" + login);
            assertThat(status(getAs(token, "/api/v1/enrollment-requests"))).as(login).isEqualTo(403);
            assertThat(status(getAs(token, "/api/v1/capacity"))).as(login).isEqualTo(403);
        }
    }
}
