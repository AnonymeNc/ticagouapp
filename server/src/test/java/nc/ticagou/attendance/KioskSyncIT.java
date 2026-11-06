package nc.ticagou.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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

/** Resynchronisation de la file d'attente des tablettes : idempotence, ordre, conflits, codes vérifiés après coup. */
class KioskSyncIT extends IntegrationTest {

    /** Veille à midi (Nouméa) : les pointages « il y a quelques heures » restent le même jour, même juste après minuit. */
    private static final LocalDate DAY = LocalDate.now(ZoneId.of("Pacific/Noumea")).minusDays(1);
    private static final Instant NOW = DAY.atTime(12, 0).atZone(ZoneId.of("Pacific/Noumea")).toInstant();

    private String director;
    private String educatriceId;
    private String childId;
    private Device tablet;
    private String sessionId;

    @BeforeEach
    void setUp() throws Exception {
        director = install();
        educatriceId = createUser(director, "EDUCATRICE", null, null, "4831").get("id").asText();
        childId = createChild(director, "Kenzo", "Lefèvre", "2025-01-15", sectionId(director, "Moyens"));
        tablet = pairDevice(director, "Accueil — entrée");
        sessionId = pinLogin(tablet, educatriceId, "4831").get("sessionId").asText();
    }

    private Map<String, Object> op(String type, Instant at) {
        var op = new LinkedHashMap<String, Object>();
        op.put("clientId", UUID.randomUUID().toString());
        op.put("type", type);
        op.put("occurredAt", at.toString());
        op.put("userId", educatriceId);
        op.put("sessionId", sessionId);
        op.put("childId", childId);
        return op;
    }

    private Map<String, Object> arrival(Instant at) {
        var op = op("ARRIVEE", at);
        op.put("attendanceId", op.get("clientId"));
        return op;
    }

    @SafeVarargs
    private JsonNode send(Map<String, Object>... ops) throws Exception {
        var result = perform(post("/api/v1/kiosk/operations"), tablet.token(), Map.of("operations", List.of(ops)));
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return body(result).get("results");
    }

    private JsonNode children() throws Exception {
        return body(getAs(director, "/api/v1/attendance?date=" + DAY)).get("children");
    }

