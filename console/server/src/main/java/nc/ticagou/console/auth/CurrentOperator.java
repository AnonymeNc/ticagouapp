package nc.ticagou.console.auth;

import java.io.Serializable;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.context.SecurityContextHolder;

import nc.ticagou.console.common.ApiException;

/** Opérateur de la session en cours. */
public final class CurrentOperator {

    public record Principal(UUID id, String login, String displayName) implements Serializable {
    }

    private CurrentOperator() {
    }

    public static Optional<Principal> find() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof Principal p ? Optional.of(p) : Optional.empty();
    }

    public static Principal get() {
        return find().orElseThrow(() -> ApiException.unauthorized("unauthenticated", "Connectez-vous."));
    }
}
