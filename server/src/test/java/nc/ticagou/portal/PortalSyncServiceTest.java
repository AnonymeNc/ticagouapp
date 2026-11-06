package nc.ticagou.portal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PortalSyncServiceTest {

    @Test
    void attenteQuiDoubleJusquATrenteMinutes() {
        assertThat(PortalSyncService.retryDelay(0)).isEqualTo(Duration.ZERO);
        assertThat(PortalSyncService.retryDelay(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(PortalSyncService.retryDelay(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(PortalSyncService.retryDelay(5)).isEqualTo(Duration.ofMinutes(16));
        assertThat(PortalSyncService.retryDelay(6)).isEqualTo(Duration.ofMinutes(30));
        assertThat(PortalSyncService.retryDelay(400)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void synchronisationDueApresLAttente() {
        var now = Instant.parse("2026-11-02T01:00:00Z");
        var settings = new PortalSettings(UUID.randomUUID(), now);
        assertThat(PortalSyncService.due(settings, now)).isTrue();

        settings.failed("Portail injoignable", now);
        settings.failed("Portail injoignable", now);
        assertThat(PortalSyncService.due(settings, now.plusSeconds(90))).isFalse();
        assertThat(PortalSyncService.due(settings, now.plusSeconds(120))).isTrue();

        settings.succeeded(now);
        assertThat(PortalSyncService.due(settings, now)).isTrue();
    }
}
