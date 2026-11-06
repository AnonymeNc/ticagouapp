package nc.ticagou.meal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.common.Formats;
import nc.ticagou.config.TiCagouProperties;
import nc.ticagou.meal.MealDtos.MonthRow;
import nc.ticagou.meal.MealDtos.QuarterDto;

/**
 * Exports du récapitulatif trimestriel, à joindre à la demande d'aide : PDF d'une page à signer (Thymeleaf et
 * openhtmltopdf, ADR 0004), classeur Excel avec le détail par enfant et par jour (fastexcel, ADR 0006).
 */
@Service
public class MealExports {

    public record Export(byte[] content, String fileName) {
    }

    private final MealService meals;
    private final ITemplateEngine templates;
    private final AuditService audit;
    private final ZoneId zone;
    private final Clock clock;

    public MealExports(MealService meals, ITemplateEngine templates, AuditService audit, TiCagouProperties properties,
                       Clock clock) {
        this.meals = meals;
        this.templates = templates;
        this.audit = audit;
        this.zone = properties.zone();
        this.clock = clock;
    }

    @Transactional
    public Export pdf(int year, int quarter) {
        var summary = meals.quarter(year, quarter).summary();
        var structure = meals.structure();
        var vars = new HashMap<String, Object>();
        vars.put("structureName", structure.getName());
        vars.put("structureLine", structure.getAgrementNumber() == null ? structure.getCity()
                : (structure.getCity() == null ? "" : structure.getCity() + " · ") + "Agrément n° " + structure.getAgrementNumber());
        vars.put("title", "Récapitulatif des repas délivrés");
        vars.put("period", "%s · du %s au %s".formatted(summary.label(), Formats.date(summary.from()), Formats.date(summary.to())));
        vars.put("sections", summary.sections());
        vars.put("months", summary.months());
        vars.put("total", summary.total());
        vars.put("average", summary.averagePerDay());
        vars.put("closed", summary.closedAt() == null ? null : "Trimestre clos le %s par %s".formatted(
                Formats.date(summary.closedAt().atZone(zone).toLocalDate()), summary.closedBy()));
        vars.put("printedAt", "Édité le %s à %s".formatted(Formats.date(clock.instant().atZone(zone).toLocalDate()),
                Formats.time(clock.instant().atZone(zone).toLocalTime())));
        vars.put("rule", "Un repas est compté quand l’enfant est pointé présent à l’heure du repas (%s), un goûter à l’heure du goûter (%s). Les paniers repas fournis par les familles ne sont pas comptés : %d sur le trimestre."
                .formatted(Formats.time(structure.getLunchTime()), Formats.time(structure.getSnackTime()),
                        summary.total().packedLunches()));
        vars.put("lunchesOf", (java.util.function.BiFunction<MonthRow, java.util.UUID, Integer>)
                (row, id) -> row.lunchesBySection().getOrDefault(id, 0));
        var html = templates.process("repas-trimestre", new Context(Locale.FRANCE, vars));
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
        record(summary, "pdf");
        return new Export(out.toByteArray(), "repas-%d-T%d.pdf".formatted(year, quarter));
    }

    @Transactional
    public Export xlsx(int year, int quarter) {
        var data = meals.quarter(year, quarter);
        var summary = data.summary();
        var out = new ByteArrayOutputStream();
        try {
            var workbook = new Workbook(out, "Ti'Cagou", "1.0");
            var recap = workbook.newWorksheet("Récapitulatif");
            recap.value(0, 0, meals.structure().getName() + " · Récapitulatif des repas délivrés");
            recap.style(0, 0).bold().fontSize(13).set();
            recap.value(1, 0, "%s · du %s au %s".formatted(summary.label(), Formats.date(summary.from()), Formats.date(summary.to())));
            int header = 3;
            int col = 0;
            recap.value(header, col++, "Mois");
            recap.value(header, col++, "Jours d’ouverture");
            for (var section : summary.sections()) {
                recap.value(header, col++, section.name());
            }
            recap.value(header, col++, "Total repas");
            recap.value(header, col++, "Goûters");
            recap.value(header, col, "Paniers repas");
            recap.range(header, 0, header, col).style().bold().fillColor("DCEFEE").set();
            int row = header + 1;
            for (var month : summary.months()) {
                writeMonth(recap, row++, month, summary);
            }
            writeMonth(recap, row, summary.total(), summary);
            recap.range(row, 0, row, col).style().bold().set();
            recap.value(row + 2, 0, "Moyenne par jour : " + summary.averagePerDay() + " repas");
            recap.value(row + 3, 0, summary.state() == MealDtos.QuarterState.CLOS ? "Trimestre clos" : "Trimestre non clos");
            recap.width(0, 22);
            for (int c = 1; c <= col; c++) {
                recap.width(c, 14);
            }

            var detail = workbook.newWorksheet("Détail par enfant et par jour");
            String[] titles = {"Date", "Enfant", "Section", "Repas", "Goûter", "Panier repas", "Correction"};
            for (int c = 0; c < titles.length; c++) {
                detail.value(0, c, titles[c]);
            }
            detail.range(0, 0, 0, titles.length - 1).style().bold().fillColor("DCEFEE").set();
            int r = 1;
            for (var line : data.detail()) {
                detail.value(r, 0, line.day());
                detail.style(r, 0).format("dd/mm/yyyy").set();
                detail.value(r, 1, line.child());
                detail.value(r, 2, line.section());
                detail.value(r, 3, line.lunch() ? 1 : 0);
                detail.value(r, 4, line.snack() ? 1 : 0);
                detail.value(r, 5, line.packedLunch() ? 1 : 0);
                if (line.correction() != null) {
                    detail.value(r, 6, line.correction());
                }
                r++;
            }
            detail.width(0, 12);
            detail.width(1, 26);
            detail.width(2, 14);
            detail.width(6, 50);
            detail.freezePane(0, 1);
            workbook.finish();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        record(summary, "xlsx");
        return new Export(out.toByteArray(), "repas-%d-T%d.xlsx".formatted(year, quarter));
    }

    private static void writeMonth(Worksheet sheet, int row, MonthRow month, QuarterDto summary) {
        int col = 0;
        sheet.value(row, col++, month.label());
        sheet.value(row, col++, month.openDays());
        for (var section : summary.sections()) {
            sheet.value(row, col++, month.lunchesBySection().getOrDefault(section.id(), 0));
        }
        sheet.value(row, col++, month.lunches());
        sheet.value(row, col++, month.snacks());
        sheet.value(row, col, month.packedLunches());
    }

    private void record(QuarterDto summary, String format) {
        audit.record(AuditAction.MEAL_EXPORTED, "meal_quarter", null, Map.of("year", summary.year(),
                "quarter", summary.quarter(), "format", format));
    }
}
