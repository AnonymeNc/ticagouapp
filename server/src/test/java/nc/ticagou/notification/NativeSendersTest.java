package nc.ticagou.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import nc.ticagou.notification.NativePush.Message;
import nc.ticagou.notification.NativePush.Platform;
import nc.ticagou.notification.NativePush.Result;
import nc.ticagou.notification.NativePush.Target;

/**
 * Envois APNs et FCM (ADR 0026) contre un faux service HTTP : jetons signés vérifiés avec la clé publique, en-têtes,
 * contenu, jetons d'appareil refusés. Aucun appel à Apple ni à Google.
 */
class NativeSendersTest {

    record Received(String method, String path, Map<String, List<String>> headers, String body) {
    }

    static final ObjectMapper JSON = new ObjectMapper();
    static final Message MESSAGE = new Message("Départ à valider : Lila G.", "Ouvrez Ti’Cagou pour autoriser ou refuser.",
            "/presences", "DEPART", Duration.ofHours(2), "depart-1");

    HttpServer server;
    final List<Received> received = new ArrayList<>();
    final AtomicInteger status = new AtomicInteger(200);
    String responseBody = "{}";
    final Clock clock = Clock.fixed(Instant.parse("2026-11-16T22:00:00Z"), ZoneId.of("Pacific/Noumea"));

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders(), body));
            var reply = exchange.getRequestURI().getPath().endsWith("/token")
                    ? "{\"access_token\":\"ya29.jeton\",\"expires_in\":3599}" : responseBody;
            var bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(exchange.getRequestURI().getPath().endsWith("/token") ? 200 : status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    static String pkcs8(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
    }

    static JsonNode part(String jwt, int index) throws IOException {
        return JSON.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[index]));
    }

    static boolean verify(String jwt, KeyPair pair, String algorithm) throws Exception {
        var parts = jwt.split("\\.");
        var signature = Signature.getInstance(algorithm);
        signature.initVerify(pair.getPublic());
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        return signature.verify(Base64.getUrlDecoder().decode(parts[2]));
    }

    @Test
    void apnsEnvoieUnJetonEs256VerifiableEtLesEnTetesDApple() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = generator.generateKeyPair();
        var apns = new ApnsSender(new PushProperties(pkcs8(pair), "ABC123DEFG", "TEAM456789", true, url(), "", "", Duration.ofMinutes(5),
                Duration.ofMinutes(30)), JSON, clock);

        var result = apns.send(MESSAGE, new Target("a1b2c3", Platform.IOS, "nc.ticagou.direction"));

        assertThat(result).isEqualTo(Result.OK);
        var request = received.getFirst();
        assertThat(request.path()).isEqualTo("/3/device/a1b2c3");
        var jwt = request.headers().get("Authorization").getFirst().substring("bearer ".length());
        assertThat(part(jwt, 0).get("alg").asText()).isEqualTo("ES256");
        assertThat(part(jwt, 0).get("kid").asText()).isEqualTo("ABC123DEFG");
        assertThat(part(jwt, 1).get("iss").asText()).isEqualTo("TEAM456789");
        assertThat(part(jwt, 1).get("iat").asLong()).isEqualTo(clock.instant().getEpochSecond());
        // Signature JOSE : 64 octets r‖s, vérifiable au format P1363.
        assertThat(Base64.getUrlDecoder().decode(jwt.split("\\.")[2])).hasSize(64);
        assertThat(verify(jwt, pair, "SHA256withECDSAinP1363Format")).isTrue();
        assertThat(request.headers().get("Apns-topic")).containsExactly("nc.ticagou.direction");
        assertThat(request.headers().get("Apns-push-type")).containsExactly("alert");
        assertThat(request.headers().get("Apns-collapse-id")).containsExactly("depart-1");
        var payload = JSON.readTree(request.body());
        assertThat(payload.at("/aps/alert/title").asText()).isEqualTo("Départ à valider : Lila G.");
        assertThat(payload.get("link").asText()).isEqualTo("/presences");

        // Le jeton du fournisseur est réutilisé (Apple refuse qu'il change trop souvent).
        apns.send(MESSAGE, new Target("d4e5", Platform.IOS, "nc.ticagou.direction"));
        assertThat(received.get(1).headers().get("Authorization")).isEqualTo(request.headers().get("Authorization"));
    }

    @Test
    void apnsSignaleUnAppareilDesinscrit() {
        assertThat(ApnsSender.result(410, "{\"reason\":\"Unregistered\"}")).isEqualTo(Result.INVALID);
        assertThat(ApnsSender.result(400, "{\"reason\":\"BadDeviceToken\"}")).isEqualTo(Result.INVALID);
        assertThat(ApnsSender.result(403, "{\"reason\":\"ExpiredProviderToken\"}")).isEqualTo(Result.FAILED);
        assertThat(ApnsSender.result(500, "")).isEqualTo(Result.FAILED);
    }

    @Test
    void fcmObtientUnJetonOauthRs256PuisEnvoieLeMessage() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        var account = JSON.writeValueAsString(Map.of("type", "service_account", "project_id", "ticagou-test",
                "client_email", "notifications@ticagou-test.iam.gserviceaccount.com", "private_key", pem,
                "token_uri", "https://oauth2.googleapis.com/token"));
        var fcm = new FcmSender(new PushProperties("", "", "", true, "", Base64.getEncoder().encodeToString(account.getBytes()), url(),
                Duration.ofMinutes(5), Duration.ofMinutes(30)), JSON, clock);

        assertThat(fcm.send(MESSAGE, new Target("fcm-jeton", Platform.ANDROID, "nc.ticagou.direction"))).isEqualTo(Result.OK);
        assertThat(fcm.send(MESSAGE, new Target("fcm-jeton-2", Platform.ANDROID, "nc.ticagou.direction"))).isEqualTo(Result.OK);

        var token = received.getFirst();
        assertThat(token.path()).isEqualTo("/token");
        var assertion = token.body().replaceAll(".*assertion=", "");
        assertThat(verify(assertion, pair, "SHA256withRSA")).isTrue();
        assertThat(part(assertion, 1).get("scope").asText()).isEqualTo(FcmSender.SCOPE);
        assertThat(part(assertion, 1).get("iss").asText()).isEqualTo("notifications@ticagou-test.iam.gserviceaccount.com");
        // Un seul jeton OAuth pour deux envois.
        assertThat(received.stream().filter(r -> r.path().equals("/token"))).hasSize(1);

        var send = received.get(1);
        assertThat(send.path()).isEqualTo("/v1/projects/ticagou-test/messages:send");
        assertThat(send.headers().get("Authorization")).containsExactly("Bearer ya29.jeton");
        var message = JSON.readTree(send.body()).get("message");
        assertThat(message.get("token").asText()).isEqualTo("fcm-jeton");
        assertThat(message.at("/notification/body").asText()).isEqualTo("Ouvrez Ti’Cagou pour autoriser ou refuser.");
        assertThat(message.at("/android/ttl").asText()).isEqualTo("7200s");
    }

    @Test
    void fcmSignaleUneApplicationDesinstallee() {
        assertThat(FcmSender.result(404, "{\"error\":{\"status\":\"NOT_FOUND\"}}")).isEqualTo(Result.INVALID);
        assertThat(FcmSender.result(400, "{\"error\":{\"details\":[{\"errorCode\":\"UNREGISTERED\"}]}}")).isEqualTo(Result.INVALID);
        assertThat(FcmSender.result(503, "{}")).isEqualTo(Result.FAILED);
    }

    @Test
    void sansSecretLesEnvoisNatifsSontDesactives() {
        var none = new PushProperties("", "", "", true, "", "", "", Duration.ofMinutes(5), Duration.ofMinutes(30));
        assertThat(new ApnsSender(none, JSON, clock).enabled()).isFalse();
        assertThat(new FcmSender(none, JSON, clock).enabled()).isFalse();
        assertThat(new ApnsSender(none, JSON, clock).send(MESSAGE, new Target("x", Platform.IOS, "b"))).isEqualTo(Result.FAILED);
    }
}
