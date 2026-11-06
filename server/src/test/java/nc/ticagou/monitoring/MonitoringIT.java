package nc.ticagou.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import nc.ticagou.IntegrationTest;
import nc.ticagou.portal.PortalSigner;

/** Supervision (ADR 0012) : activation par la direction, état signé sans donnée personnelle, reprise après échec. */
class MonitoringIT extends IntegrationTest {

    /** Faux service de supervision : enregistre la clé à l'activation, vérifie la signature des états. */
    static final class FakeSupervision implements AutoCloseable {
        final UUID siteId = UUID.randomUUID();
        final List<JsonNode> statuses = new CopyOnWriteArrayList<>();
        final List<String> signatureErrors = new CopyOnWriteArrayList<>();
        volatile PublicKey key;
        volatile boolean down;
        final HttpServer server;
        final com.fasterxml.jackson.databind.ObjectMapper json;

        FakeSupervision(com.fasterxml.jackson.databind.ObjectMapper json) throws IOException {
            this.json = json;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/supervision/v1/", this::handle);
            server.start();
        }

        String url() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        void handle(HttpExchange exchange) throws IOException {
            try {
                var body = exchange.getRequestBody().readAllBytes();
                var path = exchange.getRequestURI().getRawPath();
                if (down) {
                    reply(exchange, 503, "{\"detail\":\"Maintenance\"}");
                } else if (path.endsWith("/activate")) {
                    var request = json.readTree(body);
                    if (!"S123-4567-89AB".equals(request.get("activationCode").asText())) {
                        reply(exchange, 403, "{\"detail\":\"Code de supervision inconnu, expiré ou déjà utilisé.\"}");
                        return;
                    }
                    key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
                            Base64.getDecoder().decode(request.get("publicKey").asText())));
                    reply(exchange, 200, "{\"siteId\":\"" + siteId + "\"}");
                } else {
                    var headers = exchange.getRequestHeaders();
                    var ok = siteId.toString().equals(headers.getFirst(PortalSigner.HEADER_STRUCTURE))
                            && PortalSigner.verify(key, "POST", path, Instant.parse(headers.getFirst(PortalSigner.HEADER_DATE)),
                                    body, headers.getFirst(PortalSigner.HEADER_SIGNATURE));
                    if (!ok) {
                        signatureErrors.add(path);
                        reply(exchange, 401, "{\"detail\":\"Signature invalide\"}");
                        return;
                    }
                    statuses.add(json.readTree(body));
                    exchange.sendResponseHeaders(204, -1);
                }
            } catch (Exception e) {
                reply(exchange, 500, "{}");
            } finally {
                exchange.close();
            }
        }

        static void reply(HttpExchange exchange, int status, String body) throws IOException {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    FakeSupervision service;
    String director;

    @BeforeEach
    void installer() throws Exception {
        jdbc.update("DELETE FROM monitoring_settings");
        service = new FakeSupervision(json);
        director = install();
        createChild(director, "Noa", "Wahéo", "2024-09-10", sectionId(director, "Moyens"));
    }

    @AfterEach
    void arreter() {
        service.close();
    }

    @Test
    void activationEtEtatSigneSansDonneePersonnelle() throws Exception {
        var refused = perform(post("/api/v1/monitoring/activate"), director, Map.of("serviceUrl", service.url(), "activationCode", "FAUX"));
        assertThat(body(refused).get("code").asText()).isEqualTo("monitoring_activation");

        var activated = body(perform(post("/api/v1/monitoring/activate"), director,
                Map.of("serviceUrl", service.url(), "activationCode", "S123-4567-89AB")));
        assertThat(activated.get("state").asText()).isEqualTo("ACTIF");
        assertThat(activated.get("lastSuccessAt").isNull()).as("premier envoi immédiat").isFalse();
        assertThat(service.signatureErrors).isEmpty();
        assertThat(auditCount("MONITORING_ACTIVATED")).isEqualTo(1);

        var status = service.statuses.getFirst();
        var fields = new java.util.HashSet<String>();
        status.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("version", "database", "backup", "disk", "license", "tablets", "portal",
                "attendanceToReview");
        assertThat(status.at("/license/state").asText()).isEqualTo("EVALUATION");
        assertThat(status.at("/backup/enabled").asBoolean()).isTrue();
        assertThat(status.at("/disk/dataFreeBytes").asLong()).isPositive();
        var text = status.toString();
        assertThat(text).doesNotContain("Noa", "Wahéo", "Kaloï", "Niaouli", "nathalie");
    }

    @Test
    void echecPuisReprise() throws Exception {
        perform(post("/api/v1/monitoring/activate"), director, Map.of("serviceUrl", service.url(), "activationCode", "S123-4567-89AB"));
        service.down = true;
        var failed = body(perform(post("/api/v1/monitoring/send"), director, null));
        assertThat(failed.get("failures").asInt()).isEqualTo(1);
        assertThat(failed.get("lastError").asText()).contains("Maintenance");
        service.down = false;
        var ok = body(perform(post("/api/v1/monitoring/send"), director, null));
        assertThat(ok.get("failures").asInt()).isZero();
        assertThat(service.statuses).hasSize(2);

        var off = body(perform(post("/api/v1/monitoring/deactivate"), director, null));
        assertThat(off.get("state").asText()).isEqualTo("DESACTIVE");
        perform(post("/api/v1/monitoring/send"), director, null);
        assertThat(service.statuses).as("plus rien n'est envoyé").hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM server_secret WHERE name LIKE 'monitor-%'", Integer.class)).isZero();
    }

    @Test
    void attenteQuiDoubleApresLesEchecs() {
        assertThat(MonitoringService.delay(0)).hasMinutes(15);
        assertThat(MonitoringService.delay(1)).hasMinutes(15);
        assertThat(MonitoringService.delay(2)).hasMinutes(30);
        assertThat(MonitoringService.delay(9)).hasMinutes(60);
    }

    @Test
    void reserveeALaDirection() throws Exception {
        createUser(director, "COMPTABILITE", "compta", "Comptable-2026!", null);
        assertThat(status(getAs(accessToken("compta", "Comptable-2026!"), "/api/v1/monitoring"))).isEqualTo(403);
    }

}
