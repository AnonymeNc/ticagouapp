package nc.ticagou.portail.supervision;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import nc.ticagou.portail.PortalProperties;
import nc.ticagou.portail.auth.Mailer;
import nc.ticagou.portail.common.ApiException;
import nc.ticagou.portail.common.Hashes;

/**
 * Supervision (ADR 0012) : activation d'un serveur de crèche par un code à usage unique, réception de ses états,
 * ouverture et fermeture des alertes (un e-mail chacune) et récapitulatif quotidien au prestataire.
 */
@Service
public class SupervisionService {

    private static final Logger log = LoggerFactory.getLogger(SupervisionService.class);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(AlertRules.NOUMEA);

    /** Champs acceptés dans un état : tout le reste est ignoré (aucune donnée personnelle n'est attendue). */
    static final List<String> FIELDS = List.of("version", "database", "backup", "disk", "license", "tablets", "portal",
            "attendanceToReview", "usage");

    /** Slug d'une crèche hébergée (deploy/creche.sh) : le libellé du code, quand il en a la forme. */
    private static final java.util.regex.Pattern SLUG = java.util.regex.Pattern.compile("^[a-z0-9][a-z0-9-]{1,40}$");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Mailer mailer;
    private final PortalProperties properties;
    private final Clock clock;

    public SupervisionService(JdbcTemplate jdbc, ObjectMapper json, Mailer mailer, PortalProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.mailer = mailer;
        this.properties = properties;
        this.clock = clock;
    }

    public String newCode(String label) {
        var raw = Hashes.token(9).toUpperCase().replaceAll("[^A-Z0-9]", "X");
        var code = "S" + raw.substring(0, 3) + "-" + raw.substring(3, 7) + "-" + raw.substring(7, 11);
        var now = clock.instant();
        jdbc.update("INSERT INTO supervision_code (code_hash, label, created_at, expires_at) VALUES (?, ?, ?, ?)",
                Hashes.sha256(code), label, Timestamp.from(now), Timestamp.from(now.plus(Duration.ofDays(30))));
        return code;
    }

    @Transactional
    public UUID activate(String code, String publicKeyBase64, String name) {
        byte[] publicKey;
        try {
            publicKey = Base64.getDecoder().decode(publicKeyBase64 == null ? "" : publicKeyBase64.strip());
            java.security.KeyFactory.getInstance("Ed25519").generatePublic(new java.security.spec.X509EncodedKeySpec(publicKey));
        } catch (IllegalArgumentException | java.security.GeneralSecurityException e) {
            throw ApiException.rule("bad_public_key", "Clé publique invalide.");
        }
        var now = clock.instant();
        var hash = Hashes.sha256(code == null ? "" : code.strip().toUpperCase());
        int claimed = jdbc.update("UPDATE supervision_code SET used_at = ? WHERE code_hash = ? AND used_at IS NULL AND expires_at > ?",
                Timestamp.from(now), hash, Timestamp.from(now));
        if (claimed != 1) {
            throw ApiException.forbidden("activation_code", "Code de supervision inconnu, expiré ou déjà utilisé.");
        }
        var id = UUID.randomUUID();
        var clean = name == null || name.isBlank() ? "Crèche" : name.strip();
        var label = jdbc.queryForObject("SELECT label FROM supervision_code WHERE code_hash = ?", String.class, hash);
        var slug = label != null && SLUG.matcher(label).matches() ? label : null;
        jdbc.update("INSERT INTO supervision_site (id, name, slug, public_key, activated_at) VALUES (?, ?, ?, ?, ?)",
                id, clean.length() > 150 ? clean.substring(0, 150) : clean, slug, publicKey, Timestamp.from(now));
        jdbc.update("UPDATE supervision_code SET site_id = ? WHERE code_hash = ?", id, hash);
        return id;
    }

    /** État reçu : seuls les champs connus sont gardés, puis les alertes du site sont réévaluées. */
    @Transactional
    public void record(UUID siteId, JsonNode status) {
        var kept = json.createObjectNode();
        FIELDS.forEach(f -> {
            if (status.has(f)) {
                kept.set(f, status.get(f));
            }
        });
        var now = clock.instant();
        jdbc.update("UPDATE supervision_site SET last_seen_at = ?, last_status = ?::jsonb WHERE id = ?",
                Timestamp.from(now), kept.toString(), siteId);
        evaluate(siteId, now);
    }

    /** Toutes les 15 minutes : un serveur muet ne déclenche rien de lui-même. */
    @Scheduled(cron = "0 */15 * * * *", zone = "Pacific/Noumea")
    public void scheduled() {
        if (properties.scheduling()) {
            evaluateAll();
        }
    }

    @Transactional
    public void evaluateAll() {
        var now = clock.instant();
        for (var id : jdbc.queryForList("SELECT id FROM supervision_site WHERE suspended_at IS NULL", UUID.class)) {
            evaluate(id, now);
        }
    }

