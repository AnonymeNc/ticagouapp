package nc.ticagou.portail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import jakarta.servlet.http.Cookie;
import nc.ticagou.portail.common.Hashes;

/** Connexion par lien magique ou code, et API parents restreinte à la famille de la session. */
class ParentIT extends PortalTest {

    private static final String MARIE = "marie.wamytan@example.nc";
    private static final UUID FAMILY = UUID.fromString("0b9a3c1e-0000-4000-8000-000000000100");
    private static final UUID CHILD = UUID.fromString("0b9a3c1e-0000-4000-8000-000000001000");
    private static final UUID OTHER_FAMILY = UUID.randomUUID();
    private static final UUID OTHER_CHILD = UUID.randomUUID();
    private static final LocalDate DAY = LocalDate.parse("2026-11-02");

    private Creche creche;

    @BeforeEach
    void data() throws Exception {
        creche = activate("Les P'tits Cagous");
        batch(creche, fixture("batch.json"));
        var pdf = "%PDF-1.4 autorisation".getBytes(StandardCharsets.US_ASCII);
        batch(creche, Map.of("items", List.of(
                Map.of("seq", 11, "type", "GUARDIAN", "id", UUID.randomUUID(), "deleted", false, "payload",
                        Map.of("familyId", OTHER_FAMILY, "firstName", "Paul", "lastName", "Naisseline", "email", "paul@example.nc")),
                Map.of("seq", 12, "type", "CHILD", "id", OTHER_CHILD, "deleted", false, "payload",
                        Map.of("familyId", OTHER_FAMILY, "firstName", "Tom", "lastInitial", "N", "section", "Grands",
                                "sharedHealth", List.of())),
                Map.of("seq", 13, "type", "DOCUMENT", "id", UUID.fromString("0b9a3c1e-0000-4000-8000-000000005001"),
                        "deleted", false, "payload", Map.of("familyId", FAMILY, "childId", CHILD, "kind", "AUTORISATION",
                                "title", "Autorisation de sorties — Léa", "pdf", Base64.getEncoder().encodeToString(pdf),
                                "sha256", Hashes.sha256(pdf), "status", "A_SIGNER")))));
    }

    private MvcResult call(MockHttpServletRequestBuilder request, Cookie cookie, Object body) throws Exception {
        request.header("X-Ticagou-Portail", "1");
        if (cookie != null) {
            request.cookie(cookie);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        }
        return mvc.perform(request).andReturn();
    }

    @Test
    void reponseIdentiquePourUneAdresseInconnue() throws Exception {
        var unknown = call(post("/api/v1/auth/request"), null, Map.of("email", "inconnu@example.nc"));
        var known = call(post("/api/v1/auth/request"), null, Map.of("email", MARIE.toUpperCase()));
        assertThat(status(unknown)).isEqualTo(202);
        assertThat(unknown.getResponse().getContentAsString()).isEqualTo(known.getResponse().getContentAsString());
        var mail = awaitMail();
        assertThat(MAILS).hasSize(1);
        assertThat(mail.getTo()).containsExactly(MARIE);
        assertThat(mail.getText()).contains("https://parents.ticagou.nc/connexion#jeton=");
        // Seules les empreintes sont gardées.
        assertThat(jdbc.queryForObject("SELECT token_hash FROM login_request", String.class)).isEqualTo(Hashes.sha256(token(mail)));
    }

    @Test
    void lienMagiqueAUsageUniqueEtCookieProtege() throws Exception {
        call(post("/api/v1/auth/request"), null, Map.of("email", MARIE));
        var token = token(awaitMail());
        var ok = call(post("/api/v1/auth/verify"), null, Map.of("token", token));
        assertThat(status(ok)).isEqualTo(200);
        var header = ok.getResponse().getHeader("Set-Cookie");
        assertThat(header).contains("ticagou_session=", "HttpOnly", "Secure", "SameSite=Strict");
        assertThat(status(call(post("/api/v1/auth/verify"), null, Map.of("token", token)))).isEqualTo(401);

        var cookie = ok.getResponse().getCookie("ticagou_session");
        assertThat(status(call(get("/api/v1/me"), cookie, null))).isEqualTo(200);
        call(post("/api/v1/auth/logout"), cookie, null);
        assertThat(status(call(get("/api/v1/me"), cookie, null))).isEqualTo(401);
    }

