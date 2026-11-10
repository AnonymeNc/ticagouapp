package nc.ticagou.console.reporting;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import nc.ticagou.console.audit.AuditService;
import nc.ticagou.console.billing.CatalogueRepository;
import nc.ticagou.console.billing.ContractRepository;
import nc.ticagou.console.billing.InvoiceService;
import nc.ticagou.console.billing.Pricing;
import nc.ticagou.console.billing.Xpf;
import nc.ticagou.console.common.ApiException;
import nc.ticagou.console.parc.ParcService;

/**
 * Reporting de l'éditeur (plan console, phase 6) : chiffre d'affaires, encaissements et TGC d'une période, balance âgée
 * à ce jour, revenu mensuel par formule, vente additionnelle, risque de départ, rentabilité par crèche. Filtres : période,
 * client, formule (celle du contrat de la facture). Montants en francs CFP, entiers.
 */
@Service
public class ReportingService {

    static final ZoneId NOUMEA = ZoneId.of("Pacific/Noumea");
    /** Seuils du risque de départ. */
    static final int NO_LOGIN_DAYS = 14;
    static final int USAGE_DROP_PERCENT = 30;
    static final int LATE_PAYMENTS = 2;

    public record Filter(LocalDate from, LocalDate to, UUID clientId, String plan) {
    }

    public record Kpis(long revenueHt, long subscriptionsHt, long servicesHt, long collected, long tgc, List<Integer> rates, long mrr,
                       int departures, Integer churnTenths) {
    }

    public record MonthRow(String month, long ht, long tgc, long ttc, long collected) {
    }

    public record RateRow(int rate, long base, long tgc) {
    }

    public record AgingRow(UUID clientId, String client, long notDue, long d30, long d60, long d90, long over, long total) {
    }

    public record PlanRow(String key, String label, long monthly) {
    }

    public record Upsell(String slug, String name, String text, long monthly) {
    }

    public record Risk(String slug, String name, String kind, String text) {
    }

    public record Profit(String slug, String name, long revenue, long supportMinutes, long cost, long margin) {
    }

    public record Settings(long supportHourlyRate, long hostingPerCreche) {
    }

    public record ClientOption(UUID id, String name) {
    }

    public record Report(Filter filter, Kpis kpis, List<MonthRow> months, List<RateRow> tgc, List<AgingRow> aging, AgingRow agingTotal,
                         List<PlanRow> mrrByPlan, List<Upsell> upsell, List<Risk> risks, List<Profit> profitability, int profitMonths,
                         Settings settings, List<ClientOption> clients) {
    }

    /** Une pièce du journal des ventes : montants signés (un avoir est négatif). */
    public record Sale(LocalDate issuedOn, String number, String type, String kind, String client, String ridet, String creche, String plan,
                       String subject, LocalDate dueOn, long ht, long tgc, long ttc, Map<Integer, long[]> rates) {
    }

    /** Un encaissement et les pièces qu'il règle. */
    public record Receipt(LocalDate receivedOn, String client, long amount, String method, String reference, String invoices) {
    }

    /** Une seule formule (ADR 0021) ; les anciennes restent nommées pour les contrats signés avant. */
    private static final Map<String, String> PLAN_LABELS = Map.of("ESSENTIEL", "Essentiel (ancienne formule)",
            "GESTION", "Gestion (ancienne formule)", "COMPLET", "Ti’Cagou");
    private static final Set<String> PLANS = PLAN_LABELS.keySet();

