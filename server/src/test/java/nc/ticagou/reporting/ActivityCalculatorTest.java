package nc.ticagou.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import nc.ticagou.reporting.ActivityCalculator.Input;
import nc.ticagou.reporting.ActivityCalculator.Presence;
import nc.ticagou.reporting.ActivityCalculator.Section;

class ActivityCalculatorTest {

    static final UUID BEBES = UUID.randomUUID();
    static final UUID GRANDS = UUID.randomUUID();
    static final List<Section> SECTIONS = List.of(new Section(BEBES, "Bébés", 2), new Section(GRANDS, "Grands", 3));
    /** Ouvert du lundi au vendredi, 10 h par jour. */
    static final Map<Integer, Integer> WEEK = Map.of(1, 600, 2, 600, 3, 600, 4, 600, 5, 600);
    static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);

    @Test
    void occupationEtRealisationSurUneSemaine() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var realized = List.of(new Presence(MONDAY, a, BEBES, 480), new Presence(MONDAY.plusDays(1), a, BEBES, 540),
                new Presence(MONDAY, b, GRANDS, 600));
        var planned = List.of(new Presence(MONDAY, a, BEBES, 540), new Presence(MONDAY.plusDays(1), a, BEBES, 540),
                new Presence(MONDAY, b, GRANDS, 600), new Presence(MONDAY.plusDays(5), b, GRANDS, 600));
        var result = ActivityCalculator.compute(new Input(MONDAY, MONDAY.plusDays(6), SECTIONS, ActivityCalculator.weekly(WEEK),
                day -> 5, realized, planned));
        assertThat(result.openDays()).isEqualTo(5);
        var total = result.total();
        assertThat(total.children()).isEqualTo(2);
        assertThat(total.realizedMinutes()).isEqualTo(1_620);
        assertThat(total.plannedMinutes()).as("le samedi, fermé, ne compte pas").isEqualTo(1_680);
        assertThat(total.capacityMinutes()).isEqualTo(5 * 5 * 600);
        assertThat(total.occupancy()).isEqualTo(108);
        assertThat(total.realization()).isEqualTo(964);
        var bebes = result.sections().getFirst().indicators();
        assertThat(bebes.capacityMinutes()).isEqualTo(2 * 5 * 600);
        assertThat(bebes.occupancy()).isEqualTo(170);
        assertThat(result.months()).extracting(m -> m.month()).containsExactly(YearMonth.of(2026, 11));
    }

    @Test
    void capaciteDeLEpoqueEtPeriodeVide() {
        var day = MONDAY;
        var result = ActivityCalculator.compute(new Input(day, day.plusDays(1), SECTIONS, ActivityCalculator.weekly(WEEK),
                d -> d.equals(day) ? 10 : 20, List.of(), List.of()));
        assertThat(result.total().capacityMinutes()).isEqualTo(10 * 600 + 20 * 600);
        assertThat(result.total().occupancy()).isZero();
        assertThat(result.total().realization()).as("rien de prévu : pas de division par zéro").isZero();
    }

    @Test
    void anneeDecoupeeParMois() {
        var result = ActivityCalculator.compute(new Input(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), SECTIONS,
                ActivityCalculator.weekly(WEEK), d -> 5,
                List.of(new Presence(LocalDate.of(2026, 2, 3), UUID.randomUUID(), BEBES, 300)), List.of()));
        assertThat(result.months()).hasSize(3);
        assertThat(result.months().get(1).indicators().realizedMinutes()).isEqualTo(300);
        assertThat(result.months().get(0).openDays()).isEqualTo(22);
    }
}
