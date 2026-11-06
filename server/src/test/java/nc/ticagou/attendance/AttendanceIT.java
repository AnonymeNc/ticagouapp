package nc.ticagou.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import nc.ticagou.IntegrationTest;

/** Pointage : règle du départ, validation de la direction, corrections et droits par rôle. */
class AttendanceIT extends IntegrationTest {

    private String director;
    private String directorId;
    private String educatriceId;
    private String childId;
    private Device tablet;
    private JsonNode session;

    @BeforeEach
    void setUp() throws Exception {
        director = install();
        directorId = body(getAs(director, "/api/v1/auth/me")).get("id").asText();
        perform(put("/api/v1/users/" + directorId + "/pin"), director, Map.of("pin", "2580"));
        educatriceId = createUser(director, "EDUCATRICE", null, null, "4831").get("id").asText();
        childId = createChild(director, "Noa", "Wahéo", "2025-01-15", sectionId(director, "Moyens"));
        perform(post("/api/v1/children/" + childId + "/authorized-persons"), director,
                Map.of("firstName", "Odile", "lastName", "Wahéo", "relationship", "Grand-mère", "idVerified", true));
        tablet = pairDevice(director, "Accueil — entrée");
        session = pinLogin(tablet, educatriceId, "4831");
    }

    /** Veille à midi (Nouméa) : une correction de quelques minutes reste le même jour, même juste après minuit. */
    private static final LocalDate DAY = LocalDate.now(ZoneId.of("Pacific/Noumea")).minusDays(1);
    private static final Instant BASE = DAY.atTime(12, 0).atZone(ZoneId.of("Pacific/Noumea")).toInstant();

    private Map<String, Object> op(String type) {
        var op = new LinkedHashMap<String, Object>();
        op.put("clientId", UUID.randomUUID().toString());
        op.put("type", type);
        op.put("occurredAt", BASE.toString());
        op.put("userId", educatriceId);
        op.put("sessionId", session.get("sessionId").asText());
        op.put("childId", childId);
        return op;
    }

    private JsonNode send(Map<String, Object>... ops) throws Exception {
        var result = perform(post("/api/v1/kiosk/operations"), tablet.token(), Map.of("operations", List.of(ops)));
        assertThat(status(result)).isEqualTo(200);
        return body(result).get("results");
    }

    private JsonNode today() throws Exception {
        return body(getAs(director, "/api/v1/attendance?date=" + DAY));
    }

    private String arrive() throws Exception {
        var arrival = op("ARRIVEE");
        arrival.put("attendanceId", arrival.get("clientId"));
        assertThat(send(arrival).get(0).get("outcome").asText()).isEqualTo("APPLIQUEE");
        return (String) arrival.get("clientId");
    }

    private JsonNode kioskChild() throws Exception {
        for (var child : body(getAs(tablet.token(), "/api/v1/kiosk/today")).get("children")) {
            if (child.get("id").asText().equals(childId)) {
                return child;
            }
        }
        throw new AssertionError("enfant absent de l'instantané");
    }

    @Test
    void departAvecUnePersonneDeLaListe() throws Exception {
        var attendanceId = arrive();
        var pickup = kioskChild().get("pickup");
        assertThat(pickup).hasSize(2);
        var grandMother = pickup.get(1);
        assertThat(grandMother.get("kind").asText()).isEqualTo("AUTORISEE");

        var departure = op("DEPART");
        departure.put("attendanceId", attendanceId);
        departure.put("pickup", Map.of("kind", "AUTORISEE", "personId", grandMother.get("id").asText()));
        assertThat(send(departure).get(0).get("outcome").asText()).isEqualTo("APPLIQUEE");

        var row = today().get("children").get(0);
        assertThat(row.get("pickedUpByName").asText()).isEqualTo("Odile Wahéo");
        assertThat(row.get("departedAt").isNull()).isFalse();
        assertThat(auditCount("CHILD_DEPARTURE")).isEqualTo(1);
        assertThat(childAccessCount(childId, "POINTAGE")).isEqualTo(1);
    }

