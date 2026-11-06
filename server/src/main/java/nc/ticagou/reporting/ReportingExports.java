package nc.ticagou.reporting;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.springframework.stereotype.Service;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.common.Formats;
import nc.ticagou.reporting.ActivityCalculator.Indicators;
import nc.ticagou.reporting.ReportingService.Report;

/** Bilan d'activité en PDF (dossiers des financeurs) et en Excel (détail par section et par mois). */
@Service
public class ReportingExports {

    public record Export(byte[] content, String fileName) {
    }

    static final Map<String, String> CATEGORIES = Map.of("PROVINCE", "Province", "ORGANISME", "Organisme",
            "EMPLOYEUR", "Employeur", "COMMUNE", "Commune", "AUTRE", "Autre");

    private final ReportingService reporting;
    private final ITemplateEngine templates;
    private final AuditService audit;
    private final Clock clock;

    public ReportingExports(ReportingService reporting, ITemplateEngine templates, AuditService audit, Clock clock) {
        this.reporting = reporting;
        this.templates = templates;
        this.audit = audit;
        this.clock = clock;
    }

    static String hours(long minutes) {
        return String.format(Locale.FRANCE, "%,d h", Math.round(minutes / 60.0)).replace(' ', ' ').replace(' ', ' ');
    }

    static String percent(int perMille) {
        return String.format(Locale.FRANCE, "%.1f %%", perMille / 10.0);
    }

    public Export pdf(int year, Integer month) {
        var report = reporting.report(year, month);
        var vars = new HashMap<String, Object>();
        vars.put("r", report);
        vars.put("period", "Du %s au %s · %d jours d’ouverture".formatted(Formats.date(report.from()), Formats.date(report.to()),
                report.openDays()));
        vars.put("hours", (java.util.function.Function<Long, String>) ReportingExports::hours);
        vars.put("percent", (java.util.function.Function<Integer, String>) ReportingExports::percent);
        vars.put("xpf", (java.util.function.Function<Long, String>) Formats::xpf);
        vars.put("monthLabel", (java.util.function.Function<java.time.YearMonth, String>) Formats::monthYear);
        vars.put("category", (java.util.function.Function<String, String>) c -> CATEGORIES.getOrDefault(c, c));
        var now = clock.instant().atZone(clock.getZone());
        vars.put("printedAt", "Édité le %s à %s".formatted(Formats.date(now.toLocalDate()), Formats.time(now.toLocalTime())));
        var html = templates.process("bilan-activite", new Context(Locale.FRANCE, vars));
        var out = new ByteArrayOutputStream();
        try {
            var builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        record(report, "pdf");
        return new Export(out.toByteArray(), fileName(year, month, "pdf"));
    }

    public Export xlsx(int year, Integer month) {
        var report = reporting.report(year, month);
        var out = new ByteArrayOutputStream();
        try {
            var workbook = new Workbook(out, "Ti'Cagou", "1.0");
            var sheet = workbook.newWorksheet("Bilan");
            sheet.value(0, 0, report.structureName() + " · Bilan d’activité · " + report.label());
            sheet.style(0, 0).bold().fontSize(13).set();
            sheet.value(1, 0, "Du %s au %s · %d jours d’ouverture · capacité agréée %d places".formatted(
                    Formats.date(report.from()), Formats.date(report.to()), report.openDays(), report.capacity()));
            int row = 3;
            String[] titles = {"Périmètre", "Places", "Enfants accueillis", "Heures réalisées", "Heures contractualisées",
                    "Capacité théorique (h)", "Taux d’occupation", "Taux de réalisation"};
            header(sheet, row++, titles);
            indicators(sheet, row++, "Structure", report.capacity(), report.total());
            for (var s : report.sections()) {
                indicators(sheet, row++, s.name(), s.capacity(), s.indicators());
            }
            if (!report.months().isEmpty()) {
                row += 1;
                header(sheet, row++, new String[] {"Mois", "Jours d’ouverture", "Enfants accueillis", "Heures réalisées",
                        "Heures contractualisées", "Capacité théorique (h)", "Taux d’occupation", "Taux de réalisation"});
                for (var m : report.months()) {
                    sheet.value(row, 0, Formats.monthYear(m.month()));
                    sheet.value(row, 1, m.openDays());
                    figures(sheet, row++, m.indicators());
                }
            }
            row += 1;
            header(sheet, row++, new String[] {"Financeur", "Catégorie", "Enfants aidés", "Aides déduites (F)"});
            for (var f : report.funders()) {
                sheet.value(row, 0, f.name());
                sheet.value(row, 1, CATEGORIES.getOrDefault(f.category(), f.category()));
                sheet.value(row, 2, f.children());
                sheet.value(row++, 3, f.amount());
            }
            row += 1;
            header(sheet, row++, new String[] {"Motif d’absence", "Déductible", "Jours"});
            for (var a : report.absences()) {
                sheet.value(row, 0, a.reason());
                sheet.value(row, 1, a.deductible() ? "Oui" : "Non");
                sheet.value(row++, 2, a.days());
            }
            row += 1;
            var money = report.money();
            header(sheet, row++, new String[] {"Facturation (F)", "Montant"});
            Object[][] lines = {{"Factures émises", money.invoices()}, {"Frais de garde facturés", money.invoiced()},
                    {"TGC", money.tgc()}, {"Aides déduites", money.aids()}, {"Reste à charge des familles", money.families()},
                    {"Avoirs", money.credited()}, {"Reste dû aujourd’hui", money.remaining()}};
            for (var line : lines) {
                sheet.value(row, 0, (String) line[0]);
                sheet.value(row++, 1, ((Number) line[1]).longValue());
            }
            sheet.width(0, 30);
            for (int c = 1; c < 8; c++) {
                sheet.width(c, 18);
            }
            workbook.finish();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        record(report, "xlsx");
        return new Export(out.toByteArray(), fileName(year, month, "xlsx"));
    }

    private static void header(Worksheet sheet, int row, String[] titles) {
        for (int c = 0; c < titles.length; c++) {
            sheet.value(row, c, titles[c]);
        }
        sheet.range(row, 0, row, titles.length - 1).style().bold().fillColor("DCEFEE").set();
    }

    private static void indicators(Worksheet sheet, int row, String name, int places, Indicators i) {
        sheet.value(row, 0, name);
        sheet.value(row, 1, places);
        figures(sheet, row, i);
    }

    private static void figures(Worksheet sheet, int row, Indicators i) {
        sheet.value(row, 2, i.children());
        sheet.value(row, 3, Math.round(i.realizedMinutes() / 60.0));
        sheet.value(row, 4, Math.round(i.plannedMinutes() / 60.0));
        sheet.value(row, 5, Math.round(i.capacityMinutes() / 60.0));
        sheet.value(row, 6, i.occupancy() / 1000.0);
        sheet.style(row, 6).format("0.0%").set();
        sheet.value(row, 7, i.realization() / 1000.0);
        sheet.style(row, 7).format("0.0%").set();
    }

    private static String fileName(int year, Integer month, String extension) {
        return month == null ? "bilan-activite-%d.%s".formatted(year, extension)
                : "bilan-activite-%d-%02d.%s".formatted(year, month, extension);
    }

    private void record(Report report, String format) {
        audit.record(AuditAction.REPORT_EXPORTED, "report", null, Map.of("period", report.label(), "format", format));
    }
}
