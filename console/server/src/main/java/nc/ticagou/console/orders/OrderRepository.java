package nc.ticagou.console.orders;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Ordres aux instances. */
@Repository
public class OrderRepository {

    public record Order(UUID id, String type, String slug, Map<String, String> params, String status, String reason, String proposedBy,
                        String confirmedBy, Instant createdAt, Instant confirmedAt, Instant startedAt, Instant finishedAt, String output,
                        JsonNode result) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OrderRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<Order> latest(int limit) {
        return jdbc.query("SELECT * FROM instance_order ORDER BY created_at DESC LIMIT ?", this::order, limit);
    }

    public List<Order> forSlug(String slug) {
        return jdbc.query("SELECT * FROM instance_order WHERE slug = ? ORDER BY created_at DESC LIMIT 50", this::order, slug);
    }

    public List<Order> waiting() {
        return jdbc.query("SELECT * FROM instance_order WHERE status = 'EN_ATTENTE' ORDER BY created_at", this::order);
    }

    public Optional<Order> lock(UUID id) {
        return jdbc.query("SELECT * FROM instance_order WHERE id = ? FOR UPDATE", this::order, id).stream().findFirst();
    }

    public Optional<Order> open(String slug, String type) {
        return jdbc.query("SELECT * FROM instance_order WHERE slug = ? AND type = ? AND status IN ('A_CONFIRMER', 'EN_ATTENTE', 'EN_COURS')",
                this::order, slug, type).stream().findFirst();
    }

    public void insert(UUID id, String type, String slug, Map<String, String> params, String status, String reason, String proposedBy,
                       String confirmedBy, Instant now) {
        jdbc.update("""
                INSERT INTO instance_order (id, type, slug, params, status, reason, proposed_by, confirmed_by, created_at, confirmed_at)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)""", id, type, slug, text(params), status, reason, proposedBy, confirmedBy,
                Timestamp.from(now), "EN_ATTENTE".equals(status) ? Timestamp.from(now) : null);
    }

    public int confirm(UUID id, String by, Instant now) {
        return jdbc.update("UPDATE instance_order SET status = 'EN_ATTENTE', confirmed_by = ?, confirmed_at = ? WHERE id = ? AND status = 'A_CONFIRMER'",
                by, Timestamp.from(now), id);
    }

    public int cancel(UUID id, String by, Instant now) {
        return jdbc.update("""
                UPDATE instance_order SET status = 'ANNULE', finished_at = ?, output = coalesce(output, '') || ?
                WHERE id = ? AND status IN ('A_CONFIRMER', 'EN_ATTENTE', 'EN_COURS')""", Timestamp.from(now), "Annulé par " + by + ".", id);
    }

    /** Prise en charge par l'hôte : une seule fois (deux passages de cron ne l'exécutent pas deux fois). */
    public int claim(UUID id, Instant now) {
        return jdbc.update("UPDATE instance_order SET status = 'EN_COURS', started_at = ? WHERE id = ? AND status = 'EN_ATTENTE'",
                Timestamp.from(now), id);
    }

    public int finish(UUID id, boolean ok, String output, JsonNode result, Instant now) {
        return jdbc.update("""
                UPDATE instance_order SET status = ?, finished_at = ?, output = ?, result = ?::jsonb WHERE id = ? AND status = 'EN_COURS'""",
                ok ? "FAIT" : "ECHEC", Timestamp.from(now), output, result == null ? null : result.toString(), id);
    }

    /** Résultat complété après coup (export lu en entier, téléchargé, ordre de suppression créé). */
    public void result(UUID id, JsonNode result) {
        jdbc.update("UPDATE instance_order SET result = ?::jsonb WHERE id = ?", result.toString(), id);
    }

    private Order order(ResultSet rs, int i) throws SQLException {
        Map<String, String> params;
        JsonNode result;
        try {
            params = json.readValue(rs.getString("params"), new TypeReference<LinkedHashMap<String, String>>() { });
            result = rs.getString("result") == null ? null : json.readTree(rs.getString("result"));
        } catch (Exception e) {
            throw new SQLException(e);
        }
        return new Order(rs.getObject("id", UUID.class), rs.getString("type"), rs.getString("slug"), params, rs.getString("status"),
                rs.getString("reason"), rs.getString("proposed_by"), rs.getString("confirmed_by"), instant(rs, "created_at"),
                instant(rs, "confirmed_at"), instant(rs, "started_at"), instant(rs, "finished_at"), rs.getString("output"), result);
    }

    private String text(Map<String, String> params) {
        try {
            return json.writeValueAsString(params);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
