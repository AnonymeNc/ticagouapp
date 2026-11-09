package nc.ticagou.console.billing;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Indicateurs de facturation pour le tableau de bord et le parc : revenu mensuel récurrent (contrats en cours, prix de
 * leur barème), encours et retards, délai moyen de paiement, facturé et encaissé par mois, solde dû par crèche.
 */
@Service
public class BillingStats {

    public record Balance(long due, long daysLate, String oldestLateNumber) {
    }

    public record Month(String month, long invoiced, long collected) {
    }

    public record Board(long mrr, long arr, long outstanding, long overdue, int overdueCount, Integer dso, int drafts, int renewalDrafts,
                        int pendingMessages, List<Month> months, List<InvoiceService.Summary> late, List<InvoiceService.Summary> draftList) {
    }

    private final InvoiceService invoices;
    private final ContractRepository contracts;
    private final CatalogueRepository catalogue;
    private final OutboxRepository outbox;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public BillingStats(InvoiceService invoices, ContractRepository contracts, CatalogueRepository catalogue, OutboxRepository outbox,
                        JdbcTemplate jdbc, Clock clock) {
        this.invoices = invoices;
        this.contracts = contracts;
        this.catalogue = catalogue;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Solde dû et plus grand retard, par crèche. */
    @Transactional(readOnly = true)
    public Map<UUID, Balance> balances() {
        var result = new HashMap<String, Balance>();
        var ids = new HashMap<String, UUID>();
        jdbc.query("SELECT id, slug FROM creche", rs -> {
            ids.put(rs.getString("slug"), rs.getObject("id", UUID.class));
        });
        for (var s : invoices.list()) {
            if (s.crecheSlug() == null || s.remaining() == 0 || "AVOIR".equals(s.state()) || "BROUILLON".equals(s.state())) {
                continue;
            }
            var before = result.getOrDefault(s.crecheSlug(), new Balance(0, 0, null));
            boolean later = "RETARD".equals(s.state()) && s.daysLate() > before.daysLate();
            result.put(s.crecheSlug(), new Balance(before.due() + s.remaining(), later ? s.daysLate() : before.daysLate(),
                    later ? s.number() : before.oldestLateNumber()));
        }
        var byId = new HashMap<UUID, Balance>();
        result.forEach((slug, b) -> byId.put(ids.get(slug), b));
        return byId;
    }

    @Transactional(readOnly = true)
    public Board board() {
        var today = LocalDate.now(clock);
        long mrr = 0;
        for (var contract : contracts.running(today)) {
            if (!contract.covers(today)) {
                continue;
            }
            try {
                mrr += Pricing.quote(catalogue.catalogue(contract.priceListId()), contract.terms(), 1).total();
            } catch (IllegalArgumentException e) {
                // Contrat que le barème ne sait plus chiffrer : il apparaît sur sa fiche, pas dans le total.
            }
        }
        var all = invoices.list();
        long outstanding = 0;
        long overdue = 0;
        int overdueCount = 0;
        var late = new ArrayList<InvoiceService.Summary>();
        var drafts = new ArrayList<InvoiceService.Summary>();
        for (var s : all) {
            if ("BROUILLON".equals(s.state())) {
                drafts.add(s);
            } else if (!"AVOIR".equals(s.state()) && s.remaining() > 0) {
                outstanding += s.remaining();
                if ("RETARD".equals(s.state())) {
                    overdue += s.remaining();
                    overdueCount++;
                    late.add(s);
                }
            }
        }
        late.sort((a, b) -> Long.compare(b.daysLate(), a.daysLate()));
        return new Board(mrr, mrr * 12, outstanding, overdue, overdueCount, dso(today), drafts.size(),
                (int) drafts.stream().filter(d -> "ABONNEMENT".equals(d.kind())).count(), outbox.pending(), months(today), late, drafts);
    }

    /**
     * Délai moyen de paiement sur 90 jours : pour chaque facture soldée par des paiements reçus dans ces 90 jours, jours
     * entre l'émission et le dernier paiement, pondérés par le montant.
     */
    Integer dso(LocalDate today) {
        var rows = jdbc.queryForList("""
                SELECT i.total_ttc, i.issued_on, max(p.received_on) AS paid_on, sum(a.amount) AS paid
                FROM invoice i JOIN payment_allocation a ON a.invoice_id = i.id JOIN payment p ON p.id = a.payment_id
                WHERE i.type = 'FACTURE' AND i.status = 'VALIDEE'
                GROUP BY i.id, i.total_ttc, i.issued_on
                HAVING sum(a.amount) >= i.total_ttc - coalesce((SELECT sum(c.total_ttc) FROM invoice c
                        WHERE c.corrects_id = i.id AND c.status = 'VALIDEE'), 0)
                   AND max(p.received_on) >= ?""", Date.valueOf(today.minusDays(90)));
        long weight = 0;
        long total = 0;
        for (var row : rows) {
            long amount = ((Number) row.get("paid")).longValue();
            long days = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(((Date) row.get("issued_on")).toLocalDate(),
                    ((Date) row.get("paid_on")).toLocalDate()));
            weight += amount;
            total += amount * days;
        }
        return weight == 0 ? null : (int) Xpf.divide(total, weight);
    }

    /** Six derniers mois : facturé TTC (factures moins avoirs, par mois d'émission) et encaissé (paiements reçus). */
    List<Month> months(LocalDate today) {
        var result = new ArrayList<Month>();
        var first = YearMonth.from(today).minusMonths(5);
        var invoiced = new HashMap<YearMonth, Long>();
        jdbc.query("""
                SELECT date_trunc('month', issued_on)::date AS m,
                       sum(CASE WHEN type = 'AVOIR' THEN -total_ttc ELSE total_ttc END) AS total
                FROM invoice WHERE status = 'VALIDEE' AND issued_on >= ? GROUP BY 1""", rs -> {
            invoiced.put(YearMonth.from(rs.getDate("m").toLocalDate()), rs.getLong("total"));
        }, Date.valueOf(first.atDay(1)));
        var collected = new HashMap<YearMonth, Long>();
        jdbc.query("SELECT date_trunc('month', received_on)::date AS m, sum(amount) AS total FROM payment WHERE received_on >= ? GROUP BY 1",
                rs -> {
                    collected.put(YearMonth.from(rs.getDate("m").toLocalDate()), rs.getLong("total"));
                }, Date.valueOf(first.atDay(1)));
        for (int i = 0; i < 6; i++) {
            var m = first.plusMonths(i);
            result.add(new Month(m.toString(), invoiced.getOrDefault(m, 0L), collected.getOrDefault(m, 0L)));
        }
        return result;
    }
}