    @Test
    void departNonAutoriseRefuse() throws Exception {
        var attendanceId = arrive();
        var departure = op("DEPART");
        departure.put("attendanceId", attendanceId);
        departure.put("pickup", Map.of("kind", "AUTRE"));
        var result = send(departure).get(0);
        assertThat(result.get("outcome").asText()).isEqualTo("REFUSEE");
        assertThat(result.get("code").asText()).isEqualTo("pickup_not_authorized");

        // Un identifiant de personne inventé ne passe pas non plus.
        var forged = op("DEPART");
        forged.put("attendanceId", attendanceId);
        forged.put("pickup", Map.of("kind", "AUTORISEE", "personId", UUID.randomUUID().toString()));
        assertThat(send(forged).get(0).get("code").asText()).isEqualTo("pickup_not_authorized");

        assertThat(today().get("children").get(0).get("departedAt").isNull()).isTrue();
    }

    @Test
    void validationParLaDirectriceTracee() throws Exception {
        var attendanceId = arrive();
        var departure = op("DEPART");
        departure.put("attendanceId", attendanceId);
        departure.put("pickup", Map.of("kind", "AUTRE"));
        departure.put("override", Map.of("personName", "Marc Tein", "relationship", "Voisin, envoyé par la mère",
                "idChecked", true, "directorId", directorId, "directorPin", "2580"));
        var result = send(departure).get(0);
        assertThat(result.get("outcome").asText()).isEqualTo("APPLIQUEE");
        assertThat(result.get("needsReview").asBoolean()).isFalse();

        var row = today().get("children").get(0);
        assertThat(row.get("pickedUpByKind").asText()).isEqualTo("AUTRE");
        assertThat(row.at("/override/validatedBy").asText()).isEqualTo("Nathalie Kaloï");
        assertThat(row.at("/override/pinVerified").asBoolean()).isTrue();
        assertThat(auditCount("CHILD_DEPARTURE_OVERRIDE")).isEqualTo(1);
    }

