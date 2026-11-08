package nc.ticagou.portail.admin;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import nc.ticagou.portail.PortalProperties;
import nc.ticagou.portail.common.ApiException;
import nc.ticagou.portail.common.Hashes;
import nc.ticagou.portail.supervision.SupervisionService;
import nc.ticagou.portail.sync.SyncService;

/**
 * Exploitation par l'hébergeur (jeton {@code PORTAL_ADMIN_TOKEN}) : codes d'activation, liste et suspension des
 * structures. Aucune donnée de famille n'est accessible par ici.
 */
@RestController
@RequestMapping("/admin/v1")
public class AdminController {

    private final PortalProperties properties;
    private final SyncService sync;
    private final SupervisionService supervision;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AdminController(PortalProperties properties, SyncService sync, SupervisionService supervision, JdbcTemplate jdbc,
                           Clock clock) {
        this.properties = properties;
        this.sync = sync;
        this.supervision = supervision;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @PostMapping("/activation-codes")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> code(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestBody(required = false) Map<String, String> request) {
        check(authorization);
        return Map.of("activationCode", sync.newActivationCode(request == null ? null : request.get("label")));
    }

    @GetMapping("/structures")
    public List<Map<String, Object>> structures(@RequestHeader(value = "Authorization", required = false) String authorization) {
        check(authorization);
        return jdbc.queryForList("SELECT id, name, activated_at, last_sync_at, suspended_at, "
                + "(SELECT count(*) FROM guardian g WHERE g.structure_id = s.id) AS guardians FROM portal_structure s ORDER BY name");
    }

    @PostMapping("/structures/{id}/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void suspend(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable UUID id) {
        check(authorization);
        jdbc.update("UPDATE portal_structure SET suspended_at = ? WHERE id = ?", Timestamp.from(clock.instant()), id);
    }

    @PostMapping("/supervision-codes")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> supervisionCode(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @RequestBody(required = false) Map<String, String> request) {
        check(authorization);
        return Map.of("activationCode", supervision.newCode(request == null ? null : request.get("label")));
    }

    @GetMapping("/supervision")
    public List<Map<String, Object>> supervision(@RequestHeader(value = "Authorization", required = false) String authorization) {
        check(authorization);
        return supervision.sites();
    }

    /** Historique des alertes (console de l'éditeur, ADR 0017) : ouvertes ou fermées depuis {@code since}. */
    @GetMapping("/supervision/alerts")
    public List<Map<String, Object>> supervisionAlerts(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                       @RequestParam("since") java.time.Instant since) {
        check(authorization);
        return supervision.alertsSince(since);
    }

    @PostMapping("/supervision/{id}/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void suspendSite(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable UUID id) {
        check(authorization);
        jdbc.update("UPDATE supervision_site SET suspended_at = ? WHERE id = ?", Timestamp.from(clock.instant()), id);
    }

    private void check(String authorization) {
        var expected = properties.adminToken();
        if (expected == null || expected.isBlank() || authorization == null
                || !Hashes.equal(authorization, "Bearer " + expected)) {
            throw ApiException.unauthorized("admin_token", "Jeton d’administration requis.");
        }
    }
}
