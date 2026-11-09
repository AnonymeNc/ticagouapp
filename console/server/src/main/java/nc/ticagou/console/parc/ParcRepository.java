package nc.ticagou.console.parc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Tables du parc : crèches, clients, états reçus, instantanés, alertes, notes, lectures des sources. */
@Repository
public class ParcRepository {

    public record Creche(UUID id, String slug, String name, String city, String lifecycle, LocalDate clientSince, UUID clientId,
                         UUID supervisionSiteId, String supervisionName, Instant supervisionSeenAt, JsonNode supervisionStatus,
                         boolean supervisionSuspended, Instant hostSeenAt, JsonNode host, Instant createdAt) {
    }

    public record Client(UUID id, String name, String kind, String ridet, String address, String directorName,
                         String directorEmail, String directorPhone, String billingEmail, String reminderEmail, int paymentDays) {
    }

    public record Alert(long id, UUID crecheId, String kind, String detail, Instant openedAt, Instant closedAt) {
    }

    public record Snapshot(Instant at, JsonNode data) {
    }

    public record Note(long id, Instant at, String operatorLogin, String kind, String text) {
    }

    public record Collector(String source, Instant lastAttemptAt, Instant lastSuccessAt, String lastError, Instant cursorAt,
                            JsonNode data) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ParcRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // --- Crèches

    private static final String CRECHE = """
            SELECT id, slug, name, city, lifecycle, client_since, client_id, supervision_site_id, supervision_name,
                   supervision_seen_at, supervision_status::text AS supervision_status, supervision_suspended, host_seen_at,
                   host::text AS host, created_at FROM creche""";

    public List<Creche> all() {
        return jdbc.query(CRECHE + " ORDER BY lower(name)", this::creche);
    }

    public Optional<Creche> find(String slug) {
        return jdbc.query(CRECHE + " WHERE slug = ?", this::creche, slug).stream().findFirst();
    }

    public Optional<Creche> findById(UUID id) {
        return jdbc.query(CRECHE + " WHERE id = ?", this::creche, id).stream().findFirst();
    }

    public void lifecycle(String slug, String lifecycle, Instant now) {
        jdbc.update("UPDATE creche SET lifecycle = ?, updated_at = ? WHERE slug = ?", lifecycle, Timestamp.from(now), slug);
    }

    /** Démonstration supprimée : sa fiche disparaît, avec son client (une démo n'a ni contrat ni facture). */
    public void deleteDemo(String slug) {
        var clientId = jdbc.queryForList("SELECT client_id FROM creche WHERE slug = ? AND lifecycle = 'DEMO'", UUID.class, slug);
        if (clientId.isEmpty()) {
            return;
        }
        jdbc.update("DELETE FROM creche WHERE slug = ? AND lifecycle = 'DEMO'", slug);
        jdbc.update("DELETE FROM client c WHERE id = ? AND NOT EXISTS (SELECT 1 FROM creche k WHERE k.client_id = c.id)", clientId.getFirst());
    }

    /** Contrat signé : un prospect devient client, à la date de début du contrat si rien n'était noté. */
    public void becomesClient(UUID id, LocalDate since, Instant now) {
        jdbc.update("""
                UPDATE creche SET lifecycle = CASE WHEN lifecycle = 'PROSPECT' THEN 'CLIENT' ELSE lifecycle END,
                    client_since = coalesce(client_since, ?), updated_at = ? WHERE id = ?""", java.sql.Date.valueOf(since), Timestamp.from(now), id);
    }

    /** Crèche connue, ou enregistrée (avec son client) à sa première apparition ; vrai si elle vient d'être créée. */
    public boolean ensure(String slug, String name, String lifecycle, Instant now) {
        if (find(slug).isPresent()) {
            return false;
        }
        var clientId = UUID.randomUUID();
        var at = Timestamp.from(now);
        jdbc.update("INSERT INTO client (id, name, created_at, updated_at) VALUES (?, ?, ?, ?)", clientId, name, at, at);
        int inserted = jdbc.update("""
                INSERT INTO creche (id, slug, name, client_id, lifecycle, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (slug) DO NOTHING""", UUID.randomUUID(), slug, name, clientId, lifecycle, at, at);
        if (inserted == 0) {
            jdbc.update("DELETE FROM client WHERE id = ?", clientId);
        }
        return inserted == 1;
    }

    /** Crèche connue de l'hôte avant la supervision : elle prend le nom de la structure tant qu'on ne l'a pas renommée. */
    public void nameFromSupervision(String slug, String name) {
        jdbc.update("UPDATE client SET name = ? WHERE name = ? AND id = (SELECT client_id FROM creche WHERE slug = ?)", name, slug, slug);
        jdbc.update("UPDATE creche SET name = ? WHERE slug = ? AND name = slug", name, slug);
    }

