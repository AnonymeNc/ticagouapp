package nc.ticagou.portail.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * Jeton de média de l'application Ti’Cagou Parents (ADR 0024) : dans une vue web, une image, une vidéo ou un lien ne
 * peut pas porter l'en-tête {@code Authorization}. L'application ajoute donc ce jeton aux adresses des fichiers. Il ne
 * donne que la lecture des fichiers et photos de l'adresse du parent, une heure ; il ne vaut pas session. La clé change à
 * chaque démarrage du portail : l'application en redemande un.
 */
@Component
public class MediaTokens {

    static final Duration VALIDITY = Duration.ofHours(1);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final Clock clock;
    private final byte[] key = new byte[32];

    public MediaTokens(Clock clock) {
        this.clock = clock;
        new SecureRandom().nextBytes(key);
    }

    public record Issued(String token, Instant expiresAt) {
    }

    public Issued issue(String email) {
        var expires = clock.instant().plus(VALIDITY);
        var body = B64.encodeToString((email.toLowerCase() + "|" + expires.getEpochSecond()).getBytes(StandardCharsets.UTF_8));
        return new Issued(body + "." + B64.encodeToString(mac(body)), expires);
    }

    /** Adresse du parent si le jeton est intact et en cours de validité. */
    public Optional<String> verify(String token) {
        if (token == null) {
            return Optional.empty();
        }
        var parts = token.split("\\.");
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            if (!MessageDigest.isEqual(mac(parts[0]), Base64.getUrlDecoder().decode(parts[1]))) {
                return Optional.empty();
            }
            var fields = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8).split("\\|");
            if (fields.length != 2 || Instant.ofEpochSecond(Long.parseLong(fields[1])).isBefore(clock.instant())) {
                return Optional.empty();
            }
            return Optional.of(fields[0]);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private byte[] mac(String body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
