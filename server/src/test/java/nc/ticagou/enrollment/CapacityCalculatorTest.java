package nc.ticagou.enrollment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import nc.ticagou.enrollment.CapacityCalculator.Grid;
import nc.ticagou.enrollment.CapacityCalculator.Occupant;
import nc.ticagou.enrollment.CapacityCalculator.Period;
import nc.ticagou.enrollment.CapacityCalculator.SectionSlot;
import nc.ticagou.enrollment.CapacityCalculator.Verdict;

class CapacityCalculatorTest {

    static final UUID BEBES = UUID.randomUUID();
    static final UUID MOYENS = UUID.randomUUID();
    static final UUID GRANDS = UUID.randomUUID();
    static final List<SectionSlot> SECTIONS = List.of(
            new SectionSlot(BEBES, 2, 18, 10), new SectionSlot(MOYENS, 18, 30, 14), new SectionSlot(GRANDS, 30, null, 16));
    static final List<Integer> WEEKDAYS = List.of(1, 2, 3, 4, 5);
    static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

    /** 27 mois en novembre 2026, 30 mois (Grands) au 01/02/2027. */
    static final LocalDate BORN_AUG_2024 = LocalDate.of(2024, 8, 1);

    static Period period(String from, String to, Integer... days) {
        return new Period(LocalDate.parse(from), to == null ? null : LocalDate.parse(to), Set.of(days));
    }

    static Occupant occupant(LocalDate birthDate, UUID section, Period... periods) {
        return new Occupant(birthDate, section, List.of(periods));
    }

    static Grid grid(Occupant... occupants) {
        return CapacityCalculator.compute(NOVEMBER, SECTIONS, WEEKDAYS, List.of(occupants));
    }

    /** Places libres d'une section un jour donné, mois par mois. */
    static int[] column(Grid grid, UUID section, int day) {
        var free = grid.free().get(section);
        var column = new int[free.length];
        for (int m = 0; m < free.length; m++) {
            column[m] = free[m][grid.openDays().indexOf(day)];
        }
        return column;
    }

    @Test
    void sansContratToutesLesPlacesSontLibres() {
        var grid = grid();

        assertThat(grid.openDays()).containsExactly(1, 2, 3, 4, 5);
        assertThat(column(grid, BEBES, 1)).containsOnly(10);
        assertThat(column(grid, GRANDS, 5)).containsOnly(16);
    }

    @Test
    void passageDeSectionSelonLAge() {
        var grid = grid(occupant(BORN_AUG_2024, MOYENS, period("2025-09-01", "2027-08-31", 1, 2)));

        assertThat(column(grid, MOYENS, 1)).containsExactly(13, 13, 13, 14, 14, 14);
        assertThat(column(grid, GRANDS, 1)).containsExactly(16, 16, 16, 15, 15, 15);
        assertThat(column(grid, MOYENS, 3)).as("jour non prévu au planning").containsOnly(14);
    }

    @Test
    void sortieEnCoursDeMoisOccupeLeMoisEntier() {
        var grid = grid(occupant(LocalDate.of(2025, 9, 1), BEBES, period("2026-01-05", "2027-01-15", 4)));

        assertThat(column(grid, BEBES, 4)).containsExactly(9, 9, 9, 10, 10, 10);
    }

    @Test
    void entreeEnCoursDeMoisOccupeLeMoisEntier() {
        var grid = grid(occupant(LocalDate.of(2026, 3, 1), BEBES, period("2026-12-10", null, 2)));

        assertThat(column(grid, BEBES, 2)).containsExactly(10, 9, 9, 9, 9, 9);
    }

    @Test
    void avenantQuiChangeLesJours() {
        var grid = grid(occupant(LocalDate.of(2026, 3, 1), BEBES,
                period("2026-09-01", "2026-12-31", 1, 2), period("2027-01-01", "2027-12-31", 1, 3)));

        assertThat(column(grid, BEBES, 1)).containsOnly(9);
        assertThat(column(grid, BEBES, 2)).containsExactly(9, 9, 10, 10, 10, 10);
        assertThat(column(grid, BEBES, 3)).containsExactly(10, 10, 9, 9, 9, 9);
    }

    @Test
    void jourFermeIgnore() {
        var grid = grid(occupant(LocalDate.of(2025, 9, 1), BEBES, period("2026-01-01", null, 6)));

        assertThat(grid.openDays()).doesNotContain(6);
        assertThat(grid.free().get(BEBES)[0]).containsOnly(10);
    }

