package nc.ticagou.console.auth;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpSession;
import nc.ticagou.console.common.ApiException;

/**
 * Actions sensibles (valider une facture, émettre un avoir, confirmer un ordre) : un code TOTP saisi depuis moins de
 * 5 minutes est exigé (ADR 0017). Sinon 403 {@code confirmation_required} ; l'interface demande le code puis rejoue.
 */
@Component
public class SensitiveAction {

    private final Clock clock;

    public SensitiveAction(Clock clock) {
        this.clock = clock;
    }

    public void require(HttpSession session) {
        var confirmedAt = session == null ? null : (Instant) session.getAttribute(SessionKeys.CONFIRMED_AT);
        if (!isFresh(confirmedAt, clock.instant())) {
            throw ApiException.forbidden("confirmation_required", "Saisissez votre code pour confirmer cette action.");
        }
    }

    static boolean isFresh(Instant confirmedAt, Instant now) {
        return confirmedAt != null && !confirmedAt.plus(SessionKeys.CONFIRMATION_TTL).isBefore(now);
    }
}
