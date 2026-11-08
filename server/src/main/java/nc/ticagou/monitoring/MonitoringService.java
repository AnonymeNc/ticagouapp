package nc.ticagou.monitoring;

import java.io.File;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.audit.AuditService.Actor;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.backup.BackupService;
import nc.ticagou.common.ApiException;
import nc.ticagou.config.TiCagouProperties;
import nc.ticagou.health.HealthService;
import nc.ticagou.license.LicenseService;
import nc.ticagou.portal.PortalClient;
import nc.ticagou.portal.PortalClient.PortalException;
import nc.ticagou.portal.PortalClient.SupervisionActivation;
import nc.ticagou.portal.PortalKeys;

/**
 * Supervision (ADR 0012, ADR 0013) : activée d'office par l'éditeur, l'instance envoie toutes les 15 minutes un état
 * technique ({@link MonitoringStatus}) au service hébergé du prestataire, qui prévient par e-mail d'une sauvegarde en
 * retard, d'un disque plein, d'une licence à renouveler ou d'un serveur muet. Connexion toujours sortante.
 */
@Service
public class MonitoringService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);
    static final Duration INTERVAL = Duration.ofMinutes(15);
    static final Duration MAX_RETRY = Duration.ofHours(1);
    private static final ZoneId NOUMEA = ZoneId.of("Pacific/Noumea");

    public record SettingsDto(String state, String serviceUrl, Instant activatedAt, String activatedBy,
                              Instant lastSuccessAt, Instant lastAttemptAt, String lastError, int failures) {
    }

    private final JdbcTemplate jdbc;
    private final PortalClient client;
    private final PortalKeys keys;
    private final BackupService backups;
    private final LicenseService licenses;
    private final HealthService health;
    private final AuditService audit;
    private final TiCagouProperties properties;
    private final Clock clock;
    private final boolean scheduling;
    private final ReentrantLock lock = new ReentrantLock();

    public MonitoringService(JdbcTemplate jdbc, PortalClient client, PortalKeys keys, BackupService backups,
                             LicenseService licenses, HealthService health, AuditService audit, TiCagouProperties properties,
                             Clock clock, @Value("${ticagou.scheduling.enabled:true}") boolean scheduling) {
        this.jdbc = jdbc;
        this.client = client;
        this.keys = keys;
        this.backups = backups;
        this.licenses = licenses;
        this.health = health;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
        this.scheduling = scheduling;
    }

    @Transactional(readOnly = true)
    public SettingsDto settings() {
        return settings(CurrentUser.get().structureId());
    }

    SettingsDto settings(UUID structureId) {
        var rows = jdbc.query("SELECT * FROM monitoring_settings WHERE structure_id = ?", (rs, i) -> new SettingsDto(
                rs.getString("state"), rs.getString("service_url"), instant(rs.getTimestamp("activated_at")),
                rs.getString("activated_by"), instant(rs.getTimestamp("last_success_at")),
                instant(rs.getTimestamp("last_attempt_at")), rs.getString("last_error"), rs.getInt("failures")), structureId);
        return rows.isEmpty() ? new SettingsDto("DESACTIVE", null, null, null, null, null, null, 0) : rows.getFirst();
    }

    /** Activation : code remis par le prestataire, nouvelle paire de clés, premier envoi immédiat. */
    @Transactional
    public SettingsDto activate(String serviceUrl, String code) {
        var user = CurrentUser.get();
        return activate(user.structureId(), user.displayName(), serviceUrl, code);
    }

    private SettingsDto activate(UUID structureId, String author, String serviceUrl, String code) {
        var url = serviceUrl == null ? "" : serviceUrl.strip().replaceAll("/+$", "");
        if (!url.startsWith("https://") && !url.startsWith("http://localhost") && !url.startsWith("http://127.0.0.1")) {
            throw ApiException.rule("monitoring_url", "L’adresse du service doit commencer par https://.");
        }
        var name = jdbc.queryForObject("SELECT name FROM structure WHERE id = ?", String.class, structureId);
        var publicKey = keys.rotate(PortalKeys.Use.MONITORING, structureId);
        UUID siteId;
        try {
            siteId = client.activateSupervision(url, new SupervisionActivation(code == null ? "" : code.strip(), publicKey, name)).siteId();
        } catch (PortalException e) {
            keys.forget(PortalKeys.Use.MONITORING, structureId);
            throw ApiException.rule("monitoring_activation", e.getMessage());
        }
        var now = Timestamp.from(clock.instant());
        jdbc.update("""
                INSERT INTO monitoring_settings (structure_id, state, service_url, site_id, activated_at, activated_by, failures)
                VALUES (?, 'ACTIF', ?, ?, ?, ?, 0)
                ON CONFLICT (structure_id) DO UPDATE SET state = 'ACTIF', service_url = excluded.service_url,
                    site_id = excluded.site_id, activated_at = excluded.activated_at, activated_by = excluded.activated_by,
                    failures = 0, last_error = NULL""", structureId, url, siteId, now, author);
        audit.record(new Actor(structureId, null, author), AuditAction.MONITORING_ACTIVATED, "monitoring", structureId, Map.of("url", url));
        send(structureId);
        return settings(structureId);
    }

    /** Crèche créée et supervision configurée par l'éditeur (deploy/creche.sh) : activation au premier passage. */
    private void activateFromConfiguration() {
        var configured = properties.monitoring();
        if (configured.url().isBlank()) {
            return;
        }
        for (var structureId : jdbc.queryForList("""
                SELECT id FROM structure s WHERE NOT EXISTS (SELECT 1 FROM monitoring_settings m WHERE m.structure_id = s.id)""",
                UUID.class)) {
            try {
                activate(structureId, "Ti’Cagou", configured.url(), configured.code());
            } catch (ApiException e) {
                log.warn("Supervision : activation impossible ({})", e.getMessage());
            }
        }
    }

    @Transactional
    public SettingsDto deactivate() {
        var user = CurrentUser.get();
        jdbc.update("UPDATE monitoring_settings SET state = 'DESACTIVE', site_id = NULL WHERE structure_id = ?", user.structureId());
        keys.forget(PortalKeys.Use.MONITORING, user.structureId());
        audit.record(AuditAction.MONITORING_DEACTIVATED, "monitoring", user.structureId(), Map.of());
        return settings(user.structureId());
    }

    @Transactional
    public SettingsDto sendNow() {
        var structureId = CurrentUser.get().structureId();
        send(structureId);
        return settings(structureId);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void scheduled() {
        if (!scheduling) {
            return;
        }
        activateFromConfiguration();
        var now = clock.instant();
        for (var row : jdbc.queryForList("SELECT structure_id, last_attempt_at, failures FROM monitoring_settings WHERE state = 'ACTIF'")) {
            var last = row.get("last_attempt_at") == null ? null : ((Timestamp) row.get("last_attempt_at")).toInstant();
            if (last == null || !now.isBefore(last.plus(delay((Integer) row.get("failures"))))) {
                send((UUID) row.get("structure_id"));
            }
        }
    }

    /** Quinze minutes entre deux envois ; après des échecs, l'attente double jusqu'à une heure. */
    static Duration delay(int failures) {
        if (failures <= 0) {
            return INTERVAL;
        }
        var delay = INTERVAL.multipliedBy(1L << Math.min(failures - 1, 6));
        return delay.compareTo(MAX_RETRY) > 0 ? MAX_RETRY : delay;
    }

    void send(UUID structureId) {
        if (!lock.tryLock()) {
            return;
        }
        try {
            var row = jdbc.queryForList("SELECT service_url, site_id FROM monitoring_settings WHERE structure_id = ? AND state = 'ACTIF'",
                    structureId);
            if (row.isEmpty()) {
                return;
            }
            var url = (String) row.getFirst().get("service_url");
            var siteId = (UUID) row.getFirst().get("site_id");
            var now = Timestamp.from(clock.instant());
            try {
                client.sendStatus(url, siteId, keys.privateKey(PortalKeys.Use.MONITORING, structureId), status(structureId));
                jdbc.update("UPDATE monitoring_settings SET last_success_at = ?, last_attempt_at = ?, failures = 0, last_error = NULL WHERE structure_id = ?",
                        now, now, structureId);
            } catch (PortalException | IllegalStateException e) {
                log.warn("Supervision : envoi impossible ({})", e.getMessage());
                jdbc.update("UPDATE monitoring_settings SET last_attempt_at = ?, failures = failures + 1, last_error = ? WHERE structure_id = ?",
                        now, e.getMessage() == null ? "Erreur" : e.getMessage().substring(0, Math.min(e.getMessage().length(), 500)), structureId);
                if (e instanceof PortalException p && p.status() == 401) {
                    audit.record(new Actor(structureId, null, "Ti’Cagou"), AuditAction.MONITORING_REFUSED, "monitoring", structureId, Map.of());
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /** État du moment, construit uniquement à partir d'indicateurs techniques. */
    MonitoringStatus status(UUID structureId) {
        var check = health.check();
        var overview = backups.overview();
        var license = licenses.current();
        var data = space(properties.storage().dir().toFile());
        var destination = space(properties.backup().dir().toFile());
        var lastSuccess = overview.lastSuccess() == null ? null
                : overview.lastSuccess().finishedAt() != null ? overview.lastSuccess().finishedAt() : overview.lastSuccess().startedAt();
        int silent = jdbc.queryForObject("""
                SELECT count(*) FROM device WHERE token_hash IS NOT NULL AND revoked_at IS NULL
                  AND (last_seen_at IS NULL OR last_seen_at < ?)""", Integer.class,
                Timestamp.from(clock.instant().minus(Duration.ofDays(3))));
        var portal = jdbc.queryForList("SELECT state, failures, last_error FROM portal_settings WHERE structure_id = ?", structureId);
        int pending = jdbc.queryForObject("SELECT count(*) FROM portal_outbox WHERE structure_id = ? AND sent_at IS NULL", Integer.class, structureId);
        int toReview = jdbc.queryForObject("SELECT count(*) FROM child_attendance WHERE structure_id = ? AND needs_review", Integer.class, structureId);
        return new MonitoringStatus(check.version(), check.database().name(),
                new MonitoringStatus.Backup(overview.enabled(), overview.toolsAvailable(), overview.stale(), lastSuccess,
                        overview.lastTestOn(), overview.lastTestOk()),
                new MonitoringStatus.Disk(data == null ? null : data[0], data == null ? null : data[1],
                        destination == null ? null : destination[0], destination == null ? null : destination[1]),
                new MonitoringStatus.License(license.state().name(), license.license() == null ? null : license.license().validUntil(),
                        license.deadline(), license.desktops(), license.desktopLimit()),
                new MonitoringStatus.Tablets(license.pairedTablets(), silent),
                portal.isEmpty() ? new MonitoringStatus.Portal("DESACTIVE", 0, null, pending)
                        : new MonitoringStatus.Portal((String) portal.getFirst().get("state"), (Integer) portal.getFirst().get("failures"),
                                (String) portal.getFirst().get("last_error"), pending),
                toReview, usage(structureId, license.license() == null ? null : license.license().places()));
    }

    /** Usage sans donnée personnelle : des comptes et une date (ADR 0012 amendée, console de l'éditeur). */
    MonitoringStatus.Usage usage(UUID structureId, Integer places) {
        var now = clock.instant();
        var today = LocalDate.ofInstant(now, NOUMEA);
        int children = jdbc.queryForObject("""
                SELECT count(*) FROM child WHERE structure_id = ? AND entry_date IS NOT NULL AND entry_date <= ?
                  AND (exit_date IS NULL OR exit_date >= ?)""", Integer.class, structureId, today, today);
        int users = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE structure_id = ? AND active AND last_login_at >= ?",
                Integer.class, structureId, Timestamp.from(now.minus(Duration.ofDays(30))));
        var direction = jdbc.queryForObject("SELECT max(last_login_at) FROM app_user WHERE structure_id = ? AND role = 'DIRECTION'",
                Timestamp.class, structureId);
        int attendances = jdbc.queryForObject("SELECT count(*) FROM child_attendance WHERE structure_id = ? AND day > ? AND day <= ?",
                Integer.class, structureId, today.minusDays(7), today);
        int invoices = jdbc.queryForObject("SELECT count(*) FROM invoice WHERE structure_id = ? AND month = ?", Integer.class,
                structureId, today.withDayOfMonth(1).minusMonths(1));
        return new MonitoringStatus.Usage(children, places, users,
                direction == null ? null : LocalDate.ofInstant(direction.toInstant(), NOUMEA), attendances, invoices);
    }

    /** Octets libres et total du disque d'un dossier ; nul si le dossier est inaccessible. */
    static long[] space(File folder) {
        var probe = folder.getAbsoluteFile();
        while (probe != null && !probe.exists()) {
            probe = probe.getParentFile();
        }
        if (probe == null || probe.getTotalSpace() == 0) {
            return null;
        }
        return new long[] {probe.getUsableSpace(), probe.getTotalSpace()};
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
