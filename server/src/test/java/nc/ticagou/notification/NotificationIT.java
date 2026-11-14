package nc.ticagou.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import com.sun.net.httpserver.HttpServer;

import nc.ticagou.IntegrationTest;
import nc.ticagou.auth.AppClient;
import nc.ticagou.license.DesktopClientService;

/**
 * Notifications natives de la direction et départ validé à distance (ADR 0026, maquette B5), avec un faux service APNs.
 */
class NotificationIT extends IntegrationTest {

    record Push(String path, String body) {
    }

    static final List<Push> PUSHES = new CopyOnWriteArrayList<>();
    static final HttpServer APNS;
    static final String KEY;

    static {
        try {
            APNS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            APNS.createContext("/", exchange -> {
                var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                PUSHES.add(new Push(exchange.getRequestURI().getPath(), body));
                // Un appareil désinstallé : Apple répond 410.
                var gone = exchange.getRequestURI().getPath().endsWith("/perime");
                exchange.sendResponseHeaders(gone ? 410 : 200, -1);
                exchange.close();
            });
            APNS.start();
            var generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KEY = Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        } catch (IOException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void apns(DynamicPropertyRegistry registry) {
        registry.add("ticagou.push.apns-key", () -> KEY);
        registry.add("ticagou.push.apns-key-id", () -> "KEYID12345");
        registry.add("ticagou.push.apns-team-id", () -> "TEAM123456");
        registry.add("ticagou.push.apns-url", () -> "http://127.0.0.1:" + APNS.getAddress().getPort());
    }

    @AfterAll
    static void stopApns() {
        APNS.stop(0);
    }

    static final String IPHONE = "direction/1.0.0 (ios)";

    @Autowired
    NotificationService notifications;

    String director;
    String childId;
    Device tablet;

    @BeforeEach
    void installer() throws Exception {
        PUSHES.clear();
        director = install();
        childId = createChild(director, "Lila", "Gope", "2024-08-10", sectionId(director, "Moyens"));
        tablet = pairDevice(director, "Accueil");
    }

    MvcResult register(String token, String app, Object categories) throws Exception {
        var request = put("/api/v1/notifications/device").header(DesktopClientService.HEADER, "iphone-nathalie");
        if (app != null) {
            request.header(AppClient.HEADER, app);
        }
        var body = new java.util.HashMap<String, Object>(Map.of("platform", "IOS", "token", token));
        if (categories != null) {
            body.put("categories", categories);
        }
        return perform(request, director, body);
    }

    String arrive() throws Exception {
        var arrival = perform(post("/api/v1/attendance/children/" + childId + "/arrival"), director, Map.of("droppedOffBy", "Père"));
        assertThat(status(arrival)).isEqualTo(201);
        return body(getAs(director, "/api/v1/attendance")).get("children").get(0).get("id").asText();
    }

    MvcResult requestDeparture(String attendanceId) throws Exception {
        return perform(post("/api/v1/kiosk/departure-requests"), tablet.token(), Map.of("attendanceId", attendanceId,
                "personName", "Joseph Gope", "relationship", "Oncle", "idChecked", true, "requestedBy", "Sandrine W."));
    }

    @Test
    void seuleLApplicationInscritUnTelephoneAvecLesCategoriesParDefaut() throws Exception {
        assertThat(status(register("jeton-1", null, null))).isEqualTo(422);
        var registered = body(register("jeton-1", IPHONE, null));
        assertThat(registered.get("categories").toString()).isEqualTo("[\"DEPART\",\"ENCADREMENT\",\"SAUVEGARDE\"]");

        assertThat(status(register("jeton-1", IPHONE, List.of("DEPART")))).isEqualTo(200);
        assertThat(body(getAs(director, "/api/v1/notifications/device?token=jeton-1")).get("categories").toString())
                .isEqualTo("[\"DEPART\"]");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_device", Integer.class)).isEqualTo(1);

        assertThat(status(perform(delete("/api/v1/notifications/device?token=jeton-1"), director, null))).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_device", Integer.class)).isZero();
    }

    @Test
    void departAValiderADistanceAutoriseAvecMotifTrace() throws Exception {
        register("iphone-direction", IPHONE, null);
        var attendanceId = arrive();

        var request = body(requestDeparture(attendanceId));
        var requestId = request.get("id").asText();
        assertThat(request.get("status").asText()).isEqualTo("PENDING");

        // La direction est prévenue : prénom et initiale, ni la personne venue ni la santé.
        notifications.awaitPending();
        assertThat(PUSHES).hasSize(1);
        assertThat(PUSHES.getFirst().path()).isEqualTo("/3/device/iphone-direction");
        assertThat(PUSHES.getFirst().body()).contains("Départ à valider : Lila G.").doesNotContain("Joseph").doesNotContain("Gope");

        var pending = body(getAs(director, "/api/v1/attendance/departure-requests"));
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).get("childName").asText()).isEqualTo("Lila Gope");
        assertThat(pending.get(0).get("personName").asText()).isEqualTo("Joseph Gope");
        assertThat(pending.get(0).get("requestedBy").asText()).isEqualTo("Sandrine W.");
        assertThat(pending.get(0).get("deviceName").asText()).isEqualTo("Accueil");

        // Sans motif : refusé. La tablette ne décide pas.
        var decision = "/api/v1/attendance/departure-requests/" + requestId + "/decision";
        assertThat(status(perform(post(decision), director, Map.of("approve", true, "reason", " ")))).isEqualTo(400);
        assertThat(status(perform(post(decision), tablet.token(), Map.of("approve", true, "reason", "x")))).isEqualTo(403);

        var approved = body(perform(post(decision), director, Map.of("approve", true, "reason", "Le père a confirmé par téléphone")));
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.get("decidedBy").asText()).isNotBlank();

