package nc.ticagou.device;

import java.time.Clock;
import java.time.Duration;

import org.springframework.stereotype.Component;

import nc.ticagou.common.AddressThrottle;

/**
 * Limite les essais de code d'appairage par adresse : 5 codes faux en 10 minutes bloquent l'adresse
 * jusqu'à l'expiration du plus ancien. Un code à 6 chiffres ne peut donc pas être deviné pendant sa validité.
 */
@Component
public class PairingThrottle extends AddressThrottle {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(10);

    public PairingThrottle(Clock clock) {
        super(clock, MAX_FAILURES, WINDOW);
    }
}