    @Test
    void codeDEducatriceOuCodeFauxRefuses() throws Exception {
        var attendanceId = arrive();
        var byEducatrice = op("DEPART");
        byEducatrice.put("attendanceId", attendanceId);
        byEducatrice.put("pickup", Map.of("kind", "AUTRE"));
        byEducatrice.put("override", Map.of("personName", "Marc Tein", "relationship", "Voisin", "idChecked", true,
                "directorId", educatriceId, "directorPin", "4831"));
        assertThat(send(byEducatrice).get(0).get("code").asText()).isEqualTo("validator_unknown");

        var wrongPin = op("DEPART");
        wrongPin.put("attendanceId", attendanceId);
        wrongPin.put("pickup", Map.of("kind", "AUTRE"));
        wrongPin.put("override", Map.of("personName", "Marc Tein", "relationship", "Voisin", "idChecked", true,
                "directorId", directorId, "directorPin", "1397"));
        assertThat(send(wrongPin).get(0).get("code").asText()).isEqualTo("bad_director_pin");
        assertThat(today().get("children").get(0).get("departedAt").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT failed_pin_attempts FROM app_user WHERE id = ?::uuid", Integer.class,
                directorId)).isEqualTo(1);
    }

    @Test
    void departAuBureauParLaDirection() throws Exception {
        var attendanceId = arrive();
        var educatrice = accessTokenFor(educatriceId);
        var other = Map.of("personName", "Marc Tein", "relationship", "Voisin", "idChecked", true,
                "reason", "Appel de la mère à 16:40");
        // L'éducatrice au bureau ne peut pas valider seule une personne hors liste.
        var refused = perform(post("/api/v1/attendance/children/records/" + attendanceId + "/departure"), educatrice,
                Map.of("other", other));
        assertThat(status(refused)).isEqualTo(422);
        assertThat(body(refused).get("code").asText()).isEqualTo("pickup_not_authorized");

        var accepted = perform(post("/api/v1/attendance/children/records/" + attendanceId + "/departure"), director,
                Map.of("other", other));
        assertThat(status(accepted)).isEqualTo(204);
        assertThat(today().get("children").get(0).at("/override/reason").asText()).isEqualTo("Appel de la mère à 16:40");
    }

    @Test
    void correctionAvecMotifEtJournalEnAjoutSeul() throws Exception {
        var attendanceId = arrive();
        var row = today().get("children").get(0);
        var arrivedAt = Instant.parse(row.get("arrivedAt").asText());
        var corrected = arrivedAt.minus(10, ChronoUnit.MINUTES);

        var noReason = perform(put("/api/v1/attendance/children/records/" + attendanceId), director,
                Map.of("arrivedAt", corrected.toString()));
        assertThat(status(noReason)).isEqualTo(400);

        var educatrice = accessTokenFor(educatriceId);
        assertThat(status(perform(put("/api/v1/attendance/children/records/" + attendanceId), educatrice,
                Map.of("arrivedAt", corrected.toString(), "reason", "Oubli")))).isEqualTo(403);

        var request = new LinkedHashMap<String, Object>();
        request.put("arrivedAt", corrected.toString());
        request.put("sectionId", row.get("sectionId").asText());
        request.put("reason", "Arrivée pointée en retard par la tablette");
        assertThat(status(perform(put("/api/v1/attendance/children/records/" + attendanceId), director, request)))
                .isEqualTo(204);

        var history = body(getAs(director, "/api/v1/attendance/children/" + attendanceId + "/corrections"));
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("field").asText()).isEqualTo("arrivee");
        assertThat(history.get(0).get("reason").asText()).isEqualTo("Arrivée pointée en retard par la tablette");
        assertThat(auditCount("ATTENDANCE_CORRECTED")).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE attendance_correction SET reason = 'x'"))
                .hasMessageContaining("ajout seul");
    }

    @Test
    void droitsParRole() throws Exception {
        var cuisine = createUser(director, "CUISINE", "paulo.taufana", "Cuisine-2026!", null);
        var compta = createUser(director, "COMPTABILITE", "annie.dubois", "Comptes-2026!", null);
        assertThat(cuisine).isNotNull();
        assertThat(compta).isNotNull();
        var cook = accessToken("paulo.taufana", "Cuisine-2026!");
        var accountant = accessToken("annie.dubois", "Comptes-2026!");

        assertThat(status(getAs(cook, "/api/v1/attendance"))).isEqualTo(200);
        assertThat(status(getAs(accountant, "/api/v1/attendance"))).isEqualTo(200);
        assertThat(status(perform(post("/api/v1/attendance/children/" + childId + "/arrival"), cook, Map.of())))
                .isEqualTo(403);
        assertThat(status(perform(post("/api/v1/attendance/children/" + childId + "/arrival"), accountant, Map.of())))
                .isEqualTo(403);
        assertThat(status(getAs(accountant, "/api/v1/attendance/review"))).isEqualTo(403);
        // Une tablette ne lit pas les présences du bureau, un utilisateur n'envoie pas de file d'opérations.
        assertThat(status(getAs(tablet.token(), "/api/v1/attendance"))).isEqualTo(403);
        assertThat(status(perform(post("/api/v1/kiosk/operations"), director, Map.of("operations", List.of()))))
                .isEqualTo(403);

        var arrival = perform(post("/api/v1/attendance/children/" + childId + "/arrival"), director,
                Map.of("droppedOffBy", "Mère"));
        assertThat(status(arrival)).isEqualTo(201);
        var desk = body(getAs(director, "/api/v1/attendance")).get("children").get(0);
        assertThat(desk.get("droppedOffBy").asText()).isEqualTo("Mère");
        assertThat(desk.get("source").asText()).isEqualTo("BUREAU");
    }

    /** Jeton d'une session PIN déjà ouverte sur la tablette, pour appeler l'API du bureau. */
    private String accessTokenFor(String userId) throws Exception {
        if (userId.equals(educatriceId)) {
            return session.get("accessToken").asText();
        }
        throw new IllegalArgumentException(userId);
    }
}
