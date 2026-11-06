package nc.ticagou.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Tablette authentifiée par son jeton d'appareil (ADR 0005) : rôle {@code APPAREIL}, sans utilisateur.
 * Sert à la file d'attente hors connexion, au pointage du personnel et à la connexion par PIN.
 */
public record CurrentDevice(UUID deviceId, UUID structureId, String name) {

    static final String CLAIM_TYPE = "typ";
    static final String TYPE_DEVICE = "device";
    public static final String ROLE = "APPAREIL";

    public static Optional<CurrentDevice> find() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token
                && isDevice(token.getToken())) {
            var jwt = token.getToken();
            return Optional.of(new CurrentDevice(UUID.fromString(jwt.getSubject()),
                    UUID.fromString(jwt.getClaimAsString(CurrentUser.CLAIM_STRUCTURE)),
                    jwt.getClaimAsString(CurrentUser.CLAIM_NAME)));
        }
        return Optional.empty();
    }

    public static CurrentDevice get() {
        return find().orElseThrow(() -> new IllegalStateException("Aucune tablette authentifiée"));
    }

    static boolean isDevice(Jwt jwt) {
        return TYPE_DEVICE.equals(jwt.getClaimAsString(CLAIM_TYPE));
    }
}
