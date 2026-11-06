package nc.ticagou.portail.sync;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import nc.ticagou.portail.common.ApiException;
import nc.ticagou.portail.common.Hashes;

/**
 * Authentifie les requêtes des serveurs de crèche (ADR 0010) : signature Ed25519 de
 * {@code MÉTHODE\nchemin?requête\ndate\nSHA-256 du corps} avec la clé publique enregistrée à l'activation, date à
 * moins de 5 minutes, et une même signature n'est acceptée qu'une fois (rejeu refusé).
 */
@Component
public class SyncAuthenticator {

    public static final String HEADER_STRUCTURE = "X-Ticagou-Structure";
    public static final String HEADER_DATE = "X-Ticagou-Date";
    public static final String HEADER_SIGNATURE = "X-Ticagou-Signature";
    static final Duration MAX_SKEW = Duration.ofMinutes(5);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Map<String, Instant> seen = new ConcurrentHashMap<>();

    public SyncAuthenticator(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Renvoie l'identifiant de la structure authentifiée, ou lève 401. */
    public UUID authenticate(String method, String pathAndQuery, String structureHeader, String dateHeader,
                             String signature, byte[] body) {
        return authenticate("SELECT public_key FROM portal_structure WHERE id = ? AND suspended_at IS NULL", method,
                pathAndQuery, structureHeader, dateHeader, signature, body);
    }

    /** Serveur de crèche supervisé (ADR 0012) : même signature, clé enregistrée à l'activation de la supervision. */
    public UUID authenticateSite(String method, String pathAndQuery, String siteHeader, String dateHeader,
                                 String signature, byte[] body) {
        return authenticate("SELECT public_key FROM supervision_site WHERE id = ? AND suspended_at IS NULL", method,
                pathAndQuery, siteHeader, dateHeader, signature, body);
    }

    private UUID authenticate(String keyQuery, String method, String pathAndQuery, String structureHeader,
                              String dateHeader, String signature, byte[] body) {
        UUID structureId;
        Instant date;
        try {
            structureId = UUID.fromString(structureHeader);
            date = Instant.parse(dateHeader);
        } catch (IllegalArgumentException | NullPointerException | DateTimeParseException e) {
            throw refused();
        }
        var now = clock.instant();
        if (Duration.between(date, now).abs().compareTo(MAX_SKEW) > 0) {
            throw ApiException.unauthorized("clock_skew", "Date de la requête trop éloignée : vérifiez l’heure du serveur de la crèche.");
        }
        var keys = jdbc.query(keyQuery, (rs, i) -> rs.getBytes(1), structureId);
        if (keys.isEmpty() || signature == null || !verify(keys.getFirst(), method, pathAndQuery, date, body, signature)) {
            throw refused();
        }
        purgeSeen(now);
        if (seen.putIfAbsent(signature, date.plus(MAX_SKEW)) != null) {
            throw ApiException.unauthorized("replay", "Requête déjà reçue.");
        }
        return structureId;
    }

    static String canonical(String method, String pathAndQuery, Instant date, byte[] body) {
        return method.toUpperCase() + "\n" + pathAndQuery + "\n" + date + "\n" + Hashes.sha256(body == null ? new byte[0] : body);
    }

    static boolean verify(byte[] publicKey, String method, String pathAndQuery, Instant date, byte[] body, String signature) {
        try {
            var key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(publicKey));
            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(canonical(method, pathAndQuery, date, body).getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private void purgeSeen(Instant now) {
        seen.entrySet().removeIf(e -> e.getValue().isBefore(now));
    }

    private static ApiException refused() {
        return ApiException.unauthorized("bad_signature", "Signature invalide ou structure inconnue.");
    }
}
