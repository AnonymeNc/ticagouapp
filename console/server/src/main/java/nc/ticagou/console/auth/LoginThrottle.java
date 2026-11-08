package nc.ticagou.console.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import nc.ticagou.console.common.ApiException;

/** Connexions manquées par adresse : 20 en 10 minutes au plus (ADR 0017). En mémoire : une seule instance de la console. */
@Component
public class LoginThrottle {

    static final int MAX_FAILURES = 20;
    static final Duration WINDOW = Duration.ofMinutes(10);

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    public void check(String address) {
        var recent = failures.get(key(address));
        if (recent == null) {
            return;
        }
        synchronized (recent) {
            prune(recent, clock.instant());
            if (recent.size() >= MAX_FAILURES) {
                throw ApiException.tooMany();
            }
        }
    }

    public void fail(String address) {
        var recent = failures.computeIfAbsent(key(address), k -> new ArrayDeque<>());
        synchronized (recent) {
            var now = clock.instant();
            prune(recent, now);
            recent.addLast(now);
        }
    }

    /** Pour les tests. */
    void reset() {
        failures.clear();
    }

    private static void prune(Deque<Instant> recent, Instant now) {
        while (!recent.isEmpty() && recent.peekFirst().isBefore(now.minus(WINDOW))) {
            recent.pollFirst();
        }
    }

    private static String key(String address) {
        return address == null ? "?" : address;
    }
}
