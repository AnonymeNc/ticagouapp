package nc.ticagou.accounting;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.accounting.AccountingSettingsService.Column;
import nc.ticagou.accounting.AccountingSettingsService.SettingsDto;
import nc.ticagou.accounting.JournalBuilder.CreditNote;
import nc.ticagou.accounting.JournalBuilder.Invoice;
import nc.ticagou.accounting.JournalBuilder.InvoiceLine;
import nc.ticagou.accounting.JournalBuilder.Journal;
import nc.ticagou.accounting.JournalBuilder.Line;
import nc.ticagou.accounting.JournalBuilder.Party;
import nc.ticagou.accounting.JournalBuilder.Payment;
import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.common.ApiException;

/**
 * Export des écritures d'une période vers le logiciel du cabinet comptable : CSV aux colonnes paramétrables ou
 * fichier des écritures comptables (FEC, 18 colonnes séparées par des tabulations). Factures et avoirs à leur date
 * d'émission, encaissements à leur date, annulations d'encaissement au jour de l'annulation.
 */
@Service
public class AccountingExporter {

    public enum Format { CSV, FEC }

    public record JournalTotal(String journal, String label, int pieces, long debit, long credit) {
    }

    /** Contrôle avant export : totaux par journal, écritures équilibrées, comptes renseignés. */
    public record Check(LocalDate from, LocalDate to, List<JournalTotal> journals, long debit, long credit,
                        List<String> anomalies) {
    }

    public record ExportFile(String fileName, byte[] content) {
    }

    private static final DateTimeFormatter COMPACT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter FRENCH = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final JdbcTemplate jdbc;
    private final AccountingSettingsService settings;
    private final AuditService audit;
    private final Clock clock;

    public AccountingExporter(JdbcTemplate jdbc, AccountingSettingsService settings, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Check check(LocalDate from, LocalDate to) {
        var structureId = CurrentUser.get().structureId();
        var result = build(structureId, from, to);
        var config = settings.settings(structureId);
        var journals = new ArrayList<JournalTotal>();
        for (var journal : Journal.values()) {
            var lines = result.lines().stream().filter(l -> l.journal() == journal).toList();
            if (!lines.isEmpty()) {
                journals.add(new JournalTotal(code(journal, config), label(journal),
                        (int) lines.stream().map(Line::piece).distinct().count(),
                        lines.stream().mapToLong(Line::debit).sum(), lines.stream().mapToLong(Line::credit).sum()));
            }
        }
        return new Check(from, to, journals, result.debits(), result.credits(), anomalies(result));
    }

    @Transactional
    public ExportFile export(LocalDate from, LocalDate to, Format format) {
        var user = CurrentUser.get();
        var structureId = user.structureId();
        var result = build(structureId, from, to);
        var anomalies = anomalies(result);
        if (!anomalies.isEmpty()) {
            throw ApiException.rule("accounting_anomalies", "Export impossible : " + String.join(" ", anomalies));
        }
        var config = settings.settings(structureId);
        var codes = new LinkedHashMap<Party, String>();
        result.lines().stream().map(Line::party).filter(p -> p != null).distinct()
                .forEach(p -> codes.put(p, settings.partyCode(structureId, p, config)));
        var file = switch (format) {
            case CSV -> new ExportFile("ecritures-%s-%s.csv".formatted(from, to), csv(result.lines(), config, codes));
            case FEC -> new ExportFile("%sFEC%s.txt".formatted(ridet(structureId), COMPACT.format(to)), fec(result.lines(), config, codes));
        };
        audit.record(AuditAction.ACCOUNTING_EXPORTED, "accounting_export", null, Map.of("from", from.toString(),
                "to", to.toString(), "format", format.name(), "lines", result.lines().size()));
        return file;
    }

    private static List<String> anomalies(JournalBuilder.Result result) {
        var anomalies = new ArrayList<>(result.anomalies());
        var gaps = result.lines().stream().collect(Collectors.groupingBy(Line::piece, LinkedHashMap::new,
                Collectors.summingLong(l -> l.debit() - l.credit())));
        gaps.forEach((piece, gap) -> {
            if (gap != 0) {
                anomalies.add("Pièce %s déséquilibrée de %d F.".formatted(piece, gap));
            }
        });
        return anomalies;
    }

    // ------------------------------------------------------------------------------------------- lecture

    JournalBuilder.Result build(UUID structureId, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw ApiException.rule("accounting_period", "La fin de la période précède son début.");
        }
        if (from.plusYears(1).isBefore(to)) {
            throw ApiException.rule("accounting_period", "Exportez au plus une année à la fois.");
        }
        return JournalBuilder.build(settings.resolver(structureId), invoices(structureId, from, to),
                creditNotes(structureId, from, to), payments(structureId, from, to));
    }

