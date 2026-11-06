package nc.ticagou.meal;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.common.ApiException;
import nc.ticagou.common.Formats;

/** Trimestres clos : plus aucun pointage ni correction de repas des enfants pour ces jours. */
@Component
public class ClosedPeriods {

    private final QuarterClosureRepository closures;

    public ClosedPeriods(QuarterClosureRepository closures) {
        this.closures = closures;
    }

    public static int quarterOf(LocalDate day) {
        return (day.getMonthValue() - 1) / 3 + 1;
    }

    /** « 1er trimestre 2026 », « 3e trimestre 2026 » */
    public static String label(int year, int quarter) {
        return "%s trimestre %d".formatted(quarter == 1 ? "1er" : quarter + "e", year);
    }

    @Transactional(readOnly = true)
    public boolean isClosed(UUID structureId, LocalDate day) {
        return closures.findByStructureIdAndYearAndQuarterAndReopenedAtIsNull(structureId, day.getYear(), quarterOf(day))
                .isPresent();
    }

    /** {@code quarter_closed} (422) si le jour appartient à un trimestre clos. */
    public void requireOpen(UUID structureId, LocalDate day) {
        if (isClosed(structureId, day)) {
            throw ApiException.rule("quarter_closed",
                    "Le %s est clos : le %s ne peut plus être modifié. La direction peut le rouvrir, avec un motif."
                            .formatted(label(day.getYear(), quarterOf(day)), Formats.date(day)));
        }
    }
}
