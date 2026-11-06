package nc.ticagou.reporting;

import java.sql.Date;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.attendance.PlannedDays;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.common.ApiException;
import nc.ticagou.common.Formats;
import nc.ticagou.reporting.ActivityCalculator.Indicators;
import nc.ticagou.reporting.ActivityCalculator.MonthIndicators;
import nc.ticagou.reporting.ActivityCalculator.Presence;
import nc.ticagou.reporting.ActivityCalculator.Section;
import nc.ticagou.reporting.ActivityCalculator.SectionIndicators;

/**
 * Bilan d'activité d'un mois ou d'une année civile (menu Statistiques) : fréquentation, occupation, absences,
 * facturation et aides par financeur. Sert aussi de pièce aux dossiers des financeurs (PDF, Excel).
 */
@Service
public class ReportingService {

    public record AbsenceRow(String reason, boolean deductible, int days) {
    }

    public record FunderRow(String name, String category, int children, long amount) {
    }

    /**
     * @param invoiced  frais de garde facturés (hors TGC), factures des mois de la période
     * @param aids      aides déduites des factures
     * @param families  reste à charge des familles
     * @param credited  avoirs émis sur ces factures
     * @param remaining reste dû aujourd'hui sur ces factures
     */
    public record Money(int invoices, long invoiced, long tgc, long aids, long families, long credited, long remaining) {
    }

    public record Report(String label, LocalDate from, LocalDate to, String structureName, int capacity, int openDays,
                         int enrolled, Indicators total, List<SectionIndicators> sections, List<MonthIndicators> months,
                         List<AbsenceRow> absences, List<FunderRow> funders, Money money) {
    }

    private final JdbcTemplate jdbc;
    private final PlannedDays planned;
    private final Clock clock;

    public ReportingService(JdbcTemplate jdbc, PlannedDays planned, Clock clock) {
        this.jdbc = jdbc;
        this.planned = planned;
        this.clock = clock;
    }

    /** {@code month} nul : l'année entière. Une période en cours s'arrête à aujourd'hui. */
    @Transactional(readOnly = true)
    public Report report(int year, Integer month) {
        var structureId = CurrentUser.get().structureId();
        var first = month == null ? LocalDate.of(year, 1, 1) : YearMonth.of(year, month).atDay(1);
        var last = month == null ? LocalDate.of(year, 12, 31) : YearMonth.of(year, month).atEndOfMonth();
        var today = LocalDate.now(clock);
        if (first.isAfter(today)) {
            throw ApiException.rule("reporting_future", "Cette période n’a pas encore commencé.");
        }
        var to = last.isAfter(today) ? today : last;
        var label = month == null ? "Année " + year : Formats.monthYear(YearMonth.of(year, month));

        var sections = jdbc.query("SELECT id, name, capacity FROM section WHERE structure_id = ? ORDER BY display_order, min_age_months",
                (rs, i) -> new Section(rs.getObject("id", UUID.class), rs.getString("name"), rs.getInt("capacity")), structureId);
        var opening = new HashMap<Integer, Integer>();
        jdbc.query("SELECT day_of_week, opens_at, closes_at FROM structure_opening_hours WHERE structure_id = ?", rs -> {
            opening.put(rs.getInt("day_of_week"), (int) Duration.between(rs.getTime("opens_at").toLocalTime(),
                    rs.getTime("closes_at").toLocalTime()).toMinutes());
        }, structureId);
        var capacities = new TreeMap<LocalDate, Integer>();
        jdbc.query("SELECT valid_from, capacity FROM structure_capacity WHERE structure_id = ?",
                rs -> { capacities.put(rs.getDate("valid_from").toLocalDate(), rs.getInt("capacity")); }, structureId);
        int current = jdbc.queryForObject("SELECT capacity FROM structure WHERE id = ?", Integer.class, structureId);
        var structureName = jdbc.queryForObject("SELECT name FROM structure WHERE id = ?", String.class, structureId);

        var realized = jdbc.query("""
                SELECT day, child_id, section_id, extract(epoch FROM departed_at - arrived_at) / 60 AS minutes
                FROM child_attendance WHERE structure_id = ? AND day BETWEEN ? AND ? AND departed_at IS NOT NULL""",
                (rs, i) -> new Presence(rs.getDate("day").toLocalDate(), rs.getObject("child_id", UUID.class),
                        rs.getObject("section_id", UUID.class), Math.max(rs.getInt("minutes"), 0)),
                structureId, Date.valueOf(first), Date.valueOf(to));
        var childSections = new HashMap<UUID, UUID>();
        jdbc.query("SELECT id, section_id FROM child WHERE structure_id = ?",
                rs -> { childSections.put(rs.getObject("id", UUID.class), rs.getObject("section_id", UUID.class)); }, structureId);
        var plannedDays = new ArrayList<Presence>();
        var enrolledChildren = new java.util.HashSet<UUID>();
        for (var day = first; !day.isAfter(to); day = day.plusDays(1)) {
            for (var entry : planned.forDay(structureId, day).entrySet()) {
                enrolledChildren.add(entry.getKey());
                var plan = entry.getValue();
                if (plan.variable() || plan.arrivesAt() == null || plan.leavesAt() == null) {
                    continue;
                }
                plannedDays.add(new Presence(day, entry.getKey(), childSections.get(entry.getKey()),
                        (int) Duration.between(plan.arrivesAt(), plan.leavesAt()).toMinutes()));
            }
        }
        var input = new ActivityCalculator.Input(first, to, sections, ActivityCalculator.weekly(opening), day -> {
            var entry = capacities.floorEntry(day);
            if (entry != null) {
                return entry.getValue();
            }
            return capacities.isEmpty() ? current : capacities.firstEntry().getValue();
        }, realized, plannedDays);
        var activity = ActivityCalculator.compute(input);
        var capacity = capacities.floorEntry(to) == null ? current : capacities.floorEntry(to).getValue();

        return new Report(label, first, to, structureName, capacity, activity.openDays(), enrolledChildren.size(),
                activity.total(), activity.sections(), month == null ? activity.months() : List.of(),
                absences(structureId, first, to, opening), funders(structureId, first, last), money(structureId, first, last));
    }

