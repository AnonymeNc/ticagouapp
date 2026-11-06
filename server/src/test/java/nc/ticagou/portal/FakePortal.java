package nc.ticagou.portal;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Faux portail parents pour les tests : vérifie les signatures comme le vrai, garde les lots reçus et sert une boîte de
 * réception préparée par le test.
 */
final class FakePortal implements AutoCloseable {

    static final String CODE = "K7QM-2WXP-9HTD";

    final UUID structureId = UUID.randomUUID();
    final List<JsonNode> batches = new CopyOnWriteArrayList<>();
    final List<ObjectNode> inbox = new CopyOnWriteArrayList<>();
    final List<Long> acks = new CopyOnWriteArrayList<>();
    final List<String> rejected = new CopyOnWriteArrayList<>();
    volatile boolean down;
    volatile PublicKey publicKey;
    volatile String activatedName;

    private final HttpServer server;
    private final ObjectMapper json;

    FakePortal(ObjectMapper json) throws IOException {
        this.json = json;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sync/v1/", this::handle);
        server.start();
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** Éléments reçus, à plat, dans l'ordre. */
    List<JsonNode> items() {
        var all = new ArrayList<JsonNode>();
        batches.forEach(b -> b.get("items").forEach(all::add));
        return all;
    }

    String received() {
        return batches.toString();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            var body = exchange.getRequestBody().readAllBytes();
            var path = exchange.getRequestURI().getRawPath();
            var query = exchange.getRequestURI().getRawQuery();
            var pathAndQuery = query == null ? path : path + "?" + query;
            if (down) {
                reply(exchange, 503, "{\"detail\":\"Maintenance\"}");
                return;
            }
            if (path.equals("/sync/v1/activate")) {
                var request = json.readTree(body);
                if (!CODE.equals(request.get("activationCode").asText())) {
                    reply(exchange, 403, "{\"detail\":\"Code d’activation inconnu ou déjà utilisé\"}");
                    return;
                }
                publicKey = KeyFactory.getInstance("Ed25519")
                        .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(request.get("publicKey").asText())));
                activatedName = request.get("structureName").asText();
                reply(exchange, 200, "{\"structureId\":\"" + structureId + "\"}");
                return;
            }
            var headers = exchange.getRequestHeaders();
            var date = Instant.parse(headers.getFirst(PortalSigner.HEADER_DATE));
            if (!structureId.toString().equals(headers.getFirst(PortalSigner.HEADER_STRUCTURE))
                    || Duration.between(date, Instant.now()).abs().toMinutes() > 5
                    || !PortalSigner.verify(publicKey, exchange.getRequestMethod(), pathAndQuery, date, body,
                    headers.getFirst(PortalSigner.HEADER_SIGNATURE))) {
                rejected.add(pathAndQuery);
                reply(exchange, 401, "{\"detail\":\"Signature invalide\"}");
                return;
            }
            switch (path) {
                case "/sync/v1/batch" -> {
                    var batch = json.readTree(body);
                    batches.add(batch);
                    long max = 0;
                    for (var item : batch.get("items")) {
                        max = Math.max(max, item.get("seq").asLong());
                    }
                    reply(exchange, 200, "{\"acceptedUpTo\":" + max + "}");
                }
                case "/sync/v1/inbox" -> {
                    long after = Long.parseLong(query.substring("after=".length()));
                    var items = json.createArrayNode();
                    inbox.stream().filter(i -> i.get("seq").asLong() > after).forEach(items::add);
                    reply(exchange, 200, json.createObjectNode().set("items", items).toString());
                }
                case "/sync/v1/inbox/ack" -> {
                    acks.add(json.readTree(body).get("upTo").asLong());
                    reply(exchange, 204, null);
                }
                default -> reply(exchange, 404, "{\"detail\":\"Inconnu\"}");
            }
        } catch (Exception e) {
            reply(exchange, 500, "{\"detail\":\"" + e.getClass().getSimpleName() + "\"}");
        } finally {
            exchange.close();
        }
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
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