    public void supervision(String slug, UUID siteId, String name, Instant seenAt, JsonNode status, boolean suspended) {
        jdbc.update("""
                UPDATE creche SET supervision_site_id = ?, supervision_name = ?, supervision_seen_at = ?,
                    supervision_status = ?::jsonb, supervision_suspended = ? WHERE slug = ?""",
                siteId, name, seenAt == null ? null : Timestamp.from(seenAt), text(status), suspended, slug);
    }

    public void host(String slug, Instant at, JsonNode host) {
        jdbc.update("UPDATE creche SET host_seen_at = ?, host = ?::jsonb WHERE slug = ?", Timestamp.from(at), text(host), slug);
    }

    /** Crèches que l'hôte ne cite plus : leur conteneur est marqué absent (la fiche reste). */
    public int hostAbsent(Collection<String> reported) {
        var absent = json.createObjectNode().put("container", "absent");
        var slugs = reported.toArray(String[]::new);
        return jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    UPDATE creche SET host = ?::jsonb WHERE host IS NOT NULL AND host->>'container' <> 'absent'
                      AND NOT (slug = ANY (?))""");
            ps.setString(1, absent.toString());
            ps.setArray(2, con.createArrayOf("varchar", slugs));
            return ps;
        });
    }

    public void updateCreche(UUID id, String name, String city, String lifecycle, LocalDate clientSince, Instant now) {
        jdbc.update("UPDATE creche SET name = ?, city = ?, lifecycle = ?, client_since = ?, updated_at = ? WHERE id = ?",
                name, city, lifecycle, clientSince, Timestamp.from(now), id);
    }

    // --- Clients

    public Client client(UUID id) {
        return jdbc.queryForObject("SELECT * FROM client WHERE id = ?", (rs, i) -> new Client(rs.getObject("id", UUID.class),
                rs.getString("name"), rs.getString("kind"), rs.getString("ridet"), rs.getString("address"),
                rs.getString("director_name"), rs.getString("director_email"), rs.getString("director_phone"),
                rs.getString("billing_email"), rs.getString("reminder_email"), rs.getInt("payment_days")), id);
    }

    public Map<UUID, String> clientNames() {
        var names = new HashMap<UUID, String>();
        jdbc.query("SELECT id, name FROM client", rs -> {
            names.put(rs.getObject("id", UUID.class), rs.getString("name"));
        });
        return names;
    }

    public void updateClient(Client c, Instant now) {
        jdbc.update("""
                UPDATE client SET name = ?, kind = ?, ridet = ?, address = ?, director_name = ?, director_email = ?,
                    director_phone = ?, billing_email = ?, reminder_email = ?, payment_days = ?, updated_at = ? WHERE id = ?""",
                c.name(), c.kind(), c.ridet(), c.address(), c.directorName(), c.directorEmail(), c.directorPhone(),
                c.billingEmail(), c.reminderEmail(), c.paymentDays(), Timestamp.from(now), c.id());
    }

    // --- Instantanés

    public void snapshot(UUID crecheId, Instant at, JsonNode data) {
        jdbc.update("INSERT INTO creche_snapshot (creche_id, at, data) VALUES (?, ?, ?::jsonb)", crecheId, Timestamp.from(at), text(data));
    }

    /** Un point par jour (le dernier de la journée, heure de Nouméa) depuis {@code since}. */
    public List<Snapshot> daily(UUID crecheId, Instant since) {
        return jdbc.query("""
                SELECT DISTINCT ON ((at AT TIME ZONE 'Pacific/Noumea')::date) at, data::text AS data FROM creche_snapshot
                WHERE creche_id = ? AND at >= ? ORDER BY (at AT TIME ZONE 'Pacific/Noumea')::date, at DESC""",
                (rs, i) -> new Snapshot(rs.getTimestamp("at").toInstant(), node(rs.getString("data"))), crecheId, Timestamp.from(since));
    }

    /** Avant {@code before}, ne garde que le dernier instantané de chaque jour ; rend le nombre de lignes effacées. */
    public int reduce(Instant before) {
        return jdbc.update("""
                DELETE FROM creche_snapshot s WHERE s.at < ? AND EXISTS (
                    SELECT 1 FROM creche_snapshot t WHERE t.creche_id = s.creche_id AND t.at > s.at
                      AND (t.at AT TIME ZONE 'Pacific/Noumea')::date = (s.at AT TIME ZONE 'Pacific/Noumea')::date)""",
                Timestamp.from(before));
    }

    // --- Alertes

    public void alert(long id, UUID crecheId, String kind, String detail, Instant openedAt, Instant closedAt) {
        jdbc.update("""
                INSERT INTO alert_history (id, creche_id, kind, detail, opened_at, closed_at) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET detail = excluded.detail, closed_at = excluded.closed_at""",
                id, crecheId, kind, detail, Timestamp.from(openedAt), closedAt == null ? null : Timestamp.from(closedAt));
    }

    public Map<UUID, List<Alert>> openAlerts() {
        var result = new HashMap<UUID, List<Alert>>();
        jdbc.query("SELECT * FROM alert_history WHERE closed_at IS NULL ORDER BY opened_at", rs -> {
            var alert = alert(rs);
            result.computeIfAbsent(alert.crecheId(), k -> new ArrayList<>()).add(alert);
        });
        return result;
    }

    public List<Alert> alerts(UUID crecheId, Instant since) {
        return jdbc.query("""
                SELECT * FROM alert_history WHERE creche_id = ? AND (closed_at IS NULL OR closed_at >= ?)
                ORDER BY opened_at DESC LIMIT 200""", (rs, i) -> alert(rs), crecheId, Timestamp.from(since));
    }

    // --- Notes

    public void note(UUID crecheId, Instant at, String login, String kind, String text) {
        jdbc.update("INSERT INTO creche_note (creche_id, at, operator_login, kind, text) VALUES (?, ?, ?, ?, ?)",
                crecheId, Timestamp.from(at), login, kind, text);
    }

    public List<Note> notes(UUID crecheId) {
        return jdbc.query("SELECT * FROM creche_note WHERE creche_id = ? ORDER BY at DESC, id DESC LIMIT 200",
                (rs, i) -> new Note(rs.getLong("id"), rs.getTimestamp("at").toInstant(), rs.getString("operator_login"),
                        rs.getString("kind"), rs.getString("text")), crecheId);
    }

    public Map<UUID, Instant> lastCalls() {
        var result = new HashMap<UUID, Instant>();
        jdbc.query("SELECT creche_id, max(at) AS at FROM creche_note WHERE kind = 'APPEL' GROUP BY creche_id",
                rs -> {
                    result.put(rs.getObject("creche_id", UUID.class), rs.getTimestamp("at").toInstant());
                });
        return result;
    }

    // --- Sources

    public Collector collector(String source) {
        return jdbc.queryForObject("SELECT source, last_attempt_at, last_success_at, last_error, cursor_at, data::text AS data FROM collector WHERE source = ?",
                (rs, i) -> new Collector(rs.getString("source"), instant(rs, "last_attempt_at"), instant(rs, "last_success_at"),
                        rs.getString("last_error"), instant(rs, "cursor_at"), node(rs.getString("data"))), source);
    }

    public void collected(String source, Instant at, Instant cursor, JsonNode data) {
        jdbc.update("""
                UPDATE collector SET last_attempt_at = ?, last_success_at = ?, last_error = NULL,
                    cursor_at = coalesce(?, cursor_at), data = coalesce(?::jsonb, data) WHERE source = ?""",
                Timestamp.from(at), Timestamp.from(at), cursor == null ? null : Timestamp.from(cursor), text(data), source);
    }

    public void failed(String source, Instant at, String error) {
        var message = error == null ? "Erreur" : error.length() > 500 ? error.substring(0, 500) : error;
        jdbc.update("UPDATE collector SET last_attempt_at = ?, last_error = ? WHERE source = ?", Timestamp.from(at), message, source);
    }

    // --- Outils

    private Creche creche(ResultSet rs, int i) throws SQLException {
        var since = rs.getDate("client_since");
        return new Creche(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name"), rs.getString("city"),
                rs.getString("lifecycle"), since == null ? null : since.toLocalDate(), rs.getObject("client_id", UUID.class),
                rs.getObject("supervision_site_id", UUID.class), rs.getString("supervision_name"), instant(rs, "supervision_seen_at"),
                node(rs.getString("supervision_status")), rs.getBoolean("supervision_suspended"), instant(rs, "host_seen_at"),
                node(rs.getString("host")), rs.getTimestamp("created_at").toInstant());
    }

    private static Alert alert(ResultSet rs) throws SQLException {
        return new Alert(rs.getLong("id"), rs.getObject("creche_id", UUID.class), rs.getString("kind"), rs.getString("detail"),
                rs.getTimestamp("opened_at").toInstant(), instant(rs, "closed_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    JsonNode node(String text) {
        if (text == null) {
            return null;
        }
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.toString();
    }
}