        var record = body(getAs(director, "/api/v1/attendance")).get("children").get(0);
        assertThat(record.get("departedAt").isNull()).isFalse();
        assertThat(record.get("pickedUpByName").asText()).isEqualTo("Joseph Gope");
        assertThat(jdbc.queryForObject("SELECT reason FROM departure_override", String.class))
                .isEqualTo("Validé à distance : Le père a confirmé par téléphone");
        assertThat(jdbc.queryForObject("SELECT pin_verified FROM departure_override", Boolean.class)).isTrue();
        assertThat(auditCount("DEPARTURE_REQUESTED")).isEqualTo(1);
        assertThat(auditCount("DEPARTURE_REQUEST_DECIDED")).isEqualTo(1);
        assertThat(auditCount("CHILD_DEPARTURE_OVERRIDE")).isEqualTo(1);

        var kiosk = body(getAs(tablet.token(), "/api/v1/kiosk/departure-requests/" + requestId));
        assertThat(kiosk.get("status").asText()).isEqualTo("APPROVED");
        assertThat(status(perform(post(decision), director, Map.of("approve", false, "reason", "trop tard")))).isEqualTo(422);
        assertThat(body(getAs(director, "/api/v1/attendance/departure-requests"))).isEmpty();
    }

    @Test
    void departRefuseLEnfantResteLa() throws Exception {
        var attendanceId = arrive();
        var requestId = body(requestDeparture(attendanceId)).get("id").asText();
        var refused = body(perform(post("/api/v1/attendance/departure-requests/" + requestId + "/decision"), director,
                Map.of("approve", false, "reason", "Personne inconnue de la famille")));
        assertThat(refused.get("status").asText()).isEqualTo("REFUSED");
        assertThat(body(getAs(director, "/api/v1/attendance")).get("children").get(0).get("departedAt").isNull()).isTrue();
        var kiosk = body(getAs(tablet.token(), "/api/v1/kiosk/departure-requests/" + requestId));
        assertThat(kiosk.get("reason").asText()).isEqualTo("Personne inconnue de la famille");

        // La tablette peut retirer sa demande ; une nouvelle remplace l'ancienne.
        var second = body(requestDeparture(attendanceId)).get("id").asText();
        assertThat(status(perform(delete("/api/v1/kiosk/departure-requests/" + second), tablet.token(), null))).isEqualTo(204);
        assertThat(body(getAs(director, "/api/v1/attendance/departure-requests"))).isEmpty();
    }

    @Test
    void unTelephoneRetireOuUnJetonPerimeNeRecoitPlusRien() throws Exception {
        perform(post("/api/v1/auth/login").header(DesktopClientService.HEADER, "iphone-nathalie").header(AppClient.HEADER, IPHONE),
                null, Map.of("login", DIRECTOR_LOGIN, "password", DIRECTOR_PASSWORD));
        register("iphone-direction", IPHONE, null);
        var other = put("/api/v1/notifications/device").header(AppClient.HEADER, IPHONE).header(DesktopClientService.HEADER, "autre");
        perform(other, director, Map.of("platform", "IOS", "token", "perime"));

        requestDeparture(arrive());
        notifications.awaitPending();
        assertThat(PUSHES).extracting(Push::path).containsExactlyInAnyOrder("/3/device/iphone-direction", "/3/device/perime");
        // Apple a répondu 410 : le jeton est oublié.
        assertThat(jdbc.queryForList("SELECT token FROM notification_device", String.class)).containsExactly("iphone-direction");

        // Téléphone perdu : la direction retire le poste, ses notifications s'arrêtent.
        var poste = jdbc.queryForObject("SELECT id FROM desktop_client WHERE kind = 'MOBILE'", String.class);
        assertThat(status(perform(post("/api/v1/system/desktops/" + poste + "/revoke"), director, null))).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_device", Integer.class)).isZero();
    }

    @Test
    void lesAutresRolesNeVoientPasLesDepartsAValider() throws Exception {
        createUser(director, "EDUCATRICE", "lea.wema", "Educatrice-2026!", null);
        var educ = accessToken("lea.wema", "Educatrice-2026!");
        assertThat(status(getAs(educ, "/api/v1/attendance/departure-requests"))).isEqualTo(403);
        assertThat(status(perform(post("/api/v1/kiosk/departure-requests"), educ, Map.of("attendanceId", java.util.UUID.randomUUID(),
                "personName", "X", "relationship", "Y", "idChecked", false, "requestedBy", "Z")))).isEqualTo(403);
    }
}
