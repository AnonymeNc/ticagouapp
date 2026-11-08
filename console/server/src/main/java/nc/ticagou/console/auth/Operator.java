package nc.ticagou.console.auth;

import java.time.Instant;
import java.util.UUID;

/** Compte de la console (table {@code operator}). */
record Operator(UUID id, String login, String displayName, String passwordHash, String totpSecret, Instant totpEnabledAt,
                Long totpLastStep, Instant lockedUntil, Instant disabledAt) {

    boolean lockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    boolean totpEnabled() {
        return totpEnabledAt != null && totpSecret != null;
    }
}
