package nc.ticagou.journal;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.document.EncryptedFileStore;
import nc.ticagou.document.StoredFileRepository;

/**
 * Vidéos de la journée gardées 60 jours sur le serveur de la crèche (ADR 0019) : le fichier est effacé, la
 * transmission reste dans le journal sans lui. Le portail efface les siennes de son côté (ADR 0010).
 */
@Component
public class VideoRetentionJob {

    static final Period RETENTION = Period.ofDays(60);
    private static final ZoneId NOUMEA = ZoneId.of("Pacific/Noumea");
    private static final Logger log = LoggerFactory.getLogger(VideoRetentionJob.class);

    private final JdbcTemplate jdbc;
    private final StoredFileRepository files;
    private final EncryptedFileStore fileStore;
    private final boolean enabled;
    private final Clock clock;

    public VideoRetentionJob(JdbcTemplate jdbc, StoredFileRepository files, EncryptedFileStore fileStore,
                             @Value("${ticagou.scheduling.enabled:true}") boolean enabled, Clock clock) {
        this.jdbc = jdbc;
        this.files = files;
        this.fileStore = fileStore;
        this.enabled = enabled;
        this.clock = clock;
    }

    @Scheduled(cron = "0 45 3 * * *", zone = "Pacific/Noumea")
    public void scheduled() {
        if (enabled) {
            run();
        }
    }

    /** Nombre de vidéos effacées. */
    @Transactional
    public int run() {
        var limit = LocalDate.now(clock.withZone(NOUMEA)).minus(RETENTION);
        var expired = jdbc.query("SELECT id, photo_file_id FROM daily_entry WHERE kind = 'VIDEO' AND photo_file_id IS NOT NULL "
                + "AND day < ?", (rs, i) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}, Date.valueOf(limit));
        for (var row : expired) {
            jdbc.update("UPDATE daily_entry SET photo_file_id = NULL WHERE id = ?", row[0]);
            files.findById(row[1]).ifPresent(fileStore::delete);
        }
        if (!expired.isEmpty()) {
            log.info("Conservation : {} vidéo(s) de plus de 60 jours effacée(s)", expired.size());
        }
        return expired.size();
    }
}
