package nc.ticagou.console.auth;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Attributs de la session HTTP de la console. */
final class SessionKeys {

    /** Mot de passe accepté, code TOTP attendu ; {@code enrollSecret} : secret proposé à la première connexion. */
    record Pending(UUID operatorId, Instant at, String enrollSecret) implements Serializable {
    }

    static final String PENDING = "console.pending";
    static final String LOGIN_AT = "console.loginAt";
    static final String CONFIRMED_AT = "console.confirmedAt";

    /** Délai pour saisir le code après le mot de passe. */
    static final Duration PENDING_TTL = Duration.ofMinutes(5);
    /** Durée de validité d'un code redemandé pour une action sensible. */
    static final Duration CONFIRMATION_TTL = Duration.ofMinutes(5);

    private SessionKeys() {
    }
}