    @Test
    void cinqCodesFauxBloquentLaDemande() throws Exception {
        call(post("/api/v1/auth/request"), null, Map.of("email", MARIE));
        var good = code(awaitMail());
        var wrong = good.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            assertThat(status(call(post("/api/v1/auth/verify"), null, Map.of("email", MARIE, "code", wrong)))).isEqualTo(401);
        }
        assertThat(status(call(post("/api/v1/auth/verify"), null, Map.of("email", MARIE, "code", good)))).isEqualTo(401);
    }

    @Test
    void ecritureSansEnTeteDuPortailRefusee() throws Exception {
        var cookie = login(MARIE);
        var request = post("/api/v1/me/messages").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("structureId", creche.id(), "familyId", FAMILY, "body", "Bonjour")));
        assertThat(status(mvc.perform(request).andReturn())).isEqualTo(403);
    }

    @Test
    void leParentNeVoitQueSaFamille() throws Exception {
        var cookie = login(MARIE);
        var me = body(call(get("/api/v1/me"), cookie, null));
        assertThat(me.at("/families")).hasSize(1);
        assertThat(me.at("/families/0/children/0/firstName").asText()).isEqualTo("Léa");
        assertThat(me.at("/families/0/paymentInfo").asText()).contains("IBAN");
        assertThat(me.at("/families/0/documentsToSign").asLong()).isEqualTo(2);
        assertThat(me.at("/families/0/unreadMessages").asLong()).isEqualTo(1);

        var day = body(call(get("/api/v1/me/children/" + creche.id() + "/" + CHILD + "/day?date=" + DAY), cookie, null));
        assertThat(day.get("items")).extracting(i -> i.get("type").asText()).containsExactly("ATTENDANCE", "ENTRY");
        assertThat(day.at("/items/1/data/details/quantity").asText()).isEqualTo("BIEN");

        var photo = call(get("/api/v1/me/children/" + creche.id() + "/" + CHILD + "/photo"), cookie, null);
        assertThat(status(photo)).isEqualTo(200);
        assertThat(photo.getResponse().getContentAsByteArray()).isEqualTo(Base64.getDecoder().decode("/9j/4AAQSkZJRgABAQ=="));

        var invoices = body(call(get("/api/v1/me/invoices"), cookie, null));
        var pdf = call(get("/api/v1/me/files/" + creche.id() + "/" + invoices.at("/0/id").asText()), cookie, null);
        assertThat(new String(pdf.getResponse().getContentAsByteArray(), StandardCharsets.US_ASCII)).startsWith("%PDF");
        assertThat(pdf.getResponse().getHeader("Cache-Control")).contains("no-store");

        // L'enfant d'une autre famille n'existe pas pour Marie.
        assertThat(status(call(get("/api/v1/me/children/" + creche.id() + "/" + OTHER_CHILD + "/day"), cookie, null))).isEqualTo(404);
        assertThat(status(call(get("/api/v1/me/messages?structureId=" + creche.id() + "&familyId=" + OTHER_FAMILY), cookie, null)))
                .isEqualTo(404);
    }

    @Test
    void absenceMessageEtSignatureRemontent() throws Exception {
        var cookie = login(MARIE);
        var today = LocalDate.now(ZoneId.of("Pacific/Noumea"));
        var absence = call(post("/api/v1/me/absences"), cookie, Map.of("structureId", creche.id(), "childId", CHILD,
                "fromDay", today.plusDays(1).toString(), "toDay", today.plusDays(2).toString(), "reason", "Maladie"));
        assertThat(status(absence)).as(absence.getResponse().getContentAsString()).isEqualTo(201);
        var past = call(post("/api/v1/me/absences"), cookie, Map.of("structureId", creche.id(), "childId", CHILD,
                "fromDay", today.minusDays(1).toString(), "toDay", today.toString()));
        assertThat(body(past).get("code").asText()).isEqualTo("absence_past");
        assertThat(status(call(post("/api/v1/me/absences"), cookie, Map.of("structureId", creche.id(), "childId", OTHER_CHILD,
                "fromDay", today.toString(), "toDay", today.toString())))).isEqualTo(404);

        assertThat(status(call(post("/api/v1/me/messages"), cookie,
                Map.of("structureId", creche.id(), "familyId", FAMILY, "body", "Merci !")))).isEqualTo(201);
        var thread = body(call(get("/api/v1/me/messages?structureId=" + creche.id() + "&familyId=" + FAMILY), cookie, null));
        assertThat(thread).extracting(m -> m.at("/data/fromFamily").asBoolean()).containsExactly(false, true);
        assertThat(body(call(get("/api/v1/me"), cookie, null)).at("/families/0/unreadMessages").asLong()).isZero();

        var document = "0b9a3c1e-0000-4000-8000-000000005001";
        var unchecked = call(post("/api/v1/me/documents/" + creche.id() + "/" + document + "/sign"), cookie,
                Map.of("decision", "ACCEPTE", "certified", false));
        assertThat(status(unchecked)).isEqualTo(400);
        var signed = call(post("/api/v1/me/documents/" + creche.id() + "/" + document + "/sign"), cookie,
                Map.of("decision", "ACCEPTE", "certified", true));
        assertThat(status(signed)).as(signed.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(status(call(post("/api/v1/me/documents/" + creche.id() + "/" + document + "/sign"), cookie,
                Map.of("decision", "ACCEPTE", "certified", true)))).isEqualTo(422);
        // Empreinte de l'exemple différente du PDF reçu : refusé.
        var altered = call(post("/api/v1/me/documents/" + creche.id() + "/0b9a3c1e-0000-4000-8000-000000005000/sign"),
                cookie, Map.of("decision", "ACCEPTE", "certified", true));
        assertThat(body(altered).get("code").asText()).isEqualTo("document_altered");

        var inbox = body(signed(creche, "GET", "/sync/v1/inbox?after=0", new byte[0], java.time.Instant.now())).get("items");
        assertThat(inbox).extracting(i -> i.get("type").asText()).containsExactly("ABSENCE", "MESSAGE", "SIGNATURE");
        assertThat(inbox.get(0).at("/payload/reason").asText()).isEqualTo("Maladie");
        assertThat(inbox.get(2).at("/payload/sha256").asText())
                .isEqualTo(Hashes.sha256("%PDF-1.4 autorisation".getBytes(StandardCharsets.US_ASCII)));
        assertThat(inbox.get(2).at("/payload/signerName").asText()).isEqualTo("Marie Wamytan");
        // Format attendu par le serveur de la crèche (parents-portal/contract/inbox.json).
        var expected = fixture("inbox.json").get("items");
        for (int i = 0; i < 3; i++) {
            var keys = new java.util.TreeSet<String>();
            expected.get(i).get("payload").fieldNames().forEachRemaining(keys::add);
            var actual = new java.util.TreeSet<String>();
            inbox.get(i).get("payload").fieldNames().forEachRemaining(actual::add);
            assertThat(actual).as(expected.get(i).get("type").asText()).isEqualTo(keys);
        }
    }

    private void accessUntil(long seq, LocalDate day) throws Exception {
        var payload = new java.util.HashMap<String, Object>(Map.of("name", "Les P'tits Cagous"));
        payload.put("accessUntil", day == null ? null : day.toString());
        assertThat(status(batch(creche, Map.of("items", List.of(Map.of("seq", seq, "type", "STRUCTURE", "id", creche.id(),
                "deleted", false, "payload", payload)))))).isEqualTo(200);
    }

    private java.time.Instant sessionEnd() {
        return jdbc.queryForObject("SELECT max(expires_at) FROM parent_session", java.sql.Timestamp.class).toInstant();
    }

    private static long maxAge(MvcResult result) {
        var m = java.util.regex.Pattern.compile("Max-Age=(\\d+)").matcher(result.getResponse().getHeader("Set-Cookie"));
        assertThat(m.find()).isTrue();
        return Long.parseLong(m.group(1));
    }

    @Test
    void laSessionSuitLAbonnementDeLaCreche() throws Exception {
        var noumea = ZoneId.of("Pacific/Noumea");
        var today = LocalDate.now(noumea);
        accessUntil(40, today.plusDays(60));
        var cookie = login(MARIE);
        // Fin de session : lendemain du dernier jour d'accès, à minuit à Nouméa.
        assertThat(sessionEnd()).isEqualTo(today.plusDays(61).atStartOfDay(noumea).toInstant());

        // Renouvellement : la même session s'allonge, le cookie aussi (plafonné à un an), sans nouvel e-mail.
        accessUntil(41, today.plusDays(400));
        var me = call(get("/api/v1/me"), cookie, null);
        assertThat(status(me)).isEqualTo(200);
        assertThat(maxAge(me)).isEqualTo(java.time.Duration.ofDays(365).toSeconds());
        assertThat(sessionEnd()).isEqualTo(today.plusDays(401).atStartOfDay(noumea).toInstant());

        // Abonnement échu (grâce comprise) : plus d'accès ni d'e-mail de connexion.
        accessUntil(42, today.minusDays(1));
        assertThat(status(call(get("/api/v1/me"), cookie, null))).isEqualTo(401);
        MAILS.clear();
        assertThat(status(call(post("/api/v1/auth/request"), null, Map.of("email", MARIE)))).isEqualTo(202);
        Thread.sleep(300);
        assertThat(MAILS).isEmpty();

        // Dernier jour d'accès : encore ouvert.
        accessUntil(43, today);
        assertThat(status(call(get("/api/v1/me"), login(MARIE), null))).isEqualTo(200);

        // Serveur de crèche sans date : 30 jours glissants.
        accessUntil(44, null);
        var fresh = login(MARIE);
        assertThat(maxAge(call(get("/api/v1/me"), fresh, null))).isBetween(java.time.Duration.ofDays(29).toSeconds(),
                java.time.Duration.ofDays(30).toSeconds());
    }

    @Test
    void accesRetireLaSessionNeVoitPlusRien() throws Exception {
        var cookie = login(MARIE);
        batch(creche, Map.of("items", List.of(Map.of("seq", 30, "type", "PURGE_FAMILY", "id", FAMILY, "deleted", true))));
        assertThat(status(call(get("/api/v1/me"), cookie, null))).isEqualTo(401);
    }
}
