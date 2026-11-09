package nc.ticagou.console.reporting;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

import nc.ticagou.console.billing.Xpf;
import nc.ticagou.console.reporting.ReportingService.Receipt;
import nc.ticagou.console.reporting.ReportingService.Report;
import nc.ticagou.console.reporting.ReportingService.Sale;

/**
 * Exports pour le comptable : journal des ventes et journal des encaissements en CSV (point-virgule, UTF-8 avec BOM, dates
 * JJ/MM/AAAA, montants en francs entiers, avoirs en négatif), et classeur Excel (fastexcel, ADR 0006) avec la synthèse.
 */
public final class ReportingExports {

    private ReportingExports() {
    }

    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRENCH);

    // --- CSV

    static List<String> salesHeader(List<Sale> sales) {
        var header = new ArrayList<>(List.of("Date", "Pièce", "Type", "Client", "RIDET", "Crèche", "Formule", "Objet", "Échéance", "Total HT",
                "TGC", "Total TTC"));
        for (var rate : rates(sales)) {
            header.add("Base HT " + rate(rate));
            header.add("TGC " + rate(rate));
        }
        return header;
    }

    static List<List<String>> salesRows(List<Sale> sales) {
        var rates = rates(sales);
        var rows = new ArrayList<List<String>>();
        for (var s : sales) {
            var row = new ArrayList<>(List.of(day(s.issuedOn()), s.number(), "AVOIR".equals(s.type()) ? "Avoir" : "Facture", s.client(),
                    nz(s.ridet()), nz(s.creche()), ReportingService.planLabel(s.plan()), s.subject(), day(s.dueOn()), Long.toString(s.ht()),
                    Long.toString(s.tgc()), Long.toString(s.ttc())));
            for (var rate : rates) {
                var amounts = s.rates().get(rate);
                row.add(amounts == null ? "0" : Long.toString(amounts[0]));
                row.add(amounts == null ? "0" : Long.toString(amounts[1]));
            }
            rows.add(row);
        }
        return rows;
    }

    public static byte[] salesCsv(List<Sale> sales) {
        return csv(salesHeader(sales), salesRows(sales));
    }

    public static byte[] receiptsCsv(List<Receipt> receipts) {
        var rows = receipts.stream().map(r -> List.of(day(r.receivedOn()), r.client(), Long.toString(r.amount()), method(r.method()),
                nz(r.reference()), nz(r.invoices()))).toList();
        return csv(List.of("Date", "Client", "Montant", "Mode", "Référence", "Pièces réglées"), rows);
    }

    static byte[] csv(List<String> header, List<? extends List<String>> rows) {
        var text = new StringBuilder("﻿");
        line(text, header);
        rows.forEach(r -> line(text, r));
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void line(StringBuilder text, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                text.append(';');
            }
            var cell = cells.get(i) == null ? "" : cells.get(i);
            if (cell.contains(";") || cell.contains("\"") || cell.contains("\n") || cell.contains("\r")) {
                cell = '"' + cell.replace("\"", "\"\"") + '"';
            }
            text.append(cell);
        }
        text.append("\r\n");
    }

    // --- Excel

    public static void workbook(Report report, List<Sale> sales, List<Receipt> receipts, OutputStream out) throws IOException {
        var wb = new Workbook(out, "Ti'Cagou Console", "1.0");
        var f = report.filter();
        var k = report.kpis();

        var summary = wb.newWorksheet("Synthèse");
        int r = 0;
        summary.value(r, 0, "Reporting Ti'Cagou, du %s au %s".formatted(day(f.from()), day(f.to())));
        summary.style(r, 0).bold().set();
        r += 2;
        r = kv(summary, r, "Chiffre d'affaires HT", k.revenueHt());
        r = kv(summary, r, "dont abonnements", k.subscriptionsHt());
        r = kv(summary, r, "dont prestations", k.servicesHt());
        r = kv(summary, r, "Encaissé TTC", k.collected());
        r = kv(summary, r, "TGC collectée", k.tgc());
        r = kv(summary, r, "Revenu mensuel récurrent HT (à ce jour)", k.mrr());
        summary.value(r, 0, "Départs sur 12 mois");
        summary.value(r, 1, k.departures());
        summary.width(0, 42);
        summary.width(1, 16);

        var months = wb.newWorksheet("Par mois");
        header(months, "Mois", "Total HT", "TGC", "Total TTC", "Encaissé");
        r = 1;
        for (var m : report.months()) {
            months.value(r, 0, YearMonth.parse(m.month()).format(MONTH));
            months.value(r, 1, m.ht());
            months.value(r, 2, m.tgc());
            months.value(r, 3, m.ttc());
            months.value(r, 4, m.collected());
            r++;
        }
        total(months, r, 1, report.months().stream().mapToLong(ReportingService.MonthRow::ht).sum(),
                report.months().stream().mapToLong(ReportingService.MonthRow::tgc).sum(),
                report.months().stream().mapToLong(ReportingService.MonthRow::ttc).sum(),
                report.months().stream().mapToLong(ReportingService.MonthRow::collected).sum());

        var tgc = wb.newWorksheet("TGC");
        header(tgc, "Taux", "Base HT", "TGC collectée");
        r = 1;
        for (var row : report.tgc()) {
            tgc.value(r, 0, rate(row.rate()));
            tgc.value(r, 1, row.base());
            tgc.value(r, 2, row.tgc());
            r++;
        }
        total(tgc, r, 1, report.tgc().stream().mapToLong(ReportingService.RateRow::base).sum(),
                report.tgc().stream().mapToLong(ReportingService.RateRow::tgc).sum());

        table(wb.newWorksheet("Ventes"), salesHeader(sales), salesRows(sales), 9);
        table(wb.newWorksheet("Encaissements"), List.of("Date", "Client", "Montant", "Mode", "Référence", "Pièces réglées"),
                receipts.stream().map(x -> List.of(day(x.receivedOn()), x.client(), Long.toString(x.amount()), method(x.method()), nz(x.reference()),
                        nz(x.invoices()))).toList(), 2);

        var aging = wb.newWorksheet("Balance âgée");
        header(aging, "Client", "Non échu", "0–30 j", "31–60 j", "61–90 j", "+ 90 j", "Total");
        r = 1;
        for (var a : report.aging()) {
            aging.value(r, 0, a.client());
            long[] v = {a.notDue(), a.d30(), a.d60(), a.d90(), a.over(), a.total()};
            for (int c = 0; c < v.length; c++) {
                aging.value(r, c + 1, v[c]);
            }
            r++;
        }
        var t = report.agingTotal();
        total(aging, r, 1, t.notDue(), t.d30(), t.d60(), t.d90(), t.over(), t.total());

        var plans = wb.newWorksheet("Revenu mensuel");
        header(plans, "Poste", "Par mois HT");
        r = 1;
        for (var p : report.mrrByPlan()) {
            plans.value(r, 0, p.label());
            plans.value(r, 1, p.monthly());
            r++;
        }
        total(plans, r, 1, report.mrrByPlan().stream().mapToLong(ReportingService.PlanRow::monthly).sum());

        var profit = wb.newWorksheet("Rentabilité");
        header(profit, "Crèche", "Revenu / mois", "Support / mois (min)", "Coût / mois", "Marge estimée");
        r = 1;
        for (var p : report.profitability()) {
            profit.value(r, 0, p.name());
            profit.value(r, 1, p.revenue());
            profit.value(r, 2, p.supportMinutes());
            profit.value(r, 3, p.cost());
            profit.value(r, 4, p.margin());
            r++;
        }
        profit.value(r + 1, 0, "Support à %s de l'heure, hébergement %s par crèche et par mois.".formatted(
                Xpf.format(report.settings().supportHourlyRate()), Xpf.format(report.settings().hostingPerCreche())));
        wb.finish();
    }

    private static int kv(Worksheet ws, int r, String label, long value) {
        ws.value(r, 0, label);
        ws.value(r, 1, value);
        ws.style(r, 1).format("#,##0").set();
        return r + 1;
    }

    private static void header(Worksheet ws, String... titles) {
        for (int c = 0; c < titles.length; c++) {
            ws.value(0, c, titles[c]);
            ws.width(c, c == 0 ? 28 : 16);
        }
        ws.range(0, 0, 0, titles.length - 1).style().bold().fillColor("DCEFEE").set();
    }

    private static void total(Worksheet ws, int r, int firstColumn, long... values) {
        ws.value(r, 0, "Total");
        for (int i = 0; i < values.length; i++) {
            ws.value(r, firstColumn + i, values[i]);
        }
        ws.range(r, 0, r, firstColumn + values.length - 1).style().bold().set();
    }

    /** Tableau dont les colonnes à partir de {@code firstNumber} (sauf « Échéance ») sont des montants. */
    private static void table(Worksheet ws, List<String> header, List<? extends List<String>> rows, int firstNumber) {
        header(ws, header.toArray(String[]::new));
        int r = 1;
        for (var row : rows) {
            for (int c = 0; c < row.size(); c++) {
                var cell = row.get(c);
                if (c >= firstNumber && cell != null && cell.matches("-?\\d+") && !header.get(c).startsWith("Échéance")
                        && !header.get(c).startsWith("Mode") && !header.get(c).startsWith("Référence")) {
                    ws.value(r, c, Long.parseLong(cell));
                } else {
                    ws.value(r, c, cell);
                }
            }
            r++;
        }
    }

    private static TreeSet<Integer> rates(List<Sale> sales) {
        var rates = new TreeSet<Integer>();
        sales.forEach(s -> rates.addAll(s.rates().keySet()));
        return rates;
    }

    /** « 11 % » avec une espace ordinaire (celle des PDF est insécable, mal lue par certains tableurs). */
    static String rate(int tenths) {
        return Xpf.rate(tenths).replace(' ', ' ');
    }

    static String day(LocalDate date) {
        return date == null ? "" : DAY.format(date);
    }

    private static String nz(String text) {
        return text == null ? "" : text;
    }

    private static String method(String method) {
        return switch (method == null ? "" : method) {
            case "VIREMENT" -> "Virement";
            case "CHEQUE" -> "Chèque";
            case "ESPECES" -> "Espèces";
            default -> "Autre";
        };
    }
}
