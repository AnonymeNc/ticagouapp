package nc.ticagou.portail.push.natif;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import nc.ticagou.portail.push.natif.NativePush.Message;
import nc.ticagou.portail.push.natif.NativePush.Result;
import nc.ticagou.portail.push.natif.NativePush.Target;

/* Copie de server/…/notification/FcmSender.java (ADR 0026) : le portail est un projet séparé. Garder les deux identiques. */
/**
 * Firebase Cloud Messaging HTTP v1 (ADR 0026) : jeton OAuth 2 obtenu avec un JWT RS256 signé par la clé du compte de
 * service, gardé jusqu'à son échéance, puis envoi du message. Aucune bibliothèque Google.
 */
@Component
public class FcmSender implements NativePush.Sender {

    private static final Logger LOG = LoggerFactory.getLogger(FcmSender.class);
    static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    private final PushProperties properties;
    private final ObjectMapper json;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private ServiceAccount account;
    private String accessToken;
    private Instant accessTokenUntil = Instant.EPOCH;

    record ServiceAccount(String projectId, String clientEmail, PrivateKey key, String tokenUri) {
    }

    public FcmSender(PushProperties properties, ObjectMapper json, Clock clock) {
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public boolean enabled() {
        return properties.fcmEnabled();
    }

    synchronized ServiceAccount account() {
        if (account == null) {
            var text = properties.fcmServiceAccount().strip();
            if (!text.startsWith("{")) {
                text = new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
            }
            try {
                JsonNode node = json.readTree(text);
                var tokenUri = node.path("token_uri").asText("https://oauth2.googleapis.com/token");
                if (!properties.fcmUrl().isBlank()) {
                    tokenUri = properties.fcmUrl().replaceAll("/$", "") + "/token";
                }
                account = new ServiceAccount(node.path("project_id").asText(), node.path("client_email").asText(),
                        JwtSigner.privateKey(node.path("private_key").asText(), "RSA"), tokenUri);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Compte de service FCM illisible", e);
            }
        }
        return account;
    }

    String messagesUrl() {
        var base = properties.fcmUrl().isBlank() ? "https://fcm.googleapis.com" : properties.fcmUrl().replaceAll("/$", "");
        return base + "/v1/projects/" + account().projectId() + "/messages:send";
    }

    /** Assertion RS256 du compte de service (RFC 7523), valable une heure. */
    String assertion() {
        var now = clock.instant().getEpochSecond();
        var claims = Map.of("iss", account().clientEmail(), "scope", SCOPE, "aud", account().tokenUri(),
                "iat", now, "exp", now + 3600);
        try {
            return JwtSigner.rs256("{\"alg\":\"RS256\",\"typ\":\"JWT\"}", json.writeValueAsString(claims), account().key());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    synchronized String accessToken() throws IOException, InterruptedException {
        if (accessToken != null && clock.instant().isBefore(accessTokenUntil)) {
            return accessToken;
        }
        var form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + assertion();
        var response = http.send(HttpRequest.newBuilder(URI.create(account().tokenUri()))
                .timeout(Duration.ofSeconds(15))
                .header("content-type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("jeton OAuth refusé : " + response.statusCode() + " " + response.body());
        }
        var body = json.readTree(response.body());
        accessToken = body.path("access_token").asText();
        // Une minute de marge : le jeton n'expire jamais pendant un envoi.
        accessTokenUntil = clock.instant().plusSeconds(Math.max(60, body.path("expires_in").asLong(3600) - 60));
        return accessToken;
    }

    String payload(Message message, Target target) {
        try {
            return json.writeValueAsString(Map.of("message", Map.of(
                    "token", target.token(),
                    "notification", Map.of("title", message.title(), "body", message.body()),
                    "data", Map.of("link", message.link() == null ? "/" : message.link(), "category", message.category()),
                    "android", Map.of("priority", "high", "ttl", message.ttl().toSeconds() + "s",
                            "collapse_key", message.collapse() == null ? message.category() : message.collapse()))));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Result send(Message message, Target target) {
        if (!enabled()) {
            return Result.FAILED;
        }
        try {
            var response = http.send(HttpRequest.newBuilder(URI.create(messagesUrl()))
                    .timeout(Duration.ofSeconds(15))
                    .header("authorization", "Bearer " + accessToken())
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload(message, target))).build(), HttpResponse.BodyHandlers.ofString());
            return result(response.statusCode(), response.body());
        } catch (IOException e) {
            LOG.warn("FCM injoignable : {}", e.getMessage());
            return Result.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.FAILED;
        }
    }

    /** 404 / UNREGISTERED : application désinstallée ; INVALID_ARGUMENT sur le jeton : jeton à oublier. */
    static Result result(int status, String body) {
        if (status == 200) {
            return Result.OK;
        }
        var text = body == null ? "" : body;
        if (status == 404 || text.contains("UNREGISTERED")
                || (status == 400 && text.contains("INVALID_ARGUMENT") && text.contains("token"))) {
            return Result.INVALID;
        }
        LOG.warn("FCM a refusé la notification : {} {}", status, text);
        return Result.FAILED;
    }
}
