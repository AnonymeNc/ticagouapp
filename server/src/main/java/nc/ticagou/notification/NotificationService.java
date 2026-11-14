package nc.ticagou.notification;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PreDestroy;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.common.ApiException;
import nc.ticagou.license.DesktopClientService;
import nc.ticagou.notification.NativePush.Message;
import nc.ticagou.notification.NativePush.Platform;
import nc.ticagou.notification.NativePush.Result;
import nc.ticagou.notification.NativePush.Target;

/**
 * Appareils inscrits aux notifications natives et envoi des alertes de la direction (ADR 0026). L'envoi part en tâche
 * de fond : une requête n'attend jamais Apple ni Google. Un jeton refusé par le service est effacé.
 */
@Service
public class NotificationService {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationService.class);

    /** Ce que la direction peut recevoir (maquette C7). TABLETTE est décochée par défaut. */
    public enum Category { DEPART, ENCADREMENT, SAUVEGARDE, TABLETTE }

    public static final Set<Category> DEFAULT_CATEGORIES = EnumSet.of(Category.DEPART, Category.ENCADREMENT, Category.SAUVEGARDE);

    /** Identifiant du paquet de chaque application, sujet APNs (ADR 0023). */
    static String bundleId(String app) {
        return "nc.ticagou." + app;
    }

    public record DeviceDto(Platform platform, String app, Set<Category> categories) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ApnsSender apns;
    private final FcmSender fcm;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "notifications");
        thread.setDaemon(true);
        return thread;
    });
    private volatile CompletableFuture<Void> pending = CompletableFuture.completedFuture(null);

    public NotificationService(JdbcTemplate jdbc, Clock clock, ApnsSender apns, FcmSender fcm) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.apns = apns;
        this.fcm = fcm;
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    /** Inscrit (ou met à jour) l'appareil de la personne connectée. Un jeton passé à une autre personne change de main. */
    @Transactional
    public DeviceDto register(Platform platform, String app, String token, Set<Category> categories, String posteId) {
        var user = CurrentUser.get();
        var now = Timestamp.from(clock.instant());
        var chosen = categories == null ? DEFAULT_CATEGORIES : categories;
        var posteHash = posteId == null || posteId.isBlank() ? null : DesktopClientService.hash(posteId.trim());
        int updated = jdbc.update("""
                UPDATE notification_device SET structure_id = ?, user_id = ?, poste_hash = ?, platform = ?, app = ?,
                       categories = ?, last_seen_at = ? WHERE token = ?""",
                user.structureId(), user.userId(), posteHash, platform.name(), app, join(chosen), now, token);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO notification_device (id, structure_id, user_id, poste_hash, platform, app, token, categories,
                                                     created_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    UUID.randomUUID(), user.structureId(), user.userId(), posteHash, platform.name(), app, token, join(chosen), now, now);
        }
        return new DeviceDto(platform, app, chosen);
    }

    @Transactional(readOnly = true)
    public DeviceDto get(String token) {
        var user = CurrentUser.get();
        return jdbc.query("SELECT platform, app, categories FROM notification_device WHERE token = ? AND user_id = ?",
                (rs, i) -> new DeviceDto(Platform.valueOf(rs.getString("platform")), rs.getString("app"), parse(rs.getString("categories"))),
                token, user.userId()).stream().findFirst().orElseThrow(() -> ApiException.notFound("Appareil inconnu."));
    }

    /** Déconnexion, oubli de la crèche : l'appareil ne reçoit plus rien. */
    @Transactional
    public void unregister(String token) {
        jdbc.update("DELETE FROM notification_device WHERE token = ? AND user_id = ?", token, CurrentUser.get().userId());
    }

    /** Poste retiré par la direction (téléphone perdu, ADR 0024) : ses notifications s'arrêtent. */
    @EventListener
    public void onPosteRevoked(DesktopClientService.PosteRevoked event) {
        jdbc.update("DELETE FROM notification_device WHERE structure_id = ? AND poste_hash = ?", event.structureId(), event.hash());
    }

    /** Alerte aux personnes de la direction de la structure qui l'ont choisie. */
    public void notifyDirection(UUID structureId, Category category, Message message) {
        // Comptes désactivés : leurs appareils sont oubliés au passage.
        jdbc.update("""
                DELETE FROM notification_device d USING app_user u
                WHERE d.user_id = u.id AND d.structure_id = ? AND NOT u.active""", structureId);
        var targets = jdbc.query("""
                SELECT d.token, d.platform, d.app FROM notification_device d JOIN app_user u ON u.id = d.user_id
                WHERE d.structure_id = ? AND u.role = 'DIRECTION' AND u.active AND d.app = 'direction'
                  AND (',' || d.categories || ',') LIKE ?""",
                (rs, i) -> new Target(rs.getString("token"), Platform.valueOf(rs.getString("platform")), bundleId(rs.getString("app"))),
                structureId, "%," + category.name() + ",%");
        if (targets.isEmpty()) {
            return;
        }
        synchronized (this) {
            pending = pending.thenRunAsync(() -> deliver(message, targets), executor);
        }
    }

    private void deliver(Message message, List<Target> targets) {
        var invalid = new ArrayList<String>();
        for (var target : targets) {
            var sender = target.platform() == Platform.IOS ? apns : fcm;
            if (!sender.enabled()) {
                continue;
            }
            try {
                if (sender.send(message, target) == Result.INVALID) {
                    invalid.add(target.token());
                }
            } catch (RuntimeException e) {
                LOG.warn("Notification non envoyée : {}", e.getMessage());
            }
        }
        invalid.forEach(token -> jdbc.update("DELETE FROM notification_device WHERE token = ?", token));
    }

    /** Tests : attend la fin des envois en cours. */
    public void awaitPending() {
        try {
            pending.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Durée de vie d'une alerte chez Apple et Google : passé ce délai, elle n'a plus de sens. */
    public static final Duration ALERT_TTL = Duration.ofHours(2);

    static String join(Set<Category> categories) {
        return categories.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    static Set<Category> parse(String text) {
        var set = EnumSet.noneOf(Category.class);
        Arrays.stream(text.split(",")).filter(s -> !s.isBlank()).forEach(s -> {
            try {
                set.add(Category.valueOf(s.trim()));
            } catch (IllegalArgumentException ignored) {
                // Catégorie retirée : ignorée.
            }
        });
        return set;
    }
}
