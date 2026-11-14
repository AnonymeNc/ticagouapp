package nc.ticagou.portail.push.natif;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import nc.ticagou.portail.push.natif.NativePush.Message;
import nc.ticagou.portail.push.natif.NativePush.Result;
import nc.ticagou.portail.push.natif.NativePush.Target;

/* Copie de server/…/notification/ApnsSender.java (ADR 0026) : le portail est un projet séparé. Garder les deux identiques. */
/**
 * Apple Push Notification service (ADR 0026) : HTTP/2, jeton du fournisseur ES256 signé avec la clé .p8 de l'éditeur,
 * renouvelé toutes les 50 minutes (Apple le refuse après une heure, et s'il change plus d'une fois toutes les 20 minutes).
 */
@Component
public class ApnsSender implements NativePush.Sender {

    private static final Logger LOG = LoggerFactory.getLogger(ApnsSender.class);
    static final Duration TOKEN_LIFETIME = Duration.ofMinutes(50);

    private final PushProperties properties;
    private final ObjectMapper json;
    private final Clock clock;
    private final HttpClient http;
    private PrivateKey key;
    private String providerToken;
    private Instant providerTokenAt;

    public ApnsSender(PushProperties properties, ObjectMapper json, Clock clock) {
        this.properties = properties;
        this.json = json;
        this.clock = clock;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public boolean enabled() {
        return properties.apnsEnabled();
    }

    String baseUrl() {
        if (!properties.apnsUrl().isBlank()) {
            return properties.apnsUrl().replaceAll("/$", "");
        }
        return properties.apnsProduction() ? "https://api.push.apple.com" : "https://api.sandbox.push.apple.com";
    }

    /** Jeton du fournisseur : {"alg":"ES256","kid":…} {"iss": équipe, "iat": …}. */
    synchronized String providerToken() {
        var now = clock.instant();
        if (providerToken == null || providerTokenAt.plus(TOKEN_LIFETIME).isBefore(now)) {
            if (key == null) {
                key = JwtSigner.privateKey(properties.apnsKey(), "EC");
            }
            providerToken = JwtSigner.es256(
                    "{\"alg\":\"ES256\",\"kid\":\"" + properties.apnsKeyId() + "\"}",
                    "{\"iss\":\"" + properties.apnsTeamId() + "\",\"iat\":" + now.getEpochSecond() + "}", key);
            providerTokenAt = now;
        }
        return providerToken;
    }

    String payload(Message message) {
        try {
            return json.writeValueAsString(Map.of(
                    "aps", Map.of("alert", Map.of("title", message.title(), "body", message.body()), "sound", "default",
                            "thread-id", message.category()),
                    "link", message.link() == null ? "/" : message.link()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Result send(Message message, Target target) {
        if (!enabled()) {
            return Result.FAILED;
        }
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + "/3/device/" + target.token()))
                .timeout(Duration.ofSeconds(15))
                .header("authorization", "bearer " + providerToken())
                .header("apns-topic", target.bundleId())
                .header("apns-push-type", "alert")
                .header("apns-priority", "10")
                .header("apns-expiration", String.valueOf(clock.instant().plus(message.ttl()).getEpochSecond()))
                .header("apns-collapse-id", message.collapse() == null ? message.category() : message.collapse())
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload(message)))
                .build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return result(response.statusCode(), response.body());
        } catch (IOException e) {
            LOG.warn("APNs injoignable : {}", e.getMessage());
            return Result.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.FAILED;
        }
    }

    /** 410 : appareil désinscrit ; 400 BadDeviceToken / DeviceTokenNotForTopic : jeton à oublier. */
    static Result result(int status, String body) {
        if (status == 200) {
            return Result.OK;
        }
        if (status == 410 || (status == 400 && body != null
                && (body.contains("BadDeviceToken") || body.contains("DeviceTokenNotForTopic") || body.contains("Unregistered")))) {
            return Result.INVALID;
        }
        LOG.warn("APNs a refusé la notification : {} {}", status, body);
        return Result.FAILED;
    }
}