    /** Jours d'absence enregistrés, comptés sur les jours d'ouverture de la période. */
    private List<AbsenceRow> absences(UUID structureId, LocalDate from, LocalDate to, Map<Integer, Integer> opening) {
        var rows = new TreeMap<String, AbsenceRow>();
        jdbc.query("""
                SELECT r.label, r.deductible, a.from_day, a.to_day FROM child_absence a JOIN absence_reason r ON r.id = a.reason_id
                WHERE a.structure_id = ? AND a.to_day >= ? AND a.from_day <= ?""", rs -> {
            var start = rs.getDate("from_day").toLocalDate();
            var end = rs.getDate("to_day").toLocalDate();
            int days = 0;
            for (var d = start.isBefore(from) ? from : start; !d.isAfter(end) && !d.isAfter(to); d = d.plusDays(1)) {
                if (opening.getOrDefault(d.getDayOfWeek().getValue(), 0) > 0) {
                    days++;
                }
            }
            var label = rs.getString("label");
            var previous = rows.get(label);
            rows.put(label, new AbsenceRow(label, rs.getBoolean("deductible"), (previous == null ? 0 : previous.days()) + days));
        }, structureId, Date.valueOf(from), Date.valueOf(to));
        return List.copyOf(rows.values());
    }

    /** Aides déduites des factures des mois de la période, par financeur. */
    private List<FunderRow> funders(UUID structureId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT f.name, f.category, count(DISTINCT l.child_id) AS children, -sum(l.amount) AS amount
                FROM invoice_line l JOIN invoice i ON i.id = l.invoice_id JOIN funder f ON f.id = l.funder_id
                WHERE i.structure_id = ? AND i.month BETWEEN ? AND ? AND l.kind = 'AIDE'
                GROUP BY f.name, f.category ORDER BY amount DESC""",
                (rs, i) -> new FunderRow(rs.getString("name"), rs.getString("category"), rs.getInt("children"), rs.getLong("amount")),
                structureId, Date.valueOf(from.withDayOfMonth(1)), Date.valueOf(to));
    }

    private Money money(UUID structureId, LocalDate from, LocalDate to) {
        return jdbc.queryForObject("""
                WITH inv AS (
                    SELECT i.id, i.care_total, i.tgc_total, i.aid_total, i.family_total,
                           coalesce((SELECT sum(c.family_total) FROM credit_note c WHERE c.invoice_id = i.id), 0) AS credited,
                           coalesce((SELECT sum(a.amount) FROM payment_allocation a JOIN payment p ON p.id = a.payment_id
                                     WHERE a.invoice_id = i.id AND p.cancelled_at IS NULL), 0) AS paid
                    FROM invoice i WHERE i.structure_id = ? AND i.month BETWEEN ? AND ?)
                SELECT count(*) AS invoices, coalesce(sum(care_total), 0) AS invoiced, coalesce(sum(tgc_total), 0) AS tgc,
                       coalesce(sum(aid_total), 0) AS aids, coalesce(sum(family_total), 0) AS families,
                       coalesce(sum(credited), 0) AS credited,
                       coalesce(sum(greatest(family_total - credited - paid, 0)), 0) AS remaining
                FROM inv""", (rs, i) -> new Money(rs.getInt("invoices"), rs.getLong("invoiced"), rs.getLong("tgc"),
                rs.getLong("aids"), rs.getLong("families"), rs.getLong("credited"), rs.getLong("remaining")),
                structureId, Date.valueOf(from.withDayOfMonth(1)), Date.valueOf(to));
    }
}
