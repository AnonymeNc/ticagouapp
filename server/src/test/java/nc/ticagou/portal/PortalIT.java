package nc.ticagou.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import com.fasterxml.jackson.databind.JsonNode;

import nc.ticagou.IntegrationTest;
import nc.ticagou.license.LicenseModule;
import nc.ticagou.license.TestLicenses;

/**
 * Portail parents de bout en bout avec un faux portail (ADR 0010) : rien ne sort tant qu'il est désactivé, seule la
 * liste blanche sort (jamais la santé), les remontées attendent la direction, la désactivation efface tout.
 */
class PortalIT extends IntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Pacific/Noumea"));
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'};

    private FakePortal portal;
    private String director;
    private String educatriceId;
    private String childId;
    private String otherChildId;
    private String familyId;
    private String guardianId;
    private Device tablet;
    private String sessionId;

    @BeforeEach
    void setUp() throws Exception {
        portal = new FakePortal(json);
        director = install();
        educatriceId = createUser(director, "EDUCATRICE", null, null, "4831").get("id").asText();
        var moyens = sectionId(director, "Moyens");
        childId = createChild(director, "Léa", "Wamytan", "2025-01-15", moyens);
        otherChildId = createChild(director, "Kenzo", "Lefèvre", "2025-01-10", moyens);
        familyId = jdbc.queryForObject("SELECT family_id::text FROM child WHERE id = ?::uuid", String.class, childId);
        guardianId = jdbc.queryForObject("SELECT id::text FROM guardian WHERE family_id = ?::uuid", String.class, familyId);
        // Santé : ne doit jamais sortir.
        jdbc.update("INSERT INTO allergy (structure_id, child_id, allergen, reaction, severity) "
                + "SELECT structure_id, id, 'Arachide', 'Œdème de Quincke', 'SEVERE' FROM child WHERE id = ?::uuid", childId);
        grantPhotos(childId, true);
        tablet = pairDevice(director, "Section des Moyens");
        sessionId = pinLogin(tablet, educatriceId, "4831").get("sessionId").asText();
    }

    @AfterEach
    void tearDown() {
        portal.close();
    }

    // --- Outils -------------------------------------------------------------------------------

    private void grantPhotos(String child, boolean granted) throws Exception {
        var consent = new LinkedHashMap<String, Object>();
        consent.put("type", "PHOTOS");
        consent.put("granted", granted);
        consent.put("signedOn", TODAY.toString());
        consent.put("signedBy", "Sarah");
        assertThat(status(perform(put("/api/v1/children/" + child + "/consents"), director, Map.of("consents", List.of(consent))))).isEqualTo(200);
    }

    private Map<String, Object> transmission(String kind, List<String> children, Map<String, String> details, String text,
                                             boolean shared) {
        var op = new LinkedHashMap<String, Object>();
        op.put("clientId", UUID.randomUUID().toString());
        op.put("type", "TRANSMISSION");
        op.put("occurredAt", Instant.now().toString());
        op.put("userId", educatriceId);
        op.put("sessionId", sessionId);
        op.put("entry", Map.of("kind", kind, "childIds", children, "details", details, "text", text == null ? "" : text,
                "shared", shared));
        return op;
    }

    private JsonNode send(Map<String, Object> op) throws Exception {
        var result = perform(post("/api/v1/kiosk/operations"), tablet.token(), Map.of("operations", List.of(op)));
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return body(result).at("/results/0");
    }

    private JsonNode sendPhoto(List<String> children, boolean shared) throws Exception {
        var op = transmission("PHOTO", children, Map.of(), "Atelier peinture", shared);
        var request = multipart("/api/v1/kiosk/journal/photo")
                .file(new MockMultipartFile("operation", "", MediaType.APPLICATION_JSON_VALUE, json.writeValueAsBytes(op)))
                .file(new MockMultipartFile("photo", "photo.jpg", MediaType.IMAGE_JPEG_VALUE, JPEG))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tablet.token());
        var result = mvc.perform(request).andReturn();
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return body(result);
    }

    private JsonNode activate() throws Exception {
        var result = perform(post("/api/v1/portal/activate"), director,
                Map.of("portalUrl", portal.url(), "activationCode", FakePortal.CODE));
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return body(result);
    }

    private JsonNode sync() throws Exception {
        var result = perform(post("/api/v1/portal/sync"), director, null);
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return body(result);
    }

    private long pending() {
        return jdbc.queryForObject("SELECT count(*) FROM portal_outbox WHERE sent_at IS NULL", Long.class);
    }

    private List<JsonNode> received(String type) {
        return portal.items().stream().filter(i -> i.get("type").asText().equals(type)).toList();
    }

    // --- Scénarios ----------------------------------------------------------------------------

    @Test
    void desactiveRienNeSortMaisLaTabletteSaisit() throws Exception {
        var meal = transmission("REPAS", List.of(childId), Map.of("meal", "DEJEUNER", "quantity", "BIEN"),
                "Poisson, purée de patate douce", true);
        assertThat(send(meal).get("outcome").asText()).isEqualTo("APPLIQUEE");
        assertThat(pending()).isZero();

        var day = body(getAs(tablet.token(), "/api/v1/kiosk/journal"));
        assertThat(day.at("/entries/0/kind").asText()).isEqualTo("REPAS");
        assertThat(day.at("/entries/0/details/quantity").asText()).isEqualTo("BIEN");
        assertThat(day.get("photoConsents").toString()).contains(childId).doesNotContain(otherChildId);

        // Rejouée après une coupure : une seule transmission.
        assertThat(send(meal).get("outcome").asText()).isEqualTo("DEJA_RECUE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM daily_entry", Long.class)).isEqualTo(1);

        // Détail non prévu : refusé.
        var odd = send(transmission("CHANGE", List.of(childId), Map.of("change", "SELLES", "medicament", "x"), null, true));
        assertThat(odd.get("outcome").asText()).isEqualTo("REFUSEE");
        assertThat(odd.get("code").asText()).isEqualTo("invalid_entry");
    }

    @Test
    void medicamentDonneTransmisAuxParentsPourUnSeulEnfant() throws Exception {
        var other = createChild(director, "Kaïna", "Wamytan", "2024-03-02", sectionId(director, "Grands"));
        var refused = send(transmission("MEDICAMENT", List.of(childId, other), Map.of("medicine", "Doliprane"), null, true));
        assertThat(refused.get("outcome").asText()).isEqualTo("REFUSEE");
        assertThat(refused.get("message").asText()).contains("enfant par enfant");

        var op = transmission("MEDICAMENT", List.of(childId), Map.of("medicine", "Doliprane", "dose", "2,5 ml"), null, true);
        assertThat(send(op).get("outcome").asText()).isNotEqualTo("REFUSEE");

        assertThat(jdbc.queryForObject("SELECT details ->> 'medicine' FROM daily_entry WHERE kind = 'MEDICAMENT'", String.class))
                .isEqualTo("Doliprane");
    }

    @Test
    void activationPuisEnvoiDeLaListeBlancheSansSante() throws Exception {
        var refused = perform(post("/api/v1/portal/activate"), director,
                Map.of("portalUrl", portal.url(), "activationCode", "MAUVAIS-CODE"));
        assertThat(status(refused)).isGreaterThanOrEqualTo(400);

        assertThat(activate().get("state").asText()).isEqualTo("ACTIF");
        assertThat(portal.activatedName).isEqualTo("Les P’tits Niaoulis");

        // Famille non invitée : rien ne la concerne.
        send(transmission("REPAS", List.of(childId), Map.of("meal", "GOUTER", "quantity", "TOUT"), null, true));
        assertThat(status(perform(post("/api/v1/guardians/" + guardianId + "/portal-access"), director, null))).isEqualTo(200);
        assertThat(status(perform(post("/api/v1/children/" + childId + "/health-shares"), director,
                Map.of("label", "Allergie", "text", "PAI en place, trousse dans la section")))).isEqualTo(201);

        send(transmission("SIESTE", List.of(childId), Map.of("from", "12:30", "to", "14:00"), null, true));
        send(transmission("MOT", List.of(childId), Map.of(), "Léa a chanté toute la matinée", false));
        sendPhoto(List.of(childId), true);
        // Photo avec un enfant sans autorisation : ne sort pas.
        sendPhoto(List.of(childId, otherChildId), true);

        var result = sync();
        assertThat(result.get("error").isNull()).as(result.toString()).isTrue();
        assertThat(portal.rejected).isEmpty();
        assertThat(pending()).isZero();

        assertThat(received("STRUCTURE")).hasSize(1);
        assertThat(received("GUARDIAN").getFirst().at("/payload/email").asText()).isEqualTo("sarah@exemple.nc");
        var child = received("CHILD").stream().filter(c -> c.get("id").asText().equals(childId)).findFirst().orElseThrow();
        assertThat(child.at("/payload/lastInitial").asText()).isEqualTo("W");
        assertThat(child.at("/payload/sharedHealth/0/text").asText()).isEqualTo("PAI en place, trousse dans la section");
        // L'autre famille n'est pas invitée : son enfant ne sort pas.
        assertThat(received("CHILD")).noneMatch(c -> c.get("id").asText().equals(otherChildId) && !c.get("deleted").asBoolean());

        var entries = received("ENTRY");
        var shared = entries.stream().filter(e -> !e.get("deleted").asBoolean()).toList();
        assertThat(shared).extracting(e -> e.at("/payload/kind").asText()).contains("REPAS", "SIESTE", "PHOTO")
                .doesNotContain("MOT");
        assertThat(shared.stream().filter(e -> e.at("/payload/kind").asText().equals("PHOTO"))).hasSize(1)
                .allMatch(e -> !e.at("/payload/photo").asText().isEmpty());
        assertThat(entries.stream().filter(e -> e.get("deleted").asBoolean())).hasSizeGreaterThanOrEqualTo(2);

        // Jamais de santé, hors partage explicite.
        assertThat(portal.received()).doesNotContain("Arachide").doesNotContain("Quincke").doesNotContain("SEVERE");
    }

    @Test
    void remonteesAttendentLaDirection() throws Exception {
        activate();
        perform(post("/api/v1/guardians/" + guardianId + "/portal-access"), director, null);
        var document = body(perform(post("/api/v1/portal/documents/consent"), director,
                Map.of("childId", childId, "type", "SORTIES")));
        assertThat(document.get("status").asText()).isEqualTo("A_SIGNER");
        sync();
        var sent = received("DOCUMENT").getFirst();
        var sha = sent.at("/payload/sha256").asText();

        portal.inbox.add(json.valueToTree(Map.of("seq", 1, "type", "ABSENCE", "id", UUID.randomUUID(), "familyId", familyId,
                "payload", Map.of("childId", childId, "guardianId", guardianId, "fromDay", TODAY.plusDays(2).toString(),
                        "toDay", TODAY.plusDays(3).toString(), "reason", "Fièvre", "reportedAt", Instant.now().toString()))));
        portal.inbox.add(json.valueToTree(Map.of("seq", 2, "type", "MESSAGE", "id", UUID.randomUUID(), "familyId", familyId,
                "payload", Map.of("guardianId", guardianId, "body", "Merci pour les photos !", "sentAt", Instant.now().toString()))));
        portal.inbox.add(json.valueToTree(Map.of("seq", 3, "type", "SIGNATURE", "id", UUID.randomUUID(), "familyId", familyId,
                "payload", Map.of("documentId", document.get("id").asText(), "guardianId", guardianId,
                        "signerName", "Sarah Wamytan", "decision", "ACCEPTE", "signedAt", Instant.now().toString(), "ip", "203.0.113.24",
                        "sha256", sha))));
        // Enfant d'une autre famille : ignoré.
        portal.inbox.add(json.valueToTree(Map.of("seq", 4, "type", "ABSENCE", "id", UUID.randomUUID(), "familyId", familyId,
                "payload", Map.of("childId", otherChildId, "fromDay", TODAY.toString(), "toDay", TODAY.toString()))));

        assertThat(sync().get("received").asInt()).isEqualTo(3);
        assertThat(portal.acks).contains(4L);
        // Déjà lu : rien de nouveau.
        assertThat(sync().get("received").asInt()).isZero();

        var absences = body(getAs(director, "/api/v1/portal/reported-absences"));
        assertThat(absences).hasSize(1);
        assertThat(absences.at("/0/guardianName").asText()).isEqualTo("Sarah Wamytan");
        var reasonId = jdbc.queryForObject("SELECT id::text FROM absence_reason LIMIT 1", String.class);
        var confirmed = perform(post("/api/v1/portal/reported-absences/" + absences.at("/0/id").asText() + "/confirm"),
                director, Map.of("reasonId", reasonId));
        assertThat(status(confirmed)).as(confirmed.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM child_absence WHERE child_id = ?::uuid", Long.class, childId))
                .isEqualTo(1);

        assertThat(body(getAs(director, "/api/v1/messages/unread")).asLong()).isEqualTo(1);
        var thread = body(getAs(director, "/api/v1/families/" + familyId + "/messages"));
        assertThat(thread.at("/messages/0/body").asText()).isEqualTo("Merci pour les photos !");
        assertThat(body(getAs(director, "/api/v1/messages/unread")).asLong()).isZero();
        perform(post("/api/v1/families/" + familyId + "/messages"), director, Map.of("body", "Avec plaisir !"));
        sync();
        assertThat(received("MESSAGE").getFirst().at("/payload/author").asText()).isEqualTo("Nathalie");

        var signed = body(getAs(director, "/api/v1/portal/documents"));
        assertThat(signed).hasSize(1);
        assertThat(signed.at("/0/signerIp").asText()).isEqualTo("203.0.113.24");
        var recorded = perform(post("/api/v1/portal/documents/" + document.get("id").asText() + "/record"), director, null);
        assertThat(status(recorded)).as(recorded.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT granted FROM consent WHERE child_id = ?::uuid AND type = 'SORTIES'",
                Boolean.class, childId)).isTrue();
        assertThat(auditCount("PORTAL_SIGNATURE_RECORDED")).isEqualTo(1);
    }

    @Test
    void retraitDeLAutorisationPhotoRetireLesPhotos() throws Exception {
        activate();
        perform(post("/api/v1/guardians/" + guardianId + "/portal-access"), director, null);
        var photo = sendPhoto(List.of(childId), true);
        assertThat(photo.get("outcome").asText()).isEqualTo("APPLIQUEE");
        sync();
        assertThat(received("ENTRY")).anyMatch(e -> !e.get("deleted").asBoolean());

        grantPhotos(childId, false);
        portal.batches.clear();
        sync();
        assertThat(received("ENTRY")).isNotEmpty().allMatch(e -> e.get("deleted").asBoolean());
        assertThat(received("CHILD").getFirst().at("/payload/photo").isNull()).isTrue();
    }

    @Test
    void retraitDeLAccesEtDesactivationEffacent() throws Exception {
        activate();
        perform(post("/api/v1/guardians/" + guardianId + "/portal-access"), director, null);
        sync();
        assertThat(status(perform(delete("/api/v1/guardians/" + guardianId + "/portal-access"), director, null))).isEqualTo(200);
        sync();
        assertThat(received("PURGE_FAMILY")).extracting(i -> i.get("id").asText()).containsExactly(familyId);

        var wrong = perform(post("/api/v1/portal/deactivate"), director, Map.of("confirmation", "oui"));
        assertThat(body(wrong).get("code").asText()).isEqualTo("confirmation_mismatch");
        var result = body(perform(post("/api/v1/portal/deactivate"), director, Map.of("confirmation", "DÉSACTIVER")));
        assertThat(result.at("/status/state").asText()).isEqualTo("DESACTIVE");
        assertThat(received("PURGE_STRUCTURE")).hasSize(1);
        // La table des secrets n'est pas vidée entre les tests : seule la clé de cette structure compte.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM server_secret WHERE name LIKE 'portal-%' AND name LIKE '%' || (SELECT id::text FROM structure)",
                Long.class)).isZero();

        send(transmission("REPAS", List.of(childId), Map.of("meal", "DEJEUNER", "quantity", "PEU"), null, true));
        assertThat(pending()).isZero();
    }

    @Test
    void portailInjoignableLaFileAttend() throws Exception {
        activate();
        portal.down = true;
        var result = sync();
        assertThat(result.get("error").asText()).contains("503");
        assertThat(pending()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT failures FROM portal_settings", Integer.class)).isEqualTo(1);

        portal.down = false;
        assertThat(sync().get("error").isNull()).isTrue();
        assertThat(pending()).isZero();
        assertThat(jdbc.queryForObject("SELECT failures FROM portal_settings", Integer.class)).isZero();
    }

    @Test
    void sansLePortailPasDeTransmission() throws Exception {
        TestLicenses.apply(jdbc, 48, Set.of(), 2, TODAY.plusYears(1));

        var refused = send(transmission("MOT", List.of(childId), Map.of(), "Bonne journée", true));

        assertThat(refused.get("outcome").asText()).isEqualTo("REFUSEE");
        assertThat(refused.get("code").asText()).isEqualTo("module_absent");
    }

    @Test
    void moduleAbsentDeLaLicence() throws Exception {
        TestLicenses.apply(jdbc, 48, Set.of(LicenseModule.FACTURATION), 2, TODAY.plusYears(1));
        var result = perform(post("/api/v1/portal/activate"), director,
                Map.of("portalUrl", portal.url(), "activationCode", FakePortal.CODE));
        assertThat(status(result)).isEqualTo(403);
        assertThat(body(result).get("code").asText()).isEqualTo("module_absent");
        assertThat(body(getAs(director, "/api/v1/portal")).get("moduleIncluded").asBoolean()).isFalse();
    }

    @Test
    void educatriceSansAccesAuxReglages() throws Exception {
        createUser(director, "EDUCATRICE", "lina.wema", "Tamanou-2026!", null);
        var educatrice = accessToken("lina.wema", "Tamanou-2026!");
        assertThat(status(getAs(educatrice, "/api/v1/portal"))).isEqualTo(403);
        assertThat(status(getAs(educatrice, "/api/v1/messages"))).isEqualTo(200);
        assertThat(new String(getAs(educatrice, "/api/v1/journal?day=" + TODAY).getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8)).isEqualTo("[]");
    }
}
