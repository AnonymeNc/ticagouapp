package nc.ticagou.staffing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Alerte « encadrement insuffisant » (ADR 0026) : après le délai, une seule fois par dépassement. */
class RatioBreachNotificationTest {

    @Test
    void uneSeuleAlerteApresLeDelai() {
        var start = Instant.parse("2026-11-16T20:00:00Z");
        var breach = new RatioBreach(UUID.randomUUID(), UUID.randomUUID(), "Moyens", start, 9, 1, 2);
        var delay = Duration.ofMinutes(5);

        assertThat(breach.dueForNotification(start.plus(Duration.ofMinutes(4)), delay)).isFalse();
        assertThat(breach.dueForNotification(start.plus(Duration.ofMinutes(5)), delay)).isTrue();
        assertThat(breach.dueForNotification(start.plus(Duration.ofMinutes(6)), delay)).isFalse();
        assertThat(breach.dueForNotification(start.plus(Duration.ofHours(1)), delay)).isFalse();
    }
}
