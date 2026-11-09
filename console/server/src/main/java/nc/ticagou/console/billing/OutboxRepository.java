package nc.ticagou.console.billing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Messages préparés de la boîte d'envoi. */
@Repository
public class OutboxRepository {

    public record Message(UUID id, String kind, UUID invoiceId, UUID clientId, UUID crecheId, String to, String cc, String subject, String body,
                          String status, Instant createdAt, Instant updatedAt, Instant sentAt, String sentBy, String lastError,
                          Instant discardedAt, String discardedBy, String discardReason) {
    }

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Message> list(String status, int limit) {
        return status == null
                ? jdbc.query("SELECT * FROM outbox_message ORDER BY created_at DESC LIMIT ?", OutboxRepository::message, limit)
                : jdbc.query("SELECT * FROM outbox_message WHERE status = ? ORDER BY created_at DESC LIMIT ?", OutboxRepository::message,
                        status, limit);
    }

    public List<Message> forInvoice(UUID invoiceId) {
        return jdbc.query("SELECT * FROM outbox_message WHERE invoice_id = ? ORDER BY created_at DESC", OutboxRepository::message, invoiceId);
    }

    public Optional<Message> lock(UUID id) {
        return jdbc.query("SELECT * FROM outbox_message WHERE id = ? FOR UPDATE", OutboxRepository::message, id).stream().findFirst();
    }

    public int pending() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_message WHERE status = 'PREPARE'", Integer.class);
    }

    public void insert(Message m) {
        jdbc.update("""
                INSERT INTO outbox_message (id, kind, invoice_id, client_id, creche_id, to_address, cc_address, subject, body, status,
                    created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARE', ?, ?)""", m.id(), m.kind(), m.invoiceId(),
                m.clientId(), m.crecheId(), m.to(), m.cc(), m.subject(), m.body(), Timestamp.from(m.createdAt()),
                Timestamp.from(m.createdAt()));
    }

    public void edit(UUID id, String to, String cc, String subject, String body, Instant now) {
        jdbc.update("""
                UPDATE outbox_message SET to_address = ?, cc_address = ?, subject = ?, body = ?, updated_at = ?
                WHERE id = ? AND status = 'PREPARE'""", to, cc, subject, body, Timestamp.from(now), id);
    }

    public void sent(UUID id, String by, Instant now) {
        jdbc.update("""
                UPDATE outbox_message SET status = 'ENVOYE', sent_at = ?, sent_by = ?, last_error = NULL, updated_at = ?
                WHERE id = ? AND status = 'PREPARE'""", Timestamp.from(now), by, Timestamp.from(now), id);
    }

    public void failed(UUID id, String error, Instant now) {
        jdbc.update("UPDATE outbox_message SET last_error = ?, updated_at = ? WHERE id = ?",
                error.length() > 500 ? error.substring(0, 500) : error, Timestamp.from(now), id);
    }

    public void discard(UUID id, String by, String reason, Instant now) {
        jdbc.update("""
                UPDATE outbox_message SET status = 'ECARTE', discarded_at = ?, discarded_by = ?, discard_reason = ?, updated_at = ?
                WHERE id = ? AND status = 'PREPARE'""", Timestamp.from(now), by, reason, Timestamp.from(now), id);
    }

    private static Message message(ResultSet rs, int i) throws SQLException {
        return new Message(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getObject("invoice_id", UUID.class),
                rs.getObject("client_id", UUID.class), rs.getObject("creche_id", UUID.class), rs.getString("to_address"),
                rs.getString("cc_address"), rs.getString("subject"), rs.getString("body"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), instant(rs, "sent_at"),
                rs.getString("sent_by"), rs.getString("last_error"), instant(rs, "discarded_at"), rs.getString("discarded_by"),
                rs.getString("discard_reason"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
