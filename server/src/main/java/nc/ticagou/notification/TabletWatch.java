package nc.ticagou.notification;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalTime;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.notification.NativePush.Message;
import nc.ticagou.notification.NotificationService.Category;

/**
 * Tablette de pointage sans nouvelles pendant les heures d'ouverture (ADR 0026) : la direction est prévenue une fois
 * par absence. Hors ouverture, une tablette éteinte est normale.
 */
@Component
public class TabletWatch {

    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final PushProperties push;
    private final Clock clock;
    private final boolean enabled;

    public TabletWatch(JdbcTemplate jdbc, NotificationService notifications, PushProperties push, Clock clock,
                       @Value("${ticagou.scheduling.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.push = push;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    void tick() {
        if (enabled) {
            check();
        }
    }

    /** Tablettes appairées, muettes depuis le délai, dans une structure ouverte à cet instant. */
    @Transactional
    public void check() {
        var now = clock.instant();
        var local = now.atZone(clock.getZone());
        var since = Timestamp.from(now.minus(push.tabletOffline()));
        var rows = jdbc.queryForList("""
                SELECT d.id, d.structure_id, d.name, d.last_seen_at FROM device d
                JOIN structure_opening_hours h ON h.structure_id = d.structure_id AND h.day_of_week = ?
                WHERE d.token_hash IS NOT NULL AND d.revoked_at IS NULL AND d.last_seen_at IS NOT NULL AND d.last_seen_at < ?
                  AND h.opens_at <= ? AND h.closes_at > ?
                  AND (d.offline_notified_at IS NULL OR d.offline_notified_at < d.last_seen_at)""",
                local.getDayOfWeek().getValue(), since, LocalTime.from(local), LocalTime.from(local));
        for (var row : rows) {
            var name = (String) row.get("name");
            var minutes = java.time.Duration.between(((Timestamp) row.get("last_seen_at")).toInstant(), now).toMinutes();
            jdbc.update("UPDATE device SET offline_notified_at = ? WHERE id = ?", Timestamp.from(now), row.get("id"));
            notifications.notifyDirection((UUID) row.get("structure_id"), Category.TABLETTE, new Message(
                    "Tablette hors connexion", "Tablette « %s » sans nouvelles depuis %d min".formatted(name, minutes),
                    "/parametres?onglet=appareils", Category.TABLETTE.name(), NotificationService.ALERT_TTL,
                    "tablette-" + row.get("id")));
        }
    }
}