    @Test
    void lotEnvoyeDeuxFoisSansDoublon() throws Exception {
        var arrival = arrival(NOW.minus(20, ChronoUnit.MINUTES));
        var instructions = op("CONSIGNES", NOW.minus(19, ChronoUnit.MINUTES));
        instructions.put("attendanceId", arrival.get("clientId"));
        instructions.put("droppedOffBy", "Mère");
        instructions.put("instructions", List.of(Map.of("id", UUID.randomUUID().toString(), "kind", "MEDICAMENT",
                "medicationAt", "14:00", "medicationDose", "Paracétamol sirop, dose de l’ordonnance",
                "prescriptionGiven", true)));

        var first = send(arrival, instructions);
        assertThat(first.get(0).get("outcome").asText()).isEqualTo("APPLIQUEE");
        assertThat(first.get(1).get("outcome").asText()).isEqualTo("APPLIQUEE");
        var again = send(arrival, instructions);
        assertThat(again.get(0).get("outcome").asText()).isEqualTo("DEJA_RECUE");
        assertThat(again.get(1).get("outcome").asText()).isEqualTo("DEJA_RECUE");

        var rows = children();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("instructions")).hasSize(1);
        assertThat(rows.get(0).at("/instructions/0/medicationAt").asText()).isEqualTo("14:00:00");
        assertThat(rows.get(0).get("droppedOffBy").asText()).isEqualTo("Mère");
    }

    @Test
    void ordreEtHeureDeLaTabletteRespectes() throws Exception {
        var arrivedAt = NOW.minus(3, ChronoUnit.HOURS);
        var departedAt = NOW.minus(1, ChronoUnit.HOURS);
        var arrival = arrival(arrivedAt);
        var parent = body(getAs(tablet.token(), "/api/v1/kiosk/today")).at("/children/0/pickup/0");
        var departure = op("DEPART", departedAt);
        departure.put("attendanceId", arrival.get("clientId"));
        departure.put("offline", true);
        departure.put("pickup", Map.of("kind", "RESPONSABLE", "personId", parent.get("id").asText()));

        var results = send(arrival, departure);
        assertThat(results.findValuesAsText("outcome")).containsExactly("APPLIQUEE", "APPLIQUEE");
        var row = children().get(0);
        assertThat(Instant.parse(row.get("arrivedAt").asText())).isEqualTo(arrivedAt);
        assertThat(Instant.parse(row.get("departedAt").asText())).isEqualTo(departedAt);
        assertThat(row.get("pickedUpByName").asText()).isEqualTo("Sarah Lefèvre");
    }

    @Test
    void arriveeEnDoubleDepuisDeuxTablettes() throws Exception {
        var otherTablet = pairDevice(director, "Salle des Moyens");
        var fromOther = arrival(NOW.minus(30, ChronoUnit.MINUTES));
        var result = perform(post("/api/v1/kiosk/operations"), otherTablet.token(),
                Map.of("operations", List.of(fromOther)));
        assertThat(body(result).at("/results/0/outcome").asText()).isEqualTo("APPLIQUEE");

        var mine = arrival(NOW.minus(25, ChronoUnit.MINUTES));
        var instructions = op("CONSIGNES", NOW.minus(24, ChronoUnit.MINUTES));
        instructions.put("attendanceId", mine.get("clientId"));
        instructions.put("instructions", List.of(Map.of("id", UUID.randomUUID().toString(), "kind", "A_MAL_DORMI")));
        var results = send(mine, instructions);
        assertThat(results.get(0).get("outcome").asText()).isEqualTo("DEJA_RECUE");
        assertThat(results.get(0).get("code").asText()).isEqualTo("already_present");
        // Les consignes rejoignent le passage déjà ouvert par l'autre tablette.
        assertThat(results.get(1).get("outcome").asText()).isEqualTo("APPLIQUEE");
        var rows = children();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("id").asText()).isEqualTo(fromOther.get("clientId"));
        assertThat(rows.get(0).at("/instructions/0/kind").asText()).isEqualTo("A_MAL_DORMI");
    }

    @Test
    void departSansArriveeRefuseEtListePourLaDirection() throws Exception {
        var departure = op("DEPART", NOW.minus(5, ChronoUnit.MINUTES));
        departure.put("pickup", Map.of("kind", "AUTRE"));
        var result = send(departure).get(0);
        assertThat(result.get("outcome").asText()).isEqualTo("REFUSEE");
        assertThat(result.get("code").asText()).isEqualTo("not_present");

        var review = body(getAs(director, "/api/v1/attendance/review"));
        assertThat(review.get("refused")).hasSize(1);
        assertThat(review.at("/refused/0/type").asText()).isEqualTo("DEPART");
    }

    @Test
    void connexionHorsLigneAvecUnCodeFauxGardeLePointage() throws Exception {
        var login = new LinkedHashMap<String, Object>();
        login.put("clientId", UUID.randomUUID().toString());
        login.put("type", "CONNEXION_HORS_LIGNE");
        login.put("occurredAt", NOW.minus(40, ChronoUnit.MINUTES).toString());
        login.put("offline", true);
        login.put("userId", educatriceId);
        login.put("pin", "1397");
        var arrival = arrival(NOW.minus(39, ChronoUnit.MINUTES));
        arrival.remove("sessionId");
        arrival.put("offlineSessionId", login.get("clientId"));
        arrival.put("offline", true);

        var results = send(login, arrival);
        assertThat(results.get(0).get("outcome").asText()).isEqualTo("REFUSEE");
        assertThat(results.get(1).get("outcome").asText()).isEqualTo("APPLIQUEE");
        assertThat(results.get(1).get("needsReview").asBoolean()).isTrue();

        var row = children().get(0);
        assertThat(row.get("needsReview").asBoolean()).isTrue();
        assertThat(row.get("reviewReason").asText()).contains("code incorrect");
        assertThat(body(getAs(director, "/api/v1/attendance/review")).get("children")).hasSize(1);
        assertThat(auditCount("KIOSK_PIN_MISMATCH")).isEqualTo(1);
    }

    @Test
    void connexionHorsLigneAvecLeBonCode() throws Exception {
        var login = new LinkedHashMap<String, Object>();
        login.put("clientId", UUID.randomUUID().toString());
        login.put("type", "CONNEXION_HORS_LIGNE");
        login.put("occurredAt", NOW.minus(40, ChronoUnit.MINUTES).toString());
        login.put("userId", educatriceId);
        login.put("pin", "4831");
        var arrival = arrival(NOW.minus(39, ChronoUnit.MINUTES));
        arrival.remove("sessionId");
        arrival.put("offlineSessionId", login.get("clientId"));

        var results = send(login, arrival);
        assertThat(results.findValuesAsText("outcome")).containsExactly("APPLIQUEE", "APPLIQUEE");
        assertThat(results.get(1).get("needsReview").asBoolean()).isFalse();
        assertThat(children().get(0).path("reviewReason").asText("")).doesNotContain("code incorrect");
    }

    @Test
    void pointageDuPersonnelHorsLigneVerifieALaReception() throws Exception {
        var sectionId = sectionId(director, "Moyens");
        var good = staffPunch(educatriceId, "4831", "ARRIVEE", sectionId);
        var cookId = createUser(director, "CUISINE", null, null, "5208").get("id").asText();
        var bad = staffPunch(cookId, "1397", "ARRIVEE", null);

        var results = send(good, bad);
        assertThat(results.findValuesAsText("outcome")).containsExactly("APPLIQUEE", "APPLIQUEE");
        assertThat(results.get(0).get("needsReview").asBoolean()).isFalse();
        assertThat(results.get(1).get("needsReview").asBoolean()).isTrue();

        var staff = body(getAs(director, "/api/v1/attendance?date=" + DAY)).get("staff");
        assertThat(staff).hasSize(2);
        for (var row : staff) {
            assertThat(row.get("source").asText()).isEqualTo("PIN_HORS_LIGNE");
            assertThat(row.get("pinVerified").asBoolean()).isEqualTo(row.get("userId").asText().equals(educatriceId));
        }
        // Départ sans arrivée : refusé.
        var departure = staffPunch(educatriceId, "4831", "DEPART", null);
        assertThat(send(departure).get(0).get("outcome").asText()).isEqualTo("APPLIQUEE");
        var again = staffPunch(educatriceId, "4831", "DEPART", null);
        assertThat(send(again).get(0).get("code").asText()).isEqualTo("not_present");
    }

    private Map<String, Object> staffPunch(String userId, String pin, String action, String sectionId) {
        var op = new LinkedHashMap<String, Object>();
        op.put("clientId", UUID.randomUUID().toString());
        op.put("type", "POINTAGE_PERSONNEL");
        op.put("occurredAt", NOW.minus(10, ChronoUnit.MINUTES).toString());
        op.put("offline", true);
        op.put("userId", userId);
        op.put("pin", pin);
        op.put("staffAction", action);
        op.put("sectionId", sectionId);
        return op;
    }

    @Test
    void auteurNonProuveRefuse() throws Exception {
        var anonymous = arrival(NOW);
        anonymous.remove("sessionId");
        assertThat(send(anonymous).get(0).get("code").asText()).isEqualTo("unknown_author");

        var otherId = createUser(director, "EDUCATRICE", null, null, "7316").get("id").asText();
        var borrowed = arrival(NOW);
        borrowed.put("userId", otherId);
        assertThat(send(borrowed).get(0).get("code").asText()).isEqualTo("unknown_author");

        var cookId = createUser(director, "CUISINE", null, null, "5208").get("id").asText();
        var cookSession = pinLogin(tablet, cookId, "5208").get("sessionId").asText();
        var byCook = arrival(NOW);
        byCook.put("userId", cookId);
        byCook.put("sessionId", cookSession);
        assertThat(send(byCook).get(0).get("code").asText()).isEqualTo("forbidden");
        assertThat(children()).isEmpty();
    }

    @Test
    void heureDeTabletteIncoherenteRefusee() throws Exception {
        var future = Instant.now().plus(1, ChronoUnit.HOURS);
        assertThat(send(arrival(future)).get(0).get("code").asText()).isEqualTo("invalid_time");
    }

    @Test
    void tabletteRetireeRefusee() throws Exception {
        assertThat(status(perform(delete("/api/v1/devices/" + tablet.id()), director, null))).isEqualTo(204);
        var result = perform(post("/api/v1/kiosk/operations"), tablet.token(), Map.of("operations", List.of(arrival(NOW))));
        assertThat(status(result)).isEqualTo(401);
        assertThat(body(result).get("code").asText()).isEqualTo("device_unknown");
        assertThat(status(perform(post("/api/v1/devices/session"), null,
                Map.of("deviceId", tablet.id(), "secret", tablet.secret())))).isEqualTo(401);
        assertThat(children()).isEmpty();
    }

    @Test
    void appairageCodeExpireOuDevine() throws Exception {
        var created = body(perform(post("/api/v1/devices"), director, Map.of("name", "Salle des Bébés")));
        var code = created.get("code").asText();
        jdbc.update("UPDATE device SET pairing_expires_at = now() - interval '1 minute' WHERE id = ?::uuid",
                created.at("/device/id").asText());
        var expired = perform(post("/api/v1/devices/pair"), null, Map.of("code", code));
        assertThat(status(expired)).isEqualTo(422);
        assertThat(body(expired).get("code").asText()).isEqualTo("pairing_code_invalid");

        var fresh = body(perform(post("/api/v1/devices/" + created.at("/device/id").asText() + "/pairing-code"),
                director, null)).get("code").asText();
        for (int i = 0; i < 4; i++) {
            var wrong = fresh.equals("00000" + i) ? "11111" + i : "00000" + i;
            assertThat(status(perform(post("/api/v1/devices/pair"), null, Map.of("code", wrong)))).isEqualTo(422);
        }
        // 5 codes faux (dont l'expiré) : l'adresse est bloquée, même avec le bon code.
        assertThat(status(perform(post("/api/v1/devices/pair"), null, Map.of("code", fresh)))).isEqualTo(429);

        var devices = body(getAs(director, "/api/v1/devices"));
        assertThat(devices.findValuesAsText("state")).contains("EN_ATTENTE", "APPAIREE");
    }
}
