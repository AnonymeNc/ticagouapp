package nc.ticagou.monitoring;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.common.ApiException;
import nc.ticagou.portal.PortalClient;
import nc.ticagou.portal.PortalClient.PortalException;
import nc.ticagou.portal.PortalKeys;
import nc.ticagou.portal.PortalSigner;

/**
 * Vue groupe (lot 7) : une crèche rejoint un groupe avec un code commun, choisi par le gestionnaire, et le
 * consentement de sa direction ; ses indicateurs agrégés partent alors avec l'état de supervision, et sa direction lit
 * ceux des autres crèches du groupe. Chaque crèche garde son instance, ses comptes et ses données.
 */
@Service
public class GroupService {

    /** Ce que voit une direction : son groupe (crèches qui partagent) et la date du dernier état reçu. */
    public record GroupView(boolean joined, Instant joinedAt, String joinedBy, List<JsonNode> creches, String error) {
    }

    private final JdbcTemplate jdbc;
    private final MonitoringService monitoring;
    private final PortalClient client;
    private final PortalKeys keys;
    private final AuditService audit;
    private final Clock clock;

    public GroupService(JdbcTemplate jdbc, MonitoringService monitoring, PortalClient client, PortalKeys keys, AuditService audit,
                        Clock clock) {
        this.jdbc = jdbc;
        this.monitoring = monitoring;
        this.client = client;
        this.keys = keys;
        this.audit = audit;
        this.clock = clock;
    }

    /** Rejoindre : le code n'est gardé que sous forme d'empreinte ; {@code url} est l'adresse de cette crèche. */
    @Transactional
    public GroupView join(String code, String url) {
        var user = CurrentUser.get();
        var clean = code == null ? "" : code.strip().toUpperCase();
        if (clean.length() < 8 || clean.length() > 64) {
            throw ApiException.rule("group_code", "Le code du groupe compte de 8 à 64 caractères.");
        }
        String address = null;
        if (url != null && !url.isBlank()) {
            try {
                var uri = URI.create(url.strip());
                if (!"https".equals(uri.getScheme()) && !"localhost".equals(uri.getHost())) {
                    throw new IllegalArgumentException();
                }
                address = uri.getScheme() + "://" + uri.getRawAuthority();
            } catch (IllegalArgumentException e) {
                throw ApiException.rule("group_url", "L’adresse de la crèche doit commencer par https://.");
            }
        }
        int changed = jdbc.update("UPDATE monitoring_settings SET group_code_hash = ?, group_url = ?, group_joined_at = ?, group_joined_by = ? "
                        + "WHERE structure_id = ? AND state = 'ACTIF'", PortalSigner.sha256(("ticagou-groupe:" + clean).getBytes(StandardCharsets.UTF_8)),
                address, Timestamp.from(clock.instant()), user.displayName(), user.structureId());
        if (changed == 0) {
            throw ApiException.rule("monitoring_inactive", "La supervision doit être active pour partager avec un groupe : contactez Ti’Cagou.");
        }
        audit.record(AuditAction.GROUP_JOINED, "structure", user.structureId(), Map.of());
        monitoring.sendNow();
        return view();
    }

    @Transactional
    public GroupView leave() {
        var user = CurrentUser.get();
        jdbc.update("UPDATE monitoring_settings SET group_code_hash = NULL, group_url = NULL, group_joined_at = NULL, group_joined_by = NULL "
                + "WHERE structure_id = ?", user.structureId());
        audit.record(AuditAction.GROUP_LEFT, "structure", user.structureId(), Map.of());
        // L'état suivant part sans le bloc « groupe » : le service l'efface aussitôt.
        monitoring.sendNow();
        return view();
    }

    /** Crèches du groupe, lues au service de supervision avec la clé de cette crèche. */
    public GroupView view() {
        var structureId = CurrentUser.get().structureId();
        var rows = jdbc.queryForList("SELECT service_url, site_id, group_code_hash, group_joined_at, group_joined_by FROM monitoring_settings "
                + "WHERE structure_id = ? AND state = 'ACTIF'", structureId);
        if (rows.isEmpty() || rows.getFirst().get("group_code_hash") == null) {
            return new GroupView(false, null, null, List.of(), null);
        }
        var row = rows.getFirst();
        var joinedAt = row.get("group_joined_at") == null ? null : ((Timestamp) row.get("group_joined_at")).toInstant();
        try {
            var list = client.supervisionGroup((String) row.get("service_url"), (UUID) row.get("site_id"),
                    keys.privateKey(PortalKeys.Use.MONITORING, structureId));
            return new GroupView(true, joinedAt, (String) row.get("group_joined_by"), list, null);
        } catch (PortalException e) {
            return new GroupView(true, joinedAt, (String) row.get("group_joined_by"), List.of(), e.getMessage());
        }
    }
}