    private List<Invoice> invoices(UUID structureId, LocalDate from, LocalDate to) {
        var lines = new LinkedHashMap<UUID, List<InvoiceLine>>();
        jdbc.query("""
                SELECT l.invoice_id, l.kind, l.amount, l.tgc_amount, l.funder_id, f.name AS funder_name
                FROM invoice_line l JOIN invoice i ON i.id = l.invoice_id LEFT JOIN funder f ON f.id = l.funder_id
                WHERE i.structure_id = ? AND i.issued_on BETWEEN ? AND ? ORDER BY l.invoice_id, l.position""", rs -> {
            lines.computeIfAbsent(rs.getObject("invoice_id", UUID.class), k -> new ArrayList<>()).add(new InvoiceLine(
                    JournalBuilder.Kind.valueOf(rs.getString("kind")), rs.getLong("amount"), rs.getLong("tgc_amount"),
                    rs.getObject("funder_id", UUID.class), rs.getString("funder_name")));
        }, structureId, Date.valueOf(from), Date.valueOf(to));
        return jdbc.query("""
                SELECT i.id, i.number, i.issued_on, i.family_id, i.payer_name, i.family_total FROM invoice i
                WHERE i.structure_id = ? AND i.issued_on BETWEEN ? AND ? ORDER BY i.issued_on, i.number""",
                (rs, n) -> new Invoice(rs.getString("number"), rs.getDate("issued_on").toLocalDate(),
                        new Party(false, rs.getObject("family_id", UUID.class), rs.getString("payer_name")),
                        rs.getLong("family_total"), lines.getOrDefault(rs.getObject("id", UUID.class), List.of())),
                structureId, Date.valueOf(from), Date.valueOf(to));
    }

