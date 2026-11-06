package nc.ticagou.portail.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/** Limite en mémoire : au plus {@code max} demandes par clé (adresse e-mail, IP) sur une fenêtre glissante. */
@Component
public class RateLimiter {

    private final Map<String, ArrayDeque<Instant>> hits = new ConcurrentHashMap<>();
    private final Clock clock;

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    public boolean allow(String key, int max, Duration window) {
        var now = clock.instant();
        var queue = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (queue) {
            while (!queue.isEmpty() && queue.peekFirst().isBefore(now.minus(window))) {
                queue.pollFirst();
            }
            if (queue.size() >= max) {
                return false;
            }
            queue.addLast(now);
            return true;
        }
    }

    public void reset() {
        hits.clear();
    }
}