    private final JdbcTemplate jdbc;
    private final ContractRepository contracts;
    private final CatalogueRepository catalogue;
    private final InvoiceService invoices;
    private final ParcService parc;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    public ReportingService(JdbcTemplate jdbc, ContractRepository contracts, CatalogueRepository catalogue, InvoiceService invoices,
                            ParcService parc, AuditService audit, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.contracts = contracts;
        this.catalogue = catalogue;
        this.invoices = invoices;
        this.parc = parc;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    public Filter filter(LocalDate from, LocalDate to, UUID clientId, String plan) {
        var today = LocalDate.now(clock);
        var start = from == null ? LocalDate.of(today.getYear(), 1, 1) : from;
        var end = to == null ? today : to;
        if (end.isBefore(start)) {
            throw ApiException.rule("periode", "La fin de la période précède son début.");
        }
        if (start.plusYears(5).isBefore(end)) {
            throw ApiException.rule("periode", "Période de cinq ans au plus.");
        }
        if (plan != null && !plan.isBlank() && !PLANS.contains(plan)) {
            throw ApiException.rule("formule", "Formule : ESSENTIEL, GESTION ou COMPLET.");
        }
        return new Filter(start, end, clientId, plan == null || plan.isBlank() ? null : plan);
    }

    // --- Rapport

    @Transactional(readOnly = true)
    public Report report(Filter f) {
        var today = LocalDate.now(clock);
        var sales = sales(f);
        long revenue = 0;
        long subscriptions = 0;
        long tgc = 0;
        var rates = new TreeMap<Integer, long[]>();
        var byMonth = new TreeMap<YearMonth, long[]>();
        for (var s : sales) {
            revenue += s.ht();
            tgc += s.tgc();
            if (!"PRESTATION".equals(s.kind())) {
                subscriptions += s.ht();
            }
            s.rates().forEach((rate, amounts) -> {
                var total = rates.computeIfAbsent(rate, r -> new long[2]);
                total[0] += amounts[0];
                total[1] += amounts[1];
            });
            var month = byMonth.computeIfAbsent(YearMonth.from(s.issuedOn()), m -> new long[4]);
            month[0] += s.ht();
            month[1] += s.tgc();
            month[2] += s.ttc();
        }
        long collected = 0;
        for (var r : receipts(f)) {
            collected += r.amount();
            byMonth.computeIfAbsent(YearMonth.from(r.receivedOn()), m -> new long[4])[3] += r.amount();
        }
        var months = new ArrayList<MonthRow>();
        for (var m = YearMonth.from(f.from()); !m.isAfter(YearMonth.from(f.to())); m = m.plusMonths(1)) {
            var v = byMonth.getOrDefault(m, new long[4]);
            months.add(new MonthRow(m.toString(), v[0], v[1], v[2], v[3]));
        }
        var tgcRows = rates.entrySet().stream().map(e -> new RateRow(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();

        var scope = scope(f);
        var plans = mrrByPlan(today, scope);
        long mrr = plans.stream().mapToLong(PlanRow::monthly).sum();
        var churn = churn(today, scope);
        var aging = aging(today, f);
        var total = new AgingRow(null, "Total", aging.stream().mapToLong(AgingRow::notDue).sum(), aging.stream().mapToLong(AgingRow::d30).sum(),
                aging.stream().mapToLong(AgingRow::d60).sum(), aging.stream().mapToLong(AgingRow::d90).sum(),
                aging.stream().mapToLong(AgingRow::over).sum(), aging.stream().mapToLong(AgingRow::total).sum());
        var settings = settings();
        int profitMonths = Reporting.months(f.from(), f.to());
        var kpis = new Kpis(revenue, subscriptions, revenue - subscriptions, collected, tgc, List.copyOf(rates.keySet()), mrr, churn[0],
                Reporting.churnTenths(churn[0], churn[1]));
        return new Report(f, kpis, months, tgcRows, aging, total, plans, upsell(today, scope), risks(today, scope),
                profitability(today, f, scope, settings, profitMonths), profitMonths, settings, clients());
    }

    // --- Ventes et encaissements de la période (journaux)

    @Transactional(readOnly = true)
    public List<Sale> sales(Filter f) {
        var args = new ArrayList<Object>(List.of(Date.valueOf(f.from()), Date.valueOf(f.to())));
        var sql = new StringBuilder("""
                SELECT i.type, i.number, i.kind, i.issued_on, i.due_on, i.subject, i.total_ht, i.total_tgc, i.total_ttc, i.tgc_detail::text AS detail,
                       c.name AS client, c.ridet, cr.name AS creche, sc.plan
                FROM invoice i JOIN client c ON c.id = i.client_id
                LEFT JOIN creche cr ON cr.id = i.creche_id
                LEFT JOIN invoice o ON o.id = i.corrects_id
                LEFT JOIN subscription_contract sc ON sc.id = coalesce(i.contract_id, o.contract_id)
                WHERE i.status = 'VALIDEE' AND i.issued_on BETWEEN ? AND ?""");
        restrict(sql, args, f, "i.client_id", "sc.plan");
        sql.append(" ORDER BY i.issued_on, i.number");
        return jdbc.query(sql.toString(), (rs, n) -> {
            int sign = "AVOIR".equals(rs.getString("type")) ? -1 : 1;
            var rates = new TreeMap<Integer, long[]>();
            var detail = node(rs.getString("detail"));
            if (detail != null && detail.isArray()) {
                for (var r : detail) {
                    rates.computeIfAbsent(r.path("rate").asInt(), k -> new long[2]);
                    rates.get(r.path("rate").asInt())[0] += sign * r.path("base").asLong();
                    rates.get(r.path("rate").asInt())[1] += sign * r.path("tgc").asLong();
                }
            }
            return new Sale(rs.getDate("issued_on").toLocalDate(), rs.getString("number"), rs.getString("type"), rs.getString("kind"),
                    rs.getString("client"), rs.getString("ridet"), rs.getString("creche"), rs.getString("plan"), rs.getString("subject"),
                    rs.getDate("due_on") == null ? null : rs.getDate("due_on").toLocalDate(), sign * rs.getLong("total_ht"),
                    sign * rs.getLong("total_tgc"), sign * rs.getLong("total_ttc"), rates);
        }, args.toArray());
    }

    /** Encaissements reçus sur la période ; filtrés par formule, seule la part affectée aux pièces de cette formule compte. */
    @Transactional(readOnly = true)
    public List<Receipt> receipts(Filter f) {
        var args = new ArrayList<Object>(List.of(Date.valueOf(f.from()), Date.valueOf(f.to())));
        String sql;
        if (f.plan() == null) {
            sql = """
                    SELECT p.received_on, c.name AS client, p.amount, p.method, p.reference,
                           (SELECT string_agg(i.number, ', ' ORDER BY i.number) FROM payment_allocation a JOIN invoice i ON i.id = a.invoice_id
                            WHERE a.payment_id = p.id) AS invoices
                    FROM payment p JOIN client c ON c.id = p.client_id
                    WHERE p.received_on BETWEEN ? AND ?""" + (f.clientId() == null ? "" : " AND p.client_id = ?")
                    + " ORDER BY p.received_on, p.created_at";
        } else {
            sql = """
                    SELECT p.received_on, c.name AS client, sum(a.amount) AS amount, p.method, p.reference,
                           string_agg(i.number, ', ' ORDER BY i.number) AS invoices
                    FROM payment p JOIN client c ON c.id = p.client_id
                    JOIN payment_allocation a ON a.payment_id = p.id JOIN invoice i ON i.id = a.invoice_id
                    JOIN subscription_contract sc ON sc.id = i.contract_id
                    WHERE p.received_on BETWEEN ? AND ? AND sc.plan = ?""" + (f.clientId() == null ? "" : " AND p.client_id = ?")
                    + " GROUP BY p.id, p.received_on, c.name, p.method, p.reference, p.created_at ORDER BY p.received_on, p.created_at";
            args.add(f.plan());
        }
        if (f.clientId() != null) {
            args.add(f.clientId());
        }
        return jdbc.query(sql, (rs, n) -> new Receipt(rs.getDate("received_on").toLocalDate(), rs.getString("client"), rs.getLong("amount"),
                rs.getString("method"), rs.getString("reference"), rs.getString("invoices")), args.toArray());
    }

    private static void restrict(StringBuilder sql, List<Object> args, Filter f, String clientColumn, String planColumn) {
        if (f.clientId() != null) {
            sql.append(" AND ").append(clientColumn).append(" = ?");
            args.add(f.clientId());
        }
        if (f.plan() != null) {
            sql.append(" AND ").append(planColumn).append(" = ?");
            args.add(f.plan());
        }
    }

    // --- Balance âgée (à ce jour)

    List<AgingRow> aging(LocalDate today, Filter f) {
        var plans = new HashMap<UUID, String>();
        if (f.plan() != null) {
            jdbc.query("""
                    SELECT i.id, sc.plan FROM invoice i JOIN subscription_contract sc ON sc.id = i.contract_id WHERE i.status = 'VALIDEE'""",
                    rs -> {
                        plans.put(rs.getObject("id", UUID.class), rs.getString("plan"));
                    });
        }
        var rows = new LinkedHashMap<UUID, long[]>();
        var names = new HashMap<UUID, String>();
        for (var s : invoices.list()) {
            if (!"FACTURE".equals(s.type()) || s.remaining() <= 0 || "BROUILLON".equals(s.state()) || "ANNULEE".equals(s.state())) {
                continue;
            }
            if ((f.clientId() != null && !f.clientId().equals(s.clientId())) || (f.plan() != null && !f.plan().equals(plans.get(s.id())))) {
                continue;
            }
            var amounts = rows.computeIfAbsent(s.clientId(), k -> new long[5]);
            names.put(s.clientId(), s.clientName());
            amounts[Reporting.bucket(Reporting.daysLate(s.dueOn(), today)).ordinal()] += s.remaining();
        }
        return rows.entrySet().stream()
                .map(e -> {
                    var a = e.getValue();
                    return new AgingRow(e.getKey(), names.get(e.getKey()), a[0], a[1], a[2], a[3], a[4], a[0] + a[1] + a[2] + a[3] + a[4]);
                })
                .sorted(Comparator.comparingLong((AgingRow r) -> r.over() + r.d90() + r.d60() + r.d30()).reversed()
                        .thenComparing(AgingRow::client))
                .toList();
    }

    // --- Parc : contrats en cours, filtrés par client et formule

    /** Crèche dans le périmètre du filtre, avec son contrat en cours (s'il en a un). */
    private record Scoped(UUID crecheId, String slug, String name, String lifecycle, UUID clientId, ContractRepository.Contract contract) {
    }

    private List<Scoped> scope(Filter f) {
        var today = LocalDate.now(clock);
        var running = new HashMap<UUID, ContractRepository.Contract>();
        for (var c : contracts.running(today)) {
            if (c.covers(today)) {
                running.put(c.crecheId(), c);
            }
        }
        var result = new ArrayList<Scoped>();
        jdbc.query("SELECT id, slug, name, lifecycle, client_id FROM creche WHERE lifecycle <> 'DEMO' ORDER BY name", rs -> {
            var id = rs.getObject("id", UUID.class);
            var contract = running.get(id);
            var clientId = rs.getObject("client_id", UUID.class);
            if (f.clientId() != null && !f.clientId().equals(clientId)) {
                return;
            }
            if (f.plan() != null && (contract == null || !f.plan().equals(contract.plan()))) {
                return;
            }
            result.add(new Scoped(id, rs.getString("slug"), rs.getString("name"), rs.getString("lifecycle"), clientId, contract));
        });
        return result;
    }

    private long monthly(ContractRepository.Contract c) {
        try {
            return Pricing.quote(catalogue.catalogue(c.priceListId()), c.terms(), 1).total();
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    /**
     * Revenu mensuel récurrent ventilé : la formule (les anciennes comprises, ADR 0021), les modules à la carte des anciens
     * contrats, les options, les remises.
     */
    List<PlanRow> mrrByPlan(LocalDate today, List<Scoped> scope) {
        var totals = new LinkedHashMap<String, Long>();
        for (var key : List.of("FORMULE", "MODULES", "OPTIONS", "REMISES")) {
            totals.put(key, 0L);
        }
        for (var s : scope) {
            if (s.contract() == null) {
                continue;
            }
            Pricing.Quote quote;
            try {
                quote = Pricing.quote(catalogue.catalogue(s.contract().priceListId()), s.contract().terms(), 1);
            } catch (IllegalArgumentException e) {
                continue;
            }
            for (var line : quote.lines()) {
                String key;
                if (PLANS.contains(line.code())) {
                    key = "FORMULE";
                } else if (line.code().startsWith("REMISE")) {
                    key = "REMISES";
                } else if (Set.of("PC", "TABLETTE", "PREMIUM").contains(line.code())) {
                    key = "OPTIONS";
                } else {
                    key = "MODULES";
                }
                totals.merge(key, line.amount(), Long::sum);
            }
        }
        var labels = Map.of("FORMULE", "Formule Ti’Cagou", "MODULES", "Modules à la carte (anciens contrats)", "OPTIONS",
                "PC, tablettes et support", "REMISES", "Remises");
        return totals.entrySet().stream().filter(e -> e.getValue() != 0).map(e -> new PlanRow(e.getKey(), labels.get(e.getKey()), e.getValue()))
                .toList();
    }

    /** PC ou tablettes au-delà du contrat (lus dans l'instance) : ce qu'ils rapporteraient par mois au barème du jour. */
    List<Upsell> upsell(LocalDate today, List<Scoped> scope) {
        var slugs = new HashMap<String, Scoped>();
        scope.forEach(s -> slugs.put(s.slug(), s));
        var list = catalogue.priceListAt(today).map(l -> catalogue.catalogue(l.id())).orElse(null);
        long pc = list == null ? 0 : list.find("EQUIPEMENT", "PC", null).map(Pricing.PriceItem::price).orElse(0L);
        long tablet = list == null ? 0 : list.find("EQUIPEMENT", "TABLETTE", null).map(Pricing.PriceItem::price).orElse(0L);
        var result = new ArrayList<Upsell>();
        for (var row : parc.parc().creches()) {
            if (!slugs.containsKey(row.slug()) || !"CLIENT".equals(row.lifecycle())) {
                continue;
            }
            if (row.desktops() != null && row.desktopLimit() != null && row.desktopLimit() > 0 && row.desktops() > row.desktopLimit()) {
                int extra = row.desktops() - row.desktopLimit();
                result.add(new Upsell(row.slug(), row.name(), "%d PC utilisés pour %d au contrat".formatted(row.desktops(), row.desktopLimit()),
                        extra * pc));
            }
            if (row.tablets() != null && row.tabletLimit() != null && row.tabletLimit() > 0 && row.tablets() > row.tabletLimit()) {
                int extra = row.tablets() - row.tabletLimit();
                result.add(new Upsell(row.slug(), row.name(), "%d tablettes appairées pour %d au contrat".formatted(row.tablets(), row.tabletLimit()),
                        extra * tablet));
            }
        }
        return result;
    }

    /**
     * Risque de départ : direction sans connexion depuis 14 jours, présences pointées en baisse d'au moins 30 % sur quatre
     * semaines, ou au moins deux factures payées en retard (ou encore dues après échéance) sur 12 mois.
     */
    List<Risk> risks(LocalDate today, List<Scoped> scope) {
        var clients = new HashMap<UUID, Scoped>();
        scope.stream().filter(s -> "CLIENT".equals(s.lifecycle())).forEach(s -> clients.put(s.crecheId(), s));
        var result = new ArrayList<Risk>();
        jdbc.query("""
                SELECT id, supervision_status->'usage'->>'lastDirectionLoginOn' AS login FROM creche
                WHERE lifecycle = 'CLIENT' AND supervision_status IS NOT NULL""", rs -> {
            var s = clients.get(rs.getObject("id", UUID.class));
            var login = rs.getString("login");
            if (s == null || login == null) {
                return;
            }
            try {
                long days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(login.substring(0, 10)), today);
                if (days >= NO_LOGIN_DAYS) {
                    result.add(new Risk(s.slug(), s.name(), "CONNEXION", "aucune connexion direction depuis %d jours".formatted(days)));
                }
            } catch (java.time.DateTimeException | IndexOutOfBoundsException e) {
                // Date illisible : rien à dire.
            }
        });
        var monthAgo = today.minusDays(28).atStartOfDay(NOUMEA).toInstant();
        jdbc.query("""
                SELECT c.id,
                       (SELECT (data->>'attendances7d')::int FROM creche_snapshot s WHERE s.creche_id = c.id AND data->>'attendances7d' IS NOT NULL
                        ORDER BY at DESC LIMIT 1) AS now,
                       (SELECT (data->>'attendances7d')::int FROM creche_snapshot s WHERE s.creche_id = c.id AND data->>'attendances7d' IS NOT NULL AND at <= ?
                        ORDER BY at DESC LIMIT 1) AS before
                FROM creche c WHERE c.lifecycle = 'CLIENT'""", rs -> {
            var s = clients.get(rs.getObject("id", UUID.class));
            Integer now = (Integer) rs.getObject("now");
            Integer before = (Integer) rs.getObject("before");
            if (s != null && now != null && before != null && before >= 10 && (before - now) * 100 >= before * USAGE_DROP_PERCENT) {
                result.add(new Risk(s.slug(), s.name(), "USAGE", "présences pointées en baisse : %d sur 7 jours, contre %d il y a 4 semaines"
                        .formatted(now, before)));
            }
        }, Timestamp.from(monthAgo));
        jdbc.query("""
                SELECT i.creche_id, count(*) AS late FROM invoice i
                LEFT JOIN LATERAL (SELECT max(p.received_on) AS last_paid, coalesce(sum(a.amount), 0) AS paid
                                   FROM payment_allocation a JOIN payment p ON p.id = a.payment_id WHERE a.invoice_id = i.id) x ON true
                WHERE i.type = 'FACTURE' AND i.status = 'VALIDEE' AND i.creche_id IS NOT NULL AND i.issued_on >= ? AND i.due_on < ?
                  AND (x.last_paid > i.due_on
                       OR x.paid < i.total_ttc - coalesce((SELECT sum(c.total_ttc) FROM invoice c WHERE c.corrects_id = i.id AND c.status = 'VALIDEE'), 0))
                GROUP BY i.creche_id HAVING count(*) >= ?""", rs -> {
            var s = clients.get(rs.getObject("creche_id", UUID.class));
            if (s != null) {
                result.add(new Risk(s.slug(), s.name(), "PAIEMENT", "%d retards de paiement sur 12 mois".formatted(rs.getInt("late"))));
            }
        }, Date.valueOf(today.minusMonths(12)), Date.valueOf(today), LATE_PAYMENTS);
        return result;
    }

    /**
     * Départs sur 12 mois glissants : crèches résiliées dont le dernier contrat a fini dans l'année ; base : crèches
     * couvertes par un contrat il y a un an.
     */
    int[] churn(LocalDate today, List<Scoped> scope) {
        var ids = new java.util.HashSet<UUID>();
        scope.forEach(s -> ids.add(s.crecheId()));
        var yearAgo = today.minusMonths(12);
        int[] result = new int[2];
        jdbc.query("""
                SELECT c.id, c.lifecycle, max(sc.ends_on) AS ended, bool_or(sc.ends_on IS NULL) AS open, c.updated_at,
                       bool_or(sc.starts_on <= ? AND (sc.ends_on IS NULL OR sc.ends_on >= ?)) AS covered
                FROM creche c LEFT JOIN subscription_contract sc ON sc.creche_id = c.id
                WHERE c.lifecycle <> 'DEMO' GROUP BY c.id, c.lifecycle, c.updated_at""", rs -> {
            if (!ids.contains(rs.getObject("id", UUID.class))) {
                return;
            }
            if (rs.getBoolean("covered")) {
                result[1]++;
            }
            if ("RESILIE".equals(rs.getString("lifecycle"))) {
                var ended = rs.getDate("ended") != null && !rs.getBoolean("open") ? rs.getDate("ended").toLocalDate()
                        : rs.getTimestamp("updated_at").toInstant().atZone(NOUMEA).toLocalDate();
                if (!ended.isBefore(yearAgo) && !ended.isAfter(today)) {
                    result[0]++;
                }
            }
        }, Date.valueOf(yearAgo), Date.valueOf(yearAgo));
        return result;
    }

    /** Revenu mensuel de chaque crèche sous contrat, face au support saisi sur la période (ramené au mois) et à l'hébergement. */
    List<Profit> profitability(LocalDate today, Filter f, List<Scoped> scope, Settings settings, int months) {
        var minutes = new HashMap<UUID, Long>();
        jdbc.query("""
                SELECT creche_id, sum(minutes) AS minutes FROM creche_note WHERE minutes IS NOT NULL AND at >= ? AND at < ?
                GROUP BY creche_id""", rs -> {
            minutes.put(rs.getObject("creche_id", UUID.class), rs.getLong("minutes"));
        }, Timestamp.from(f.from().atStartOfDay(NOUMEA).toInstant()), Timestamp.from(f.to().plusDays(1).atStartOfDay(NOUMEA).toInstant()));
        var result = new ArrayList<Profit>();
        for (var s : scope) {
            if (s.contract() == null) {
                continue;
            }
            long revenue = monthly(s.contract());
            long spent = minutes.getOrDefault(s.crecheId(), 0L);
            long cost = Reporting.supportCost(spent, months, settings.supportHourlyRate()) + settings.hostingPerCreche();
            result.add(new Profit(s.slug(), s.name(), revenue, Xpf.divide(spent, months), cost, revenue - cost));
        }
        result.sort(Comparator.comparingLong(Profit::margin));
        return result;
    }

    // --- Réglages, listes

    @Transactional(readOnly = true)
    public Settings settings() {
        return jdbc.queryForObject("SELECT support_hourly_rate, hosting_per_creche FROM reporting_settings WHERE id = 1",
                (rs, n) -> new Settings(rs.getLong(1), rs.getLong(2)));
    }

    @Transactional
    public Settings updateSettings(long hourlyRate, long hosting) {
        jdbc.update("UPDATE reporting_settings SET support_hourly_rate = ?, hosting_per_creche = ?, updated_at = ? WHERE id = 1", hourlyRate,
                hosting, Timestamp.from(clock.instant()));
        audit.record("REGLAGES_RENTABILITE", "reporting", Map.of("tauxHoraire", hourlyRate, "hebergement", hosting));
        return settings();
    }

    private List<ClientOption> clients() {
        return jdbc.query("SELECT id, name FROM client ORDER BY name", (rs, n) -> new ClientOption(rs.getObject("id", UUID.class), rs.getString("name")));
    }

    public static String planLabel(String plan) {
        return plan == null ? "" : PLAN_LABELS.getOrDefault(plan, plan);
    }

    private JsonNode node(String text) {
        try {
            return text == null ? null : json.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }
}
