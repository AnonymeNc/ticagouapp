package nc.ticagou.license;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.audit.AuditService.Actor;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.common.ApiException;
import nc.ticagou.config.TiCagouProperties;

/**
 * Postes du bureau comptés par l'abonnement (ADR 0011). Chaque navigateur envoie un identifiant
 * qu'il a tiré au hasard à sa première ouverture ({@code X-Ticagou-Poste}) ; le serveur n'en garde que le haché.
 * Un PC absent depuis {@code ticagou.license.desktop-idle} libère sa place, un PC retiré par la direction ne peut
 * plus se connecter. Les anciens clients sans identifiant partagent une seule place.
 */
@Service
public class DesktopClientService {

    public static final String HEADER = "X-Ticagou-Poste";
    public static final String NAME_HEADER = "X-Ticagou-Poste-Nom";
    static final String UNIDENTIFIED = "sans-identifiant";

    public record DesktopClient(UUID id, String label, Instant firstSeenAt, Instant lastSeenAt, String lastUserName,
                                boolean active) {
    }

    private final JdbcTemplate jdbc;
    private final LicenseService licenses;
    private final AuditService audit;
    private final Clock clock;
    private final TiCagouProperties.License settings;

    public DesktopClientService(JdbcTemplate jdbc, LicenseService licenses, AuditService audit, Clock clock,
                                TiCagouProperties properties) {
        this.jdbc = jdbc;
        this.licenses = licenses;
        this.audit = audit;
        this.clock = clock;
        this.settings = properties.license();
    }

    /**
     * Connexion d'un PC (mot de passe ou renouvellement de session) : le PC est enregistré, ou refusé s'il a été
     * retiré ou s'il dépasse le nombre de PC de l'abonnement.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public void admit(UUID structureId, String clientId, String clientName, String userName) {
        var raw = clientId == null || clientId.isBlank() ? UNIDENTIFIED : clientId.trim();
        var label = raw.equals(UNIDENTIFIED) ? "PC non identifié (ancienne version de Ti’Cagou)" : label(clientName);
        var hash = hash(raw);
        var now = clock.instant();
        var rows = jdbc.queryForList("SELECT id, revoked_at FROM desktop_client WHERE structure_id = ? AND client_hash = ?",
                structureId, hash);
        if (!rows.isEmpty()) {
            var row = rows.getFirst();
            if (row.get("revoked_at") != null) {
                throw ApiException.forbidden("desktop_revoked",
                        "Ce PC a été retiré de Ti’Cagou par la direction (Paramètres › Tablettes et abonnement).");
            }
            jdbc.update("UPDATE desktop_client SET last_seen_at = ?, label = ?, last_user_name = ? WHERE id = ?",
                    Timestamp.from(now), label, userName, row.get("id"));
            return;
        }
        int limit = licenses.desktopLimit();
        if (active(structureId, now) >= limit) {
            audit.record(new Actor(structureId, null, userName), AuditAction.DESKTOP_REFUSED, "desktop_client", null,
                    Map.of("label", label));
            throw ApiException.rule("license_desktops", ("Ce PC dépasse %s de l’abonnement : retirez un ancien PC dans "
                    + "Paramètres › Tablettes et abonnement, ou contactez Ti’Cagou.")
                    .formatted(limit <= 1 ? "le poste" : "les %d postes".formatted(limit)));
        }
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO desktop_client (id, structure_id, client_hash, label, first_seen_at, last_seen_at, last_user_name)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", id, structureId, hash, label, Timestamp.from(now), Timestamp.from(now), userName);
        audit.record(new Actor(structureId, null, userName), AuditAction.DESKTOP_REGISTERED, "desktop_client", id,
                Map.of("label", label));
    }

    /** PC qui occupent une place : non retirés et vus récemment. */
    public int active(UUID structureId, Instant now) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM desktop_client WHERE structure_id = ? AND revoked_at IS NULL AND last_seen_at > ?""",
                Integer.class, structureId, Timestamp.from(now.minus(settings.desktopIdle())));
    }

    @Transactional(readOnly = true)
    public List<DesktopClient> list() {
        var structureId = CurrentUser.get().structureId();
        var since = clock.instant().minus(settings.desktopIdle());
        return jdbc.query("""
                SELECT id, label, first_seen_at, last_seen_at, last_user_name FROM desktop_client
                WHERE structure_id = ? AND revoked_at IS NULL ORDER BY last_seen_at DESC""",
                (rs, i) -> {
                    var last = rs.getTimestamp("last_seen_at").toInstant();
                    return new DesktopClient(rs.getObject("id", UUID.class), rs.getString("label"),
                            rs.getTimestamp("first_seen_at").toInstant(), last, rs.getString("last_user_name"),
                            last.isAfter(since));
                }, structureId);
    }

    @Transactional
    public void revoke(UUID id) {
        var user = CurrentUser.get();
        int updated = jdbc.update("""
                UPDATE desktop_client SET revoked_at = ?, revoked_by = ? WHERE id = ? AND structure_id = ? AND revoked_at IS NULL""",
                Timestamp.from(clock.instant()), user.displayName(), id, user.structureId());
        if (updated == 0) {
            throw ApiException.notFound("PC introuvable.");
        }
        audit.record(AuditAction.DESKTOP_REVOKED, "desktop_client", id, Map.of());
    }

    /** Le nom du PC arrive encodé, un en-tête HTTP étant en ASCII : « Bureau%20accueil ». */
    public static String decodeName(String value) {
        if (value == null) {
            return null;
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static String label(String name) {
        if (name == null || name.isBlank()) {
            return "PC sans nom";
        }
        var clean = name.strip().replaceAll("\\p{Cntrl}", "");
        return clean.length() > 120 ? clean.substring(0, 120) : clean;
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
