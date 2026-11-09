package nc.ticagou.console.billing;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Paiements reçus et leur affectation aux factures ; ce qui n'est pas affecté est un trop-perçu du client. */
@Repository
public class PaymentRepository {

    public record Payment(UUID id, UUID clientId, LocalDate receivedOn, long amount, String method, String reference, String note,
                          String operatorLogin, Instant createdAt, long allocated) {

        public long unallocated() {
            return amount - allocated;
        }
    }

    public record Allocation(UUID paymentId, UUID invoiceId, String invoiceNumber, LocalDate receivedOn, String method, String reference,
                             long amount) {
    }

    private static final String SELECT = """
            SELECT p.*, coalesce((SELECT sum(a.amount) FROM payment_allocation a WHERE a.payment_id = p.id), 0) AS allocated
            FROM payment p""";

    private final JdbcTemplate jdbc;

    public PaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Payment> all() {
        return jdbc.query(SELECT + " ORDER BY p.received_on DESC, p.created_at DESC", this::payment);
    }

    public List<Payment> forClient(UUID clientId) {
        return jdbc.query(SELECT + " WHERE p.client_id = ? ORDER BY p.received_on DESC", this::payment, clientId);
    }

    public Optional<Payment> find(UUID id) {
        return jdbc.query(SELECT + " WHERE p.id = ?", this::payment, id).stream().findFirst();
    }

    public List<Allocation> allocationsOf(UUID invoiceId) {
        return allocations("a.invoice_id = ?", invoiceId);
    }

    public List<Allocation> allocationsFrom(UUID paymentId) {
        return allocations("a.payment_id = ?", paymentId);
    }

    private List<Allocation> allocations(String where, UUID id) {
        return jdbc.query("""
                SELECT a.payment_id, a.invoice_id, i.number, p.received_on, p.method, p.reference, a.amount
                FROM payment_allocation a JOIN payment p ON p.id = a.payment_id JOIN invoice i ON i.id = a.invoice_id
                WHERE\s""" + where + " ORDER BY p.received_on, i.number", (rs, i) -> new Allocation(rs.getObject("payment_id", UUID.class),
                rs.getObject("invoice_id", UUID.class), rs.getString("number"), rs.getDate("received_on").toLocalDate(),
                rs.getString("method"), rs.getString("reference"), rs.getLong("amount")), id);
    }

    public void insert(UUID id, UUID clientId, LocalDate receivedOn, long amount, String method, String reference, String note,
                       String operator, Instant now) {
        jdbc.update("""
                INSERT INTO payment (id, client_id, received_on, amount, method, reference, note, operator_login, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, clientId, Date.valueOf(receivedOn), amount, method, reference, note, operator,
                Timestamp.from(now));
    }

    public void allocate(UUID paymentId, UUID invoiceId, long amount) {
        jdbc.update("INSERT INTO payment_allocation (id, payment_id, invoice_id, amount) VALUES (?, ?, ?, ?)", UUID.randomUUID(), paymentId,
                invoiceId, amount);
    }

    public void delete(UUID id) {
        jdbc.update("DELETE FROM payment WHERE id = ?", id);
    }

    private Payment payment(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Payment(rs.getObject("id", UUID.class), rs.getObject("client_id", UUID.class), rs.getDate("received_on").toLocalDate(),
                rs.getLong("amount"), rs.getString("method"), rs.getString("reference"), rs.getString("note"), rs.getString("operator_login"),
                rs.getTimestamp("created_at").toInstant(), rs.getLong("allocated"));
    }
}