    private void evaluate(UUID siteId, Instant now) {
        var row = jdbc.queryForMap("SELECT name, last_seen_at, last_status::text AS status FROM supervision_site WHERE id = ?", siteId);
        var name = (String) row.get("name");
        var lastSeen = row.get("last_seen_at") == null ? null : ((Timestamp) row.get("last_seen_at")).toInstant();
        JsonNode status;
        try {
            status = row.get("status") == null ? null : json.readTree((String) row.get("status"));
        } catch (Exception e) {
            status = null;
        }
        var current = AlertRules.evaluate(status, lastSeen, now);
        var open = jdbc.queryForList("SELECT kind FROM supervision_alert WHERE site_id = ? AND closed_at IS NULL", String.class, siteId);
        for (var entry : current.entrySet()) {
            if (!open.contains(entry.getKey())) {
                jdbc.update("INSERT INTO supervision_alert (site_id, kind, detail, opened_at) VALUES (?, ?, ?, ?)",
                        siteId, entry.getKey(), entry.getValue(), Timestamp.from(now));
                notify("Alerte — %s — %s".formatted(name, AlertRules.label(entry.getKey())), entry.getValue());
            }
        }
        for (var kind : open) {
            if (!current.containsKey(kind)) {
                jdbc.update("UPDATE supervision_alert SET closed_at = ? WHERE site_id = ? AND kind = ? AND closed_at IS NULL",
                        Timestamp.from(now), siteId, kind);
                notify("Résolu — %s — %s".formatted(name, AlertRules.label(kind)), "L’alerte « %s » est résolue.".formatted(AlertRules.label(kind)));
            }
        }
    }

    /** Récapitulatif de 7 h : chaque site, son dernier contact et ses alertes en cours. */
    @Scheduled(cron = "0 0 7 * * *", zone = "Pacific/Noumea")
    public void dailyDigest() {
        if (properties.scheduling()) {
            sendDigest();
        }
    }

    public void sendDigest() {
        var sites = sites();
        if (sites.isEmpty()) {
            return;
        }
        var text = new StringBuilder("Supervision des serveurs Ti'Cagou\n\n");
        int alerts = 0;
        for (var site : sites) {
            @SuppressWarnings("unchecked")
            var open = (List<Map<String, Object>>) site.get("alerts");
            alerts += open.size();
            text.append("• ").append(site.get("name")).append(" — dernier contact : ")
                    .append(site.get("lastSeenAt") == null ? "jamais" : DATE_TIME.format((Instant) site.get("lastSeenAt")))
                    .append(", version ").append(site.getOrDefault("version", "?")).append("\n");
            for (var alert : open) {
                text.append("    ⚠ ").append(AlertRules.label((String) alert.get("kind"))).append(" : ").append(alert.get("detail")).append("\n");
            }
            if (open.isEmpty()) {
                text.append("    Tout va bien.\n");
            }
        }
        notify("Récapitulatif du jour — %d site%s, %d alerte%s".formatted(sites.size(), sites.size() > 1 ? "s" : "",
                alerts, alerts > 1 ? "s" : ""), text.toString());
    }

    /** Liste pour l'hébergeur : état résumé et alertes en cours (aucune donnée personnelle). */
    public List<Map<String, Object>> sites() {
        var result = new ArrayList<Map<String, Object>>();
        jdbc.query("SELECT id, name, slug, activated_at, last_seen_at, last_status::text AS status, suspended_at FROM supervision_site ORDER BY name", rs -> {
            var id = rs.getObject("id", UUID.class);
            var site = new java.util.LinkedHashMap<String, Object>();
            site.put("id", id);
            site.put("name", rs.getString("name"));
            site.put("slug", rs.getString("slug"));
            site.put("activatedAt", rs.getTimestamp("activated_at").toInstant());
            site.put("lastSeenAt", rs.getTimestamp("last_seen_at") == null ? null : rs.getTimestamp("last_seen_at").toInstant());
            site.put("suspended", rs.getTimestamp("suspended_at") != null);
            try {
                var status = rs.getString("status") == null ? null : json.readTree(rs.getString("status"));
                site.put("version", status == null ? null : status.path("version").asText(null));
                site.put("status", status);
            } catch (Exception e) {
                site.put("status", null);
            }
            site.put("alerts", jdbc.queryForList("SELECT kind, detail, opened_at FROM supervision_alert WHERE site_id = ? AND closed_at IS NULL ORDER BY opened_at", id));
            result.add(site);
        });
        return result;
    }

    /** Alertes ouvertes ou fermées depuis {@code since}, pour l'historique de la console de l'éditeur. */
    public List<Map<String, Object>> alertsSince(Instant since) {
        return jdbc.query("""
                SELECT a.id, a.site_id, s.slug, a.kind, a.detail, a.opened_at, a.closed_at
                FROM supervision_alert a JOIN supervision_site s ON s.id = a.site_id
                WHERE a.opened_at >= ? OR a.closed_at >= ? ORDER BY a.id""", (rs, i) -> {
            var alert = new java.util.LinkedHashMap<String, Object>();
            alert.put("id", rs.getLong("id"));
            alert.put("siteId", rs.getObject("site_id", UUID.class));
            alert.put("slug", rs.getString("slug"));
            alert.put("kind", rs.getString("kind"));
            alert.put("detail", rs.getString("detail"));
            alert.put("openedAt", rs.getTimestamp("opened_at").toInstant());
            alert.put("closedAt", rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant());
            return alert;
        }, Timestamp.from(since), Timestamp.from(since));
    }

    private void notify(String subject, String text) {
        var to = properties.supervisionEmail();
        if (to == null || to.isBlank()) {
            log.info("Supervision (pas d’adresse PORTAL_SUPERVISION_EMAIL) : {}", subject);
            return;
        }
        mailer.send(to, "[Ti'Cagou] " + subject, text + "\n\n— Supervision Ti'Cagou\n");
    }
}
