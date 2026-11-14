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
import nc.ticagou.auth.AppClient;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.common.ApiException;
import nc.ticagou.config.TiCagouProperties;

/**
 * Postes du bureau comptés par l'abonnement (ADR 0011). Chaque navigateur envoie un identifiant
 * qu'il a tiré au hasard à sa première ouverture ({@code X-Ticagou-Poste}) ; le serveur n'en garde que le haché.
 * Un PC absent depuis {@code ticagou.license.desktop-idle} libère sa place, un PC retiré par la direction ne peut
 * plus se connecter. Les anciens clients sans identifiant partagent une seule place.
 * <p>
 * L'application Direction installée sur un téléphone ou une tablette est un poste {@link Kind#MOBILE} : enregistré et
 * révocable comme un PC, mais hors du compte des PC, dans la limite de {@code ticagou.license.mobile-max} (ADR 0028).
 */
@Service
public class DesktopClientService {

    public static final String HEADER = "X-Ticagou-Poste";
    public static final String NAME_HEADER = "X-Ticagou-Poste-Nom";
    static final String UNIDENTIFIED = "sans-identifiant";

    /** BUREAU : navigateur, compté dans les PC de l'abonnement ; MOBILE : application Direction (ADR 0028). */
    public enum Kind { BUREAU, MOBILE }

    public record DesktopClient(UUID id, String label, Instant firstSeenAt, Instant lastSeenAt, String lastUserName,
                                boolean active, Kind kind, String app) {
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
        admit(structureId, clientId, clientName, userName, null);
    }

    /** {@code app} : application mobile annoncée par l'en-tête X-Ticagou-App, null pour un navigateur. */
    @Transactional(noRollbackFor = ApiException.class)
    public void admit(UUID structureId, String clientId, String clientName, String userName, AppClient app) {
        var raw = clientId == null || clientId.isBlank() ? UNIDENTIFIED : clientId.trim();
        var kind = app != null && app.isDirection() && !raw.equals(UNIDENTIFIED) ? Kind.MOBILE : Kind.BUREAU;
        var appLabel = kind == Kind.MOBILE ? app.label() : null;
        var label = raw.equals(UNIDENTIFIED) ? "PC non identifié (ancienne version de Ti’Cagou)"
                : label(clientName, kind == Kind.MOBILE ? "Téléphone sans nom" : "PC sans nom");
        var hash = hash(raw);
        var now = clock.instant();
        var rows = jdbc.queryForList("SELECT id, revoked_at FROM desktop_client WHERE structure_id = ? AND client_hash = ?",
                structureId, hash);
        if (!rows.isEmpty()) {
            var row = rows.getFirst();
            if (row.get("revoked_at") != null) {
                throw ApiException.forbidden("desktop_revoked", kind == Kind.MOBILE
                        ? "Ce téléphone a été retiré de Ti’Cagou par la direction (Paramètres › Tablettes et abonnement)."
                        : "Ce PC a été retiré de Ti’Cagou par la direction (Paramètres › Tablettes et abonnement).");
            }
            jdbc.update("""
                    UPDATE desktop_client SET last_seen_at = ?, label = ?, last_user_name = ?, kind = ?, app = ? WHERE id = ?""",
                    Timestamp.from(now), label, userName, kind.name(), appLabel, row.get("id"));
            return;
        }
        if (kind == Kind.MOBILE) {
            int mobileMax = settings.mobileMax();
            if (active(structureId, now, Kind.MOBILE) >= mobileMax) {
                audit.record(new Actor(structureId, null, userName), AuditAction.DESKTOP_REFUSED, "desktop_client", null,
                        Map.of("label", label, "kind", kind.name()));
                throw ApiException.rule("license_mobiles", ("Ce téléphone dépasse les %d téléphones permis : retirez un ancien "
                        + "téléphone dans Paramètres › Tablettes et abonnement, ou contactez Ti’Cagou.").formatted(mobileMax));
            }
        } else if (active(structureId, now) >= licenses.desktopLimit()) {
            int limit = licenses.desktopLimit();
            audit.record(new Actor(structureId, null, userName), AuditAction.DESKTOP_REFUSED, "desktop_client", null,
                    Map.of("label", label));
            throw ApiException.rule("license_desktops", ("Ce PC dépasse %s de l’abonnement : retirez un ancien PC dans "
                    + "Paramètres › Tablettes et abonnement, ou contactez Ti’Cagou.")
                    .formatted(limit <= 1 ? "le poste" : "les %d postes".formatted(limit)));
        }
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO desktop_client (id, structure_id, client_hash, label, first_seen_at, last_seen_at, last_user_name,
                                            kind, app)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, structureId, hash, label, Timestamp.from(now), Timestamp.from(now),
                userName, kind.name(), appLabel);
        audit.record(new Actor(structureId, null, userName), AuditAction.DESKTOP_REGISTERED, "desktop_client", id,
                Map.of("label", label, "kind", kind.name()));
    }

    /** PC qui occupent une place : non retirés et vus récemment. Les téléphones ne comptent pas (ADR 0028). */
    public int active(UUID structureId, Instant now) {
        return active(structureId, now, Kind.BUREAU);
    }

    public int active(UUID structureId, Instant now, Kind kind) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM desktop_client
                WHERE structure_id = ? AND kind = ? AND revoked_at IS NULL AND last_seen_at > ?""",
                Integer.class, structureId, kind.name(), Timestamp.from(now.minus(settings.desktopIdle())));
    }

    @Transactional(readOnly = true)
    public List<DesktopClient> list() {
        var structureId = CurrentUser.get().structureId();
        var since = clock.instant().minus(settings.desktopIdle());
        return jdbc.query("""
                SELECT id, label, first_seen_at, last_seen_at, last_user_name, kind, app FROM desktop_client
                WHERE structure_id = ? AND revoked_at IS NULL ORDER BY last_seen_at DESC""",
                (rs, i) -> {
                    var last = rs.getTimestamp("last_seen_at").toInstant();
                    return new DesktopClient(rs.getObject("id", UUID.class), rs.getString("label"),
                            rs.getTimestamp("first_seen_at").toInstant(), last, rs.getString("last_user_name"),
                            last.isAfter(since), Kind.valueOf(rs.getString("kind")), rs.getString("app"));
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

    private static String label(String name, String fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
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
