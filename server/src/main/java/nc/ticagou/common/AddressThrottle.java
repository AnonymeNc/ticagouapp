package nc.ticagou.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limite les échecs par adresse : {@code maxFailures} dans {@code window} bloquent l'adresse jusqu'à l'expiration du
 * plus ancien. Compteurs en mémoire : une instance par crèche, et un redémarrage les remet à zéro sans dommage.
 */
public class AddressThrottle {

    /** Au-delà, les adresses sans échec récent sont oubliées : la mémoire reste bornée face à un balayage. */
    private static final int MAX_ADDRESSES = 10_000;

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxFailures;
    private final Duration window;

    protected AddressThrottle(Clock clock, int maxFailures, Duration window) {
        this.clock = clock;
        this.maxFailures = maxFailures;
        this.window = window;
    }

    public boolean isBlocked(String address) {
        var recent = failures.get(address);
        if (recent == null) {
            return false;
        }
        synchronized (recent) {
            prune(recent);
            return recent.size() >= maxFailures;
        }
    }

    public void failed(String address) {
        if (failures.size() >= MAX_ADDRESSES) {
            failures.values().removeIf(recent -> {
                synchronized (recent) {
                    prune(recent);
                    return recent.isEmpty();
                }
            });
        }
        var recent = failures.computeIfAbsent(address, a -> new ArrayDeque<>());
        synchronized (recent) {
            prune(recent);
            recent.addLast(clock.instant());
        }
    }

    public void succeeded(String address) {
        failures.remove(address);
    }

    /** Pour les tests : oublie tous les essais. */
    public void reset() {
        failures.clear();
    }

    private void prune(Deque<Instant> recent) {
        var limit = clock.instant().minus(window);
        while (!recent.isEmpty() && recent.peekFirst().isBefore(limit)) {
            recent.removeFirst();
        }
    }
}
