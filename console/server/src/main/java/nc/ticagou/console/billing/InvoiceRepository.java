package nc.ticagou.console.billing;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Factures et avoirs, leurs lignes, la numérotation, les sommes payées et créditées. */
@Repository
public class InvoiceRepository {

    public record Invoice(UUID id, String type, String number, String status, String kind, UUID clientId, UUID crecheId, UUID contractId,
                          UUID correctsId, String subject, LocalDate periodFrom, LocalDate periodTo, LocalDate issuedOn,
                          LocalDate dueOn, long totalHt, long totalTgc, long totalTtc, JsonNode tgcDetail, JsonNode clientSnapshot,
                          JsonNode issuerSnapshot, String note, String pdfSha256, Instant sentAt, boolean remindersPaused,
                          Instant validatedAt, String validatedBy, Instant createdAt) {

        public boolean draft() {
            return "BROUILLON".equals(status);
        }

        public boolean credit() {
            return "AVOIR".equals(type);
        }
    }

    public record Line(UUID id, int position, String label, String detail, int quantity, long unitPrice, long amount, String tgcNature,
                       int tgcRate, UUID creditsLineId) {
    }

    /** Sommes rattachées à une facture : payé (affectations) et crédité (avoirs validés). */
    public record Settlement(long paid, long credited) {
    }

    private static final String COLUMNS = """
            id, type, number, status, kind, client_id, creche_id, contract_id, corrects_id, subject, period_from, period_to,
            issued_on, due_on, total_ht, total_tgc, total_ttc, tgc_detail::text AS tgc_detail, client_snapshot::text AS client_snapshot,
            issuer_snapshot::text AS issuer_snapshot, note, pdf_sha256, sent_at, reminders_paused, validated_at, validated_by, created_at""";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public InvoiceRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<Invoice> all() {
        return jdbc.query("SELECT " + COLUMNS + " FROM invoice ORDER BY coalesce(issued_on, created_at::date) DESC, number DESC NULLS FIRST",
                this::invoice);
    }

