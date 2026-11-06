package nc.ticagou.backup;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Vérifie chaque minute si la sauvegarde de la nuit est due (désactivé dans les tests). */
@Component
public class BackupScheduler {

    private final BackupService backups;
    private final boolean enabled;

    public BackupScheduler(BackupService backups, @Value("${ticagou.scheduling.enabled:true}") boolean enabled) {
        this.backups = backups;
        this.enabled = enabled;
    }

    @Scheduled(cron = "0 * * * * *")
    void tick() {
        if (enabled) {
            backups.runNightlyIfDue();
        }
    }
}
