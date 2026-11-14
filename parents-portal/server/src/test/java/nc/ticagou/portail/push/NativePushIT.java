package nc.ticagou.portail.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.sun.net.httpserver.HttpServer;

import jakarta.servlet.http.Cookie;
import nc.ticagou.portail.PortalTest;

/** Application Ti’Cagou Parents : nouveautés envoyées par APNs (ADR 0026), avec un faux service d'Apple. */
class NativePushIT extends PortalTest {

    record Push(String path, String topic, String body) {
    }

    static final List<Push> APPLE = new CopyOnWriteArrayList<>();
    static final HttpServer APNS;
    static final String KEY;

    static {
        try {
            APNS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            APNS.createContext("/", exchange -> {
                APPLE.add(new Push(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("apns-topic"),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                exchange.sendResponseHeaders(exchange.getRequestURI().getPath().endsWith("/desinstallee") ? 410 : 200, -1);
                exchange.close();
            });
            APNS.start();
            var generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KEY = Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void apns(DynamicPropertyRegistry registry) {
        registry.add("portal.push.apns-key", () -> KEY);
        registry.add("portal.push.apns-key-id", () -> "KEYID12345");
        registry.add("portal.push.apns-team-id", () -> "TEAM123456");
        registry.add("portal.push.apns-url", () -> "http://127.0.0.1:" + APNS.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        APNS.stop(0);
    }

    private static final String MARIE = "marie.wamytan@example.nc";
    private static final UUID FAMILY = UUID.fromString("0b9a3c1e-0000-4000-8000-000000000100");
    private static final UUID CHILD = UUID.fromString("0b9a3c1e-0000-4000-8000-000000001000");

    @Autowired
    private PushService push;

    @BeforeEach
    void vider() {
        APPLE.clear();
    }

    private MvcResult call(MockHttpServletRequestBuilder request, Cookie cookie, Object body) throws Exception {
        request.header("X-Ticagou-Portail", "1").cookie(cookie);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        }
        return mvc.perform(request).andReturn();
    }

    private static Map<String, Object> photo(long seq) {
        return Map.of("seq", seq, "type", "ENTRY", "id", UUID.randomUUID(), "deleted", false, "payload", Map.of(
                "familyIds", List.of(FAMILY), "childIds", List.of(CHILD), "day", "2026-11-02", "at", "2026-11-02T02:00:00Z",
                "kind", "PHOTO", "text", "Atelier peinture", "details", Map.of(),
                "photo", Base64.getEncoder().encodeToString(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
                "photoType", "image/jpeg"));
    }

    @Test
    void lApplicationRecoitLesNouveautesChoisiesSansContenuDeMessage() throws Exception {
        var creche = activate("Les P'tits Cagous");
        batch(creche, fixture("batch.json"));
        var cookie = login(MARIE);

        assertThat(status(call(post("/api/v1/me/push/native"), cookie,
                Map.of("platform", "IOS", "token", "iphone-de-marie", "categories", List.of("PHOTOS", "MESSAGES"))))).isEqualTo(204);
        call(post("/api/v1/me/push/native"), cookie, Map.of("platform", "IOS", "token", "desinstallee", "categories", List.of("PHOTOS")));
        assertThat(body(call(get("/api/v1/me/push/native?token=iphone-de-marie"), cookie, null)).get("categories"))
                .extracting(com.fasterxml.jackson.databind.JsonNode::asText).containsExactlyInAnyOrder("MESSAGES", "PHOTOS");

        batch(creche, Map.of("items", List.of(photo(20),
                Map.of("seq", 21, "type", "MESSAGE", "id", UUID.randomUUID(), "deleted", false, "payload",
                        Map.of("familyId", FAMILY, "author", "Sandrine", "body", "Léa a de la fièvre", "sentAt", "2026-11-02T03:00:00Z")),
                Map.of("seq", 22, "type", "INVOICE", "id", UUID.randomUUID(), "deleted", false, "payload", Map.of(
                        "familyId", FAMILY, "number", "FA-2026-0050", "kind", "FACTURE", "month", "2026-11", "issuedOn", "2026-11-30",
                        "total", 1000, "remaining", 1000, "lines", List.of())))));
        push.drain();

        var toMarie = APPLE.stream().filter(p -> p.path().equals("/3/device/iphone-de-marie")).toList();
        assertThat(toMarie).hasSize(2);
        assertThat(toMarie.getFirst().topic()).isEqualTo("nc.ticagou.parents");
        assertThat(toMarie.getFirst().body()).contains("Nouvelle photo de Léa").contains("Les P'tits Cagous").contains("/album");
        assertThat(toMarie.get(1).body()).contains("Nouveau message de la crèche").doesNotContain("fièvre");
        // Facture : catégorie non choisie. Application désinstallée (410) : jeton effacé.
        assertThat(APPLE).noneMatch(p -> p.body().contains("FA-2026-0050"));
        assertThat(jdbc.queryForList("SELECT token FROM native_device", String.class)).containsExactly("iphone-de-marie");

        // Désinscription ; puis famille retirée par la crèche : plus aucun appareil.
        assertThat(status(call(post("/api/v1/me/push/native/unregister"), cookie, Map.of("token", "iphone-de-marie")))).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM native_device", Long.class)).isZero();
        call(post("/api/v1/me/push/native"), cookie, Map.of("platform", "ANDROID", "token", "android-de-marie", "categories", List.of("PHOTOS")));
        batch(creche, Map.of("items", List.of(Map.of("seq", 30, "type", "PURGE_FAMILY", "id", FAMILY, "deleted", true))));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM native_device", Long.class)).isZero();
    }
}
