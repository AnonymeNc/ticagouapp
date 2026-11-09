package nc.ticagou.console.billing;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import nc.ticagou.console.billing.ReminderPlanner.Rule;

/** Règles de relance datées, jours fériés, règles par client, relances préparées. */
@Repository
public class ReminderRepository {

    public record RuleSet(UUID id, String label, LocalDate validFrom, LocalDate validTo) {
    }

    public record Holiday(LocalDate day, String label) {
    }

    public record ClientRule(UUID clientId, String clientName, String crecheSlug, String crecheName, int paymentDays, int shiftDays,
                             boolean paused, String note) {
    }

    public record Sent(UUID invoiceId, int level, String label, LocalDate preparedOn, int daysLate, UUID messageId, String messageStatus,
                       Instant sentAt) {
    }

    private final JdbcTemplate jdbc;

    public ReminderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- Règles

    public List<RuleSet> sets() {
        return jdbc.query("SELECT * FROM reminder_rule_set ORDER BY valid_from DESC", (rs, i) -> new RuleSet(rs.getObject("id", UUID.class),
                rs.getString("label"), rs.getDate("valid_from").toLocalDate(), CatalogueRepository.date(rs, "valid_to")));
    }

    public List<Rule> rules(UUID setId) {
        return jdbc.query("SELECT * FROM reminder_rule WHERE set_id = ? ORDER BY level", (rs, i) -> new Rule(rs.getInt("level"),
                rs.getString("label"), rs.getInt("days"), rs.getBoolean("active"), rs.getString("subject"), rs.getString("body"),
                rs.getBoolean("copy_to_issuer"), rs.getBoolean("attach_pdf"), rs.getBoolean("propose_suspension")), setId);
    }

    public Optional<RuleSet> setAt(LocalDate day) {
        return sets().stream().filter(s -> !s.validFrom().isAfter(day) && (s.validTo() == null || !s.validTo().isBefore(day))).findFirst();
    }

    public List<Rule> rulesAt(LocalDate day) {
        return setAt(day).map(s -> rules(s.id())).orElse(List.of());
    }

