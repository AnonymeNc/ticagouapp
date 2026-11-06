package nc.ticagou.auth;

import java.time.Clock;
import java.time.Duration;

import org.springframework.stereotype.Component;

import nc.ticagou.common.AddressThrottle;

/**
 * Limite les connexions manquées par adresse : le blocage par compte ne suffit pas sur internet, où un inconnu peut
 * essayer plusieurs comptes. Le seuil reste large : toute une crèche sort sur la même adresse.
 */
@Component
public class LoginThrottle extends AddressThrottle {

    static final int MAX_FAILURES = 20;
    static final Duration WINDOW = Duration.ofMinutes(10);

    public LoginThrottle(Clock clock) {
        super(clock, MAX_FAILURES, WINDOW);
    }
}