    private List<CreditNote> creditNotes(UUID structureId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT c.number, c.issued_on, c.family_total, c.aid_total, i.id AS invoice_id, i.number AS invoice_number,
                       i.family_id, i.payer_name
                FROM credit_note c JOIN invoice i ON i.id = c.invoice_id
                WHERE c.structure_id = ? AND c.issued_on BETWEEN ? AND ? ORDER BY c.issued_on, c.number""",
                (rs, n) -> new CreditNote(rs.getString("number"), rs.getString("invoice_number"),
                        rs.getDate("issued_on").toLocalDate(),
                        new Party(false, rs.getObject("family_id", UUID.class), rs.getString("payer_name")),
                        rs.getLong("family_total"), aidsOf(rs.getObject("invoice_id", UUID.class), rs.getLong("aid_total"))),
                structureId, Date.valueOf(from), Date.valueOf(to));
    }

    /** Aides annulées par un avoir, réparties entre les financeurs de la facture au prorata de leurs aides. */
    private Map<Party, Long> aidsOf(UUID invoiceId, long aidTotal) {
        var aids = new LinkedHashMap<Party, Long>();
        if (aidTotal == 0) {
            return aids;
        }
        var rows = jdbc.queryForList("""
                SELECT l.funder_id, f.name, -sum(l.amount) AS amount FROM invoice_line l JOIN funder f ON f.id = l.funder_id
                WHERE l.invoice_id = ? AND l.kind = 'AIDE' GROUP BY l.funder_id, f.name ORDER BY f.name""", invoiceId);
        long invoiced = rows.stream().mapToLong(r -> ((Number) r.get("amount")).longValue()).sum();
        long left = aidTotal;
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            long share = i == rows.size() - 1 ? left : Math.round((double) aidTotal * ((Number) row.get("amount")).longValue() / invoiced);
            left -= share;
            aids.put(new Party(true, (UUID) row.get("funder_id"), (String) row.get("name")), share);
        }
        return aids;
    }

    private List<Payment> payments(UUID structureId, LocalDate from, LocalDate to) {
        var zone = clock.getZone().getId();
        var invoices = new LinkedHashMap<UUID, String>();
        jdbc.query("""
                SELECT a.payment_id, string_agg(coalesce(i.number, r.number), ', ' ORDER BY coalesce(i.number, r.number)) AS refs
                FROM payment_allocation a LEFT JOIN invoice i ON i.id = a.invoice_id LEFT JOIN funder_request r ON r.id = a.funder_request_id
                JOIN payment p ON p.id = a.payment_id WHERE p.structure_id = ? GROUP BY a.payment_id""",
                rs -> { invoices.put(rs.getObject("payment_id", UUID.class), rs.getString("refs")); }, structureId);
        var payments = new ArrayList<Payment>();
        jdbc.query("""
                SELECT p.id, p.receipt_number, p.paid_on, p.family_id, p.funder_id, p.payer_name, p.method, p.amount,
                       (p.cancelled_at AT TIME ZONE ?)::date AS cancelled_on
                FROM payment p WHERE p.structure_id = ?
                  AND (p.paid_on BETWEEN ? AND ? OR (p.cancelled_at AT TIME ZONE ?)::date BETWEEN ? AND ?)
                ORDER BY p.paid_on, p.created_at""", rs -> {
            var id = rs.getObject("id", UUID.class);
            var funder = rs.getObject("funder_id", UUID.class);
            var party = new Party(funder != null, funder != null ? funder : rs.getObject("family_id", UUID.class),
                    rs.getString("payer_name"));
            var number = rs.getString("receipt_number") != null ? rs.getString("receipt_number") : "ENC-" + id.toString().substring(0, 8);
            var method = JournalBuilder.Method.valueOf(rs.getString("method"));
            var paidOn = rs.getDate("paid_on").toLocalDate();
            if (!paidOn.isBefore(from) && !paidOn.isAfter(to)) {
                payments.add(new Payment(number, paidOn, party, method, rs.getLong("amount"), invoices.get(id), false));
            }
            var cancelled = rs.getDate("cancelled_on");
            if (cancelled != null && !cancelled.toLocalDate().isBefore(from) && !cancelled.toLocalDate().isAfter(to)) {
                payments.add(new Payment(number + "-ANN", cancelled.toLocalDate(), party, method, rs.getLong("amount"),
                        invoices.get(id), true));
            }
        }, zone, structureId, Date.valueOf(from), Date.valueOf(to), zone, Date.valueOf(from), Date.valueOf(to));
        return payments;
    }

    private String ridet(UUID structureId) {
        var ridet = jdbc.queryForList("SELECT ridet FROM structure WHERE id = ?", String.class, structureId);
        var digits = ridet.isEmpty() || ridet.getFirst() == null ? "" : ridet.getFirst().replaceAll("[^0-9]", "");
        return digits.isEmpty() ? "TICAGOU" : digits;
    }

    // ------------------------------------------------------------------------------------------- formats

    static String code(Journal journal, SettingsDto config) {
        return switch (journal) {
            case VENTES -> config.salesJournal();
            case BANQUE -> config.bankJournal();
            case CAISSE -> config.cashJournal();
        };
    }

    static String label(Journal journal) {
        return switch (journal) {
            case VENTES -> "Ventes";
            case BANQUE -> "Banque";
            case CAISSE -> "Caisse";
        };
    }

    /** CSV paramétrable : montants en francs entiers, séparateur et ordre des colonnes choisis par la structure. */
    static byte[] csv(List<Line> lines, SettingsDto config, Map<Party, String> codes) {
        var separator = "TAB".equals(config.csvSeparator()) ? "\t" : config.csvSeparator();
        var dates = switch (config.csvDate()) {
            case "AAAA-MM-JJ" -> DateTimeFormatter.ISO_LOCAL_DATE;
            case "AAAAMMJJ" -> COMPACT;
            default -> FRENCH;
        };
        var out = new StringBuilder();
        if (config.csvHeader()) {
            out.append(config.csvColumns().stream().map(c -> header(c)).collect(Collectors.joining(separator))).append("\r\n");
        }
        for (var line : lines) {
            out.append(config.csvColumns().stream().map(c -> escape(switch (c) {
                case JOURNAL -> code(line.journal(), config);
                case DATE -> dates.format(line.day());
                case PIECE -> line.piece();
                case COMPTE -> line.account();
                case AUXILIAIRE -> line.party() == null ? "" : codes.get(line.party());
                case LIBELLE -> line.label();
                case DEBIT -> line.debit() == 0 ? "" : Long.toString(line.debit());
                case CREDIT -> line.credit() == 0 ? "" : Long.toString(line.credit());
            }, separator)).collect(Collectors.joining(separator))).append("\r\n");
        }
        // BOM : Excel et la plupart des logiciels comptables reconnaissent alors l'UTF-8 (accents des libellés).
        return ("﻿" + out).getBytes(StandardCharsets.UTF_8);
    }

    private static String header(Column column) {
        return switch (column) {
            case JOURNAL -> "Journal";
            case DATE -> "Date";
            case PIECE -> "Pièce";
            case COMPTE -> "Compte";
            case AUXILIAIRE -> "Compte auxiliaire";
            case LIBELLE -> "Libellé";
            case DEBIT -> "Débit";
            case CREDIT -> "Crédit";
        };
    }

    private static String escape(String value, String separator) {
        var v = value == null ? "" : value;
        return v.contains(separator) || v.contains("\"") || v.contains("\n") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    static final List<String> FEC_COLUMNS = List.of("JournalCode", "JournalLib", "EcritureNum", "EcritureDate",
            "CompteNum", "CompteLib", "CompAuxNum", "CompAuxLib", "PieceRef", "PieceDate", "EcritureLib", "Debit", "Credit",
            "EcritureLet", "DateLet", "ValidDate", "Montantdevise", "Idevise");

    /** Fichier des écritures comptables : 18 colonnes, tabulations, dates AAAAMMJJ, montants « 1500,00 ». */
    static byte[] fec(List<Line> lines, SettingsDto config, Map<Party, String> codes) {
        var out = new StringBuilder(String.join("\t", FEC_COLUMNS)).append("\r\n");
        var numbers = new LinkedHashMap<String, Integer>();
        for (var line : lines) {
            int number = numbers.computeIfAbsent(line.journal() + "|" + line.piece(), k -> numbers.size() + 1);
            var party = line.party();
            out.append(String.join("\t", List.of(
                    code(line.journal(), config), label(line.journal()), Integer.toString(number),
                    COMPACT.format(line.day()), line.account(), clean(line.accountLabel()),
                    party == null ? "" : codes.get(party), party == null ? "" : clean(party.name()),
                    line.piece(), COMPACT.format(line.day()), clean(line.label()), amount(line.debit()),
                    amount(line.credit()), "", "", COMPACT.format(line.day()), "", "XPF"))).append("\r\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** XPF sans décimales : « 1500 » s'écrit « 1500,00 », seulement à l'écriture du fichier. */
    static String amount(long francs) {
        return francs + ",00";
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }
}