    @Test
    void enfantSansTrancheCorrespondanteGardeSaSection() {
        var tooYoung = LocalDate.of(2026, 10, 1);

        var grid = grid(occupant(tooYoung, BEBES, period("2026-11-01", null, 1)));

        assertThat(column(grid, BEBES, 1)).as("moins de 2 mois en novembre et décembre").containsOnly(9);
    }

    @Test
    void depassementSignaleParUneValeurNegative() {
        var sections = List.of(new SectionSlot(MOYENS, 18, 30, 1));
        var child = LocalDate.of(2025, 2, 1);
        var grid = CapacityCalculator.compute(NOVEMBER, sections, WEEKDAYS, List.of(
                occupant(child, MOYENS, period("2026-09-01", null, 1)),
                occupant(child, MOYENS, period("2026-09-01", null, 1))));

        assertThat(grid.free().get(MOYENS)[0][0]).isEqualTo(-1);
    }

    // --- Tester une demande ---------------------------------------------------------------------------

    static final List<SectionSlot> ONE_PLACE = List.of(new SectionSlot(MOYENS, 18, 30, 1));
    static final LocalDate BORN_FEB_2025 = LocalDate.of(2025, 2, 1);

    static Grid onePlace(Occupant... occupants) {
        return CapacityCalculator.compute(NOVEMBER, ONE_PLACE, WEEKDAYS, List.of(occupants));
    }

    @Test
    void demandeAcceptableDesLeMoisSouhaite() {
        var result = CapacityCalculator.test(onePlace(), ONE_PLACE, BORN_FEB_2025, MOYENS, List.of(1, 2),
                LocalDate.of(2026, 11, 16));

        assertThat(result.verdict()).isEqualTo(Verdict.ACCEPTABLE);
        assertThat(result.firstMonth()).isEqualTo(NOVEMBER);
        assertThat(result.fullDays()).isEmpty();
    }

    @Test
    void demandePossiblePlusTard() {
        var leavesInJanuary = occupant(BORN_FEB_2025, MOYENS, period("2026-01-01", "2027-01-31", 2));

        var result = CapacityCalculator.test(onePlace(leavesInJanuary), ONE_PLACE, BORN_FEB_2025, MOYENS,
                List.of(1, 2, 4), LocalDate.of(2027, 1, 4));

        assertThat(result.verdict()).isEqualTo(Verdict.LATER);
        assertThat(result.firstMonth()).isEqualTo(YearMonth.of(2027, 2));
        assertThat(result.fullDays()).containsExactly(2);
    }

    @Test
    void unMoisLibreIsoleNeSuffitPas() {
        var away = occupant(BORN_FEB_2025, MOYENS,
                period("2026-01-01", "2026-11-30", 1), period("2027-01-01", "2027-12-31", 1));

        var result = CapacityCalculator.test(onePlace(away), ONE_PLACE, BORN_FEB_2025, MOYENS, List.of(1),
                LocalDate.of(2026, 12, 1));

        assertThat(result.verdict()).isEqualTo(Verdict.NONE);
        assertThat(result.firstMonth()).isNull();
    }

    @Test
    void entreeAuDelaDeLaPeriode() {
        var result = CapacityCalculator.test(onePlace(), ONE_PLACE, BORN_FEB_2025, MOYENS, List.of(1),
                LocalDate.of(2027, 6, 1));

        assertThat(result.verdict()).isEqualTo(Verdict.OUT_OF_RANGE);
    }

    @Test
    void laDemandeSuitLaSectionDeSonAge() {
        // Né en août 2024 : Moyens jusqu'en janvier, Grands ensuite. Les Grands sont complets le lundi.
        var grandsFull = occupant(LocalDate.of(2023, 1, 1), GRANDS, period("2025-01-01", null, 1));
        var sections = List.of(new SectionSlot(MOYENS, 18, 30, 5), new SectionSlot(GRANDS, 30, null, 1));
        var grid = CapacityCalculator.compute(NOVEMBER, sections, WEEKDAYS, List.of(grandsFull));

        var result = CapacityCalculator.test(grid, sections, BORN_AUG_2024, MOYENS, List.of(1), LocalDate.of(2026, 11, 2));

        assertThat(result.verdict()).isEqualTo(Verdict.NONE);
        assertThat(result.fullDays()).as("le lundi est libre en novembre, chez les Moyens").isEmpty();
    }
}
