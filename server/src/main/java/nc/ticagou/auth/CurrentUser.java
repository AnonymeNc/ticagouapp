package nc.ticagou.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Utilisateur authentifié de la requête courante, lu depuis le JWT.
 * Toute lecture ou écriture métier est restreinte à {@link #structureId()}.
 */
public record CurrentUser(UUID userId, UUID structureId, Role role, String displayName, AuthMethod method) {

    static final String CLAIM_STRUCTURE = "sid";
    static final String CLAIM_ROLE = "role";
    static final String CLAIM_NAME = "name";
    static final String CLAIM_METHOD = "amr";
    static final String CLAIM_MFA_PENDING = "mfa_pending";

    public static Optional<CurrentUser> find() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token
                && !CurrentDevice.isDevice(token.getToken())) {
            return Optional.of(fromJwt(token.getToken()));
        }
        return Optional.empty();
    }

    public static CurrentUser get() {
        return find().orElseThrow(() -> new IllegalStateException("Aucun utilisateur authentifié"));
    }

    static CurrentUser fromJwt(Jwt jwt) {
        return new CurrentUser(
                UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString(CLAIM_STRUCTURE)),
                Role.valueOf(jwt.getClaimAsString(CLAIM_ROLE)),
                jwt.getClaimAsString(CLAIM_NAME),
                AuthMethod.valueOf(jwt.getClaimAsString(CLAIM_METHOD)));
    }
}