    public List<Invoice> forClient(UUID clientId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM invoice WHERE client_id = ? ORDER BY coalesce(issued_on, created_at::date) DESC",
                this::invoice, clientId);
    }

    public Optional<Invoice> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM invoice WHERE id = ?", this::invoice, id).stream().findFirst();
    }

    /** Verrou de la facture pour la durée de la transaction (validation, paiement, avoir). */
    public Optional<Invoice> lock(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM invoice WHERE id = ? FOR UPDATE", this::invoice, id).stream().findFirst();
    }

    public List<Invoice> creditsOf(UUID invoiceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM invoice WHERE corrects_id = ? ORDER BY created_at", this::invoice, invoiceId);
    }

    public List<Line> lines(UUID invoiceId) {
        return jdbc.query("SELECT * FROM invoice_line WHERE invoice_id = ? ORDER BY position", (rs, i) -> new Line(
                rs.getObject("id", UUID.class), rs.getInt("position"), rs.getString("label"), rs.getString("detail"), rs.getInt("quantity"),
                rs.getLong("unit_price"), rs.getLong("amount"), rs.getString("tgc_nature"), rs.getInt("tgc_rate"),
                rs.getObject("credits_line_id", UUID.class)), invoiceId);
    }

    /** Payé et crédité, pour toutes les factures validées. */
    public Map<UUID, Settlement> settlements() {
        var result = new HashMap<UUID, Settlement>();
        jdbc.query("""
                SELECT i.id,
                       coalesce((SELECT sum(a.amount) FROM payment_allocation a WHERE a.invoice_id = i.id), 0) AS paid,
                       coalesce((SELECT sum(c.total_ttc) FROM invoice c WHERE c.corrects_id = i.id AND c.status = 'VALIDEE'), 0) AS credited
                FROM invoice i WHERE i.type = 'FACTURE' AND i.status = 'VALIDEE'""", rs -> {
            result.put(rs.getObject("id", UUID.class), new Settlement(rs.getLong("paid"), rs.getLong("credited")));
        });
        return result;
    }

    public Settlement settlement(UUID invoiceId) {
        return jdbc.queryForObject("""
                SELECT coalesce((SELECT sum(a.amount) FROM payment_allocation a WHERE a.invoice_id = ?), 0) AS paid,
                       coalesce((SELECT sum(c.total_ttc) FROM invoice c WHERE c.corrects_id = ? AND c.status = 'VALIDEE'), 0) AS credited""",
                (rs, i) -> new Settlement(rs.getLong("paid"), rs.getLong("credited")), invoiceId, invoiceId);
    }

    /** Montant HT déjà rendu par avoir sur chaque ligne d'une facture. */
    public Map<UUID, Long> creditedByLine(UUID invoiceId) {
        var result = new HashMap<UUID, Long>();
        jdbc.query("""
                SELECT l.credits_line_id, sum(l.amount) AS amount FROM invoice_line l JOIN invoice c ON c.id = l.invoice_id
                WHERE c.corrects_id = ? AND c.status = 'VALIDEE' AND l.credits_line_id IS NOT NULL GROUP BY l.credits_line_id""", rs -> {
            result.put(rs.getObject("credits_line_id", UUID.class), rs.getLong("amount"));
        }, invoiceId);
        return result;
    }

    // --- Écritures

    public void insert(Invoice i, Instant now) {
        jdbc.update("""
                INSERT INTO invoice (id, type, status, kind, client_id, creche_id, contract_id, corrects_id, subject, period_from,
                    period_to, due_on, note, created_at, updated_at)
                VALUES (?, ?, 'BROUILLON', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", i.id(), i.type(), i.kind(), i.clientId(), i.crecheId(),
                i.contractId(), i.correctsId(), i.subject(), date(i.periodFrom()), date(i.periodTo()), date(i.dueOn()), i.note(),
                Timestamp.from(now), Timestamp.from(now));
    }

    public void updateDraft(UUID id, String subject, LocalDate periodFrom, LocalDate periodTo, String note, Instant now) {
        jdbc.update("UPDATE invoice SET subject = ?, period_from = ?, period_to = ?, note = ?, updated_at = ? WHERE id = ?",
                subject, date(periodFrom), date(periodTo), note, Timestamp.from(now), id);
    }

    public void replaceLines(UUID invoiceId, List<Line> lines) {
        jdbc.update("DELETE FROM invoice_line WHERE invoice_id = ?", invoiceId);
        int position = 1;
        for (var l : lines) {
            jdbc.update("""
                    INSERT INTO invoice_line (id, invoice_id, position, label, detail, quantity, unit_price, amount, tgc_nature, tgc_rate,
                        credits_line_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", UUID.randomUUID(), invoiceId, position++, l.label(),
                    l.detail(), l.quantity(), l.unitPrice(), l.amount(), l.tgcNature(), l.tgcRate(), l.creditsLineId());
        }
    }

    /** Totaux d'un brouillon (aperçu) ; figés pour de bon à la validation. */
    public void totals(UUID id, Totals.Result totals, JsonNode detail) {
        jdbc.update("UPDATE invoice SET total_ht = ?, total_tgc = ?, total_ttc = ?, tgc_detail = ?::jsonb WHERE id = ?", totals.ht(),
                totals.tgc(), totals.ttc(), detail.toString(), id);
    }

    public void delete(UUID id) {
        jdbc.update("DELETE FROM invoice WHERE id = ?", id);
    }

    /** Validation : numéro, dates, mentions, PDF et son empreinte. Ensuite la ligne ne change plus (trigger). */
    public void validate(UUID id, String number, LocalDate issuedOn, LocalDate dueOn, JsonNode clientSnapshot, JsonNode issuerSnapshot,
                         byte[] pdf, String sha256, String operator, Instant now) {
        jdbc.update("""
                UPDATE invoice SET number = ?, status = 'VALIDEE', issued_on = ?, due_on = ?, client_snapshot = ?::jsonb,
                    issuer_snapshot = ?::jsonb, pdf = ?, pdf_sha256 = ?, validated_at = ?, validated_by = ?, updated_at = ?
                WHERE id = ? AND status = 'BROUILLON'""", number, date(issuedOn), date(dueOn), clientSnapshot.toString(),
                issuerSnapshot.toString(), pdf, sha256, Timestamp.from(now), operator, Timestamp.from(now), id);
    }

    public void markSent(UUID id, Instant at) {
        jdbc.update("UPDATE invoice SET sent_at = coalesce(sent_at, ?), updated_at = ? WHERE id = ?", Timestamp.from(at), Timestamp.from(at), id);
    }

    public void pauseReminders(UUID id, boolean paused, Instant now) {
        jdbc.update("UPDATE invoice SET reminders_paused = ?, updated_at = ? WHERE id = ?", paused, Timestamp.from(now), id);
    }

    public byte[] pdf(UUID id) {
        return jdbc.query("SELECT pdf FROM invoice WHERE id = ?", (rs, i) -> rs.getBytes("pdf"), id).stream().findFirst().orElse(null);
    }

    /** Numéro suivant de la série pour l'exercice : incrément atomique, dans la transaction de la validation. */
    public int next(String series, int year) {
        return jdbc.queryForObject("""
                INSERT INTO counter (series, year, value) VALUES (?, ?, 1)
                ON CONFLICT (series, year) DO UPDATE SET value = counter.value + 1 RETURNING value""", Integer.class, series, year);
    }

    /** Fin de la dernière période d'abonnement facturée (brouillons compris) pour une crèche. */
    public Optional<LocalDate> lastSubscriptionPeriodEnd(UUID crecheId) {
        return jdbc.queryForList("""
                SELECT max(period_to) FROM invoice WHERE creche_id = ? AND type = 'FACTURE' AND kind = 'ABONNEMENT'""",
                Date.class, crecheId).stream().filter(java.util.Objects::nonNull).map(Date::toLocalDate).findFirst();
    }

    /** Facture d'abonnement (validée ou brouillon) dont la période contient ce jour. */
    public Optional<Invoice> subscriptionCovering(UUID crecheId, LocalDate day) {
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM invoice WHERE creche_id = ? AND type = 'FACTURE' AND kind = 'ABONNEMENT' AND period_from <= ? AND period_to >= ?
                ORDER BY period_from DESC LIMIT 1""", this::invoice, crecheId, date(day), date(day)).stream().findFirst();
    }

    /**
     * Fin de la période d'abonnement payée la plus lointaine (abonnement attendu de l'instance, D6) : facture validée,
     * soldée par des paiements, et pas annulée par avoir.
     */
    public Optional<LocalDate> paidThrough(UUID crecheId) {
        return jdbc.queryForList("""
                SELECT max(i.period_to) FROM invoice i
                CROSS JOIN LATERAL (SELECT coalesce((SELECT sum(a.amount) FROM payment_allocation a WHERE a.invoice_id = i.id), 0) AS paid,
                                           coalesce((SELECT sum(c.total_ttc) FROM invoice c WHERE c.corrects_id = i.id AND c.status = 'VALIDEE'), 0)
                                               AS credited) s
                WHERE i.creche_id = ? AND i.type = 'FACTURE' AND i.kind = 'ABONNEMENT' AND i.status = 'VALIDEE'
                  AND s.credited < i.total_ttc AND i.total_ttc - s.paid - s.credited <= 0""",
                Date.class, crecheId).stream().filter(java.util.Objects::nonNull).map(Date::toLocalDate).findFirst();
    }

    // --- Outils

    private Invoice invoice(ResultSet rs, int i) throws SQLException {
        return new Invoice(rs.getObject("id", UUID.class), rs.getString("type"), rs.getString("number"), rs.getString("status"),
                rs.getString("kind"), rs.getObject("client_id", UUID.class), rs.getObject("creche_id", UUID.class),
                rs.getObject("contract_id", UUID.class), rs.getObject("corrects_id", UUID.class), rs.getString("subject"),
                CatalogueRepository.date(rs, "period_from"), CatalogueRepository.date(rs, "period_to"),
                CatalogueRepository.date(rs, "issued_on"), CatalogueRepository.date(rs, "due_on"), rs.getLong("total_ht"),
                rs.getLong("total_tgc"), rs.getLong("total_ttc"), node(rs.getString("tgc_detail")), node(rs.getString("client_snapshot")),
                node(rs.getString("issuer_snapshot")), rs.getString("note"), rs.getString("pdf_sha256"), instant(rs, "sent_at"),
                rs.getBoolean("reminders_paused"), instant(rs, "validated_at"), rs.getString("validated_by"),
                rs.getTimestamp("created_at").toInstant());
    }

    private JsonNode node(String text) {
        try {
            return text == null ? null : json.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Date date(LocalDate day) {
        return day == null ? null : Date.valueOf(day);
    }
}