    public boolean setStartsOnOrAfter(LocalDate day) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM reminder_rule_set WHERE valid_from >= ?)", Boolean.class,
                Date.valueOf(day)));
    }

    public void insertSet(UUID id, String label, LocalDate validFrom, List<Rule> rules, Instant now) {
        jdbc.update("UPDATE reminder_rule_set SET valid_to = ? WHERE valid_to IS NULL AND valid_from < ?", Date.valueOf(validFrom.minusDays(1)),
                Date.valueOf(validFrom));
        jdbc.update("INSERT INTO reminder_rule_set (id, label, valid_from, created_at) VALUES (?, ?, ?, ?)", id, label, Date.valueOf(validFrom),
                Timestamp.from(now));
        for (var r : rules) {
            jdbc.update("""
                    INSERT INTO reminder_rule (set_id, level, label, days, active, subject, body, copy_to_issuer, attach_pdf, propose_suspension)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, r.level(), r.label(), r.days(), r.active(), r.subject(), r.body(),
                    r.copyToIssuer(), r.attachPdf(), r.proposeSuspension());
        }
    }

    // --- Fériés

    public List<Holiday> holidays() {
        return jdbc.query("SELECT * FROM public_holiday ORDER BY day", (rs, i) -> new Holiday(rs.getDate("day").toLocalDate(), rs.getString("label")));
    }

    public Set<LocalDate> holidayDays() {
        return new HashSet<>(holidays().stream().map(Holiday::day).toList());
    }

    public void addHoliday(LocalDate day, String label) {
        jdbc.update("INSERT INTO public_holiday (day, label) VALUES (?, ?) ON CONFLICT (day) DO UPDATE SET label = excluded.label",
                Date.valueOf(day), label);
    }

    public int removeHoliday(LocalDate day) {
        return jdbc.update("DELETE FROM public_holiday WHERE day = ?", Date.valueOf(day));
    }

    // --- Règles par client

    public List<ClientRule> clientRules() {
        return jdbc.query("""
                SELECT c.id, c.name, c.payment_days, c.reminder_shift_days, c.reminders_paused, c.reminder_note,
                       (SELECT slug FROM creche k WHERE k.client_id = c.id ORDER BY k.name LIMIT 1) AS slug,
                       (SELECT name FROM creche k WHERE k.client_id = c.id ORDER BY k.name LIMIT 1) AS creche
                FROM client c ORDER BY lower(c.name)""", (rs, i) -> new ClientRule(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("slug"), rs.getString("creche"), rs.getInt("payment_days"), rs.getInt("reminder_shift_days"),
                rs.getBoolean("reminders_paused"), rs.getString("reminder_note")));
    }

    public int updateClientRule(UUID clientId, int shiftDays, boolean paused, String note) {
        return jdbc.update("UPDATE client SET reminder_shift_days = ?, reminders_paused = ?, reminder_note = ? WHERE id = ?", shiftDays, paused,
                note, clientId);
    }

    // --- Relances

    /** Niveaux déjà préparés, par facture. */
    public Map<UUID, Set<Integer>> doneLevels() {
        var result = new HashMap<UUID, Set<Integer>>();
        jdbc.query("SELECT invoice_id, level FROM reminder", rs -> {
            result.computeIfAbsent(rs.getObject("invoice_id", UUID.class), k -> new HashSet<>()).add(rs.getInt("level"));
        });
        return result;
    }

    public void insert(UUID invoiceId, int level, UUID messageId, LocalDate preparedOn, int daysLate, Instant now) {
        jdbc.update("""
                INSERT INTO reminder (id, invoice_id, level, outbox_message_id, prepared_on, days_late, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(), invoiceId, level, messageId, Date.valueOf(preparedOn), daysLate, Timestamp.from(now));
    }

    public List<UUID> invoicesOf(UUID messageId) {
        return jdbc.queryForList("SELECT invoice_id FROM reminder WHERE outbox_message_id = ? ORDER BY created_at", UUID.class, messageId);
    }

    /** Relance retirée (facture soldée entre-temps) : les factures encore dues pourront être relancées à nouveau. */
    public int forget(UUID messageId, List<UUID> invoiceIds) {
        int removed = 0;
        for (var id : invoiceIds) {
            removed += jdbc.update("DELETE FROM reminder WHERE outbox_message_id = ? AND invoice_id = ?", messageId, id);
        }
        return removed;
    }

    public List<Sent> forInvoice(UUID invoiceId) {
        return jdbc.query("""
                SELECT r.invoice_id, r.level, r.prepared_on, r.days_late, r.outbox_message_id, m.status, m.sent_at,
                       coalesce((SELECT rr.label FROM reminder_rule rr JOIN reminder_rule_set s ON s.id = rr.set_id
                                 WHERE rr.level = r.level AND s.valid_from <= r.prepared_on ORDER BY s.valid_from DESC LIMIT 1), 'Relance') AS label
                FROM reminder r LEFT JOIN outbox_message m ON m.id = r.outbox_message_id
                WHERE r.invoice_id = ? ORDER BY r.level""", (rs, i) -> new Sent(rs.getObject("invoice_id", UUID.class), rs.getInt("level"),
                rs.getString("label"), rs.getDate("prepared_on").toLocalDate(), rs.getInt("days_late"), rs.getObject("outbox_message_id", UUID.class),
                rs.getString("status"), rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toInstant()), invoiceId);
    }

    /** Factures dont la dernière relance (qui propose une suspension) est partie : la suspension est à décider. */
    public List<UUID> suspensionCandidates() {
        return jdbc.queryForList("""
                SELECT DISTINCT r.invoice_id FROM reminder r JOIN outbox_message m ON m.id = r.outbox_message_id
                JOIN reminder_rule_set s ON s.valid_from <= r.prepared_on AND (s.valid_to IS NULL OR s.valid_to >= r.prepared_on)
                JOIN reminder_rule rr ON rr.set_id = s.id AND rr.level = r.level
                WHERE m.status = 'ENVOYE' AND rr.propose_suspension""", UUID.class);
    }
}
