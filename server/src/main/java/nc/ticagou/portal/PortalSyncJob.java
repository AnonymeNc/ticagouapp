package nc.ticagou.portal;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Synchronisation du portail parents chaque minute ; les envois acquittés sont oubliés après 30 jours. */
@Component
public class PortalSyncJob {

    private final PortalSyncService sync;
    private final PortalOutboxRepository outbox;
    private final boolean enabled;
    private final Clock clock;

    public PortalSyncJob(PortalSyncService sync, PortalOutboxRepository outbox,
                         @Value("${ticagou.scheduling.enabled:true}") boolean enabled, Clock clock) {
        this.sync = sync;
        this.outbox = outbox;
        this.enabled = enabled;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void run() {
        if (enabled) {
            sync.syncAll();
        }
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Pacific/Noumea")
    @org.springframework.transaction.annotation.Transactional
    public void cleanUp() {
        if (enabled) {
            outbox.deleteSentBefore(clock.instant().minus(Duration.ofDays(30)));
        }
    }
}
