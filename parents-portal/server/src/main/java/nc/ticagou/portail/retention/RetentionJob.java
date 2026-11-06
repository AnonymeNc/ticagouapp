package nc.ticagou.portail.retention;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.portail.PortalProperties;
import nc.ticagou.portail.TimeConfig;

/**
 * Effacement quotidien (ADR 0010) : éléments arrivés à échéance (transmissions et photos 60 jours, factures et
 * documents 24 mois, messages 12 mois), demandes de connexion de plus d'un jour, sessions expirées ou révoquées.
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final JdbcTemplate jdbc;
    private final PortalProperties properties;
    private final Clock clock;

    public RetentionJob(JdbcTemplate jdbc, PortalProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "0 15 2 * * *", zone = "Pacific/Noumea")
    public void scheduled() {
        if (properties.scheduling()) {
            run();
        }
    }

    @Transactional
    public int run() {
        var today = LocalDate.now(clock.withZone(TimeConfig.NOUMEA));
        var now = clock.instant();
        int items = jdbc.update("DELETE FROM item WHERE expires_on < ?", Date.valueOf(today));
        jdbc.update("DELETE FROM login_request WHERE created_at < ?", Timestamp.from(now.minus(Duration.ofDays(1))));
        jdbc.update("DELETE FROM parent_session WHERE expires_at < ? OR revoked_at < ?", Timestamp.from(now),
                Timestamp.from(now.minus(Duration.ofDays(1))));
        jdbc.update("DELETE FROM activation_code WHERE used_at IS NULL AND expires_at < ?", Timestamp.from(now));
        log.info("Rétention : {} élément(s) effacé(s)", items);
        return items;
    }
}
