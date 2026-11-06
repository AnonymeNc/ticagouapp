package nc.ticagou.meal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import nc.ticagou.meal.MealCounter.Basis;
import nc.ticagou.meal.MealCounter.ChildDay;
import nc.ticagou.meal.MealCounter.Passage;
import nc.ticagou.meal.MealCounter.SectionTotal;
import nc.ticagou.meal.MealCounter.Texture;

class MealCounterTest {

    private static final LocalTime LUNCH = LocalTime.of(11, 30);
    private static final LocalTime SNACK = LocalTime.of(15, 30);
    private static final UUID BEBES = UUID.randomUUID();
    private static final UUID MOYENS = UUID.randomUUID();

    private static LocalTime t(String hhmm) {
        return LocalTime.parse(hhmm);
    }

    private static ChildDay day(UUID section, List<Passage> passages, String plannedArrival, String plannedLeave) {
        return new ChildDay(UUID.randomUUID(), section, passages, plannedArrival == null ? null : t(plannedArrival),
                plannedLeave == null ? null : t(plannedLeave), false, Texture.NORMALE, null, null);
    }

    private static MealCounter.ChildCount single(ChildDay day, LocalTime now) {
        return MealCounter.count(LUNCH, SNACK, now, List.of(day)).children().getFirst();
    }

    @Test
    void presentALHeurePileEstServi() {
        var arrivedAtLunch = day(MOYENS, List.of(new Passage(t("11:30"), t("16:30"))), null, null);
        var leftAtLunch = day(MOYENS, List.of(new Passage(t("07:30"), t("11:30"))), null, null);
        var leftBefore = day(MOYENS, List.of(new Passage(t("07:30"), t("11:29"))), null, null);
        assertThat(single(arrivedAtLunch, null).lunch()).isTrue();
        assertThat(single(leftAtLunch, null).lunch()).isTrue();
        assertThat(single(leftAtLunch, null).snack()).isFalse();
        assertThat(single(leftBefore, null).lunch()).isFalse();
    }

    @Test
    void departAvantLeGouterRetireLeGouter() {
        var halfDay = day(MOYENS, List.of(new Passage(t("07:30"), t("13:00"))), "07:30", "13:00");
        var count = single(halfDay, null);
        assertThat(count.lunch()).isTrue();
        assertThat(count.snack()).isFalse();
        var total = MealCounter.count(LUNCH, SNACK, null, List.of(halfDay)).total();
        assertThat(total.leavingBeforeSnack()).isEqualTo(1);
    }

    @Test
    void previsionDuMatinDApresLeContrat() {
        var now = t("09:30");
        var present = day(MOYENS, List.of(new Passage(t("07:40"), null)), "07:30", "16:30");
        var expected = day(MOYENS, List.of(), "10:00", "16:30");
        var afterLunch = day(MOYENS, List.of(), "13:30", "17:00");
        var presentLeavingEarly = day(MOYENS, List.of(new Passage(t("07:40"), null)), "07:30", "11:00");
        var cameAndLeft = day(MOYENS, List.of(new Passage(t("07:40"), t("08:30"))), "07:30", "16:30");

        assertThat(single(present, now)).extracting("lunch", "snack", "lunchBasis").containsExactly(true, true, Basis.PREVISION);
        assertThat(single(expected, now)).extracting("lunch", "snack").containsExactly(true, true);
        assertThat(single(afterLunch, now)).extracting("lunch", "snack").containsExactly(false, true);
        assertThat(single(presentLeavingEarly, now)).extracting("lunch", "snack").containsExactly(false, false);
        assertThat(single(cameAndLeft, now)).extracting("lunch", "snack").containsExactly(false, false);
    }

    @Test
    void apresLHeureDuRepasSeulLePointageCompte() {
        var now = t("12:00");
        // Attendu à 10:00 mais jamais arrivé : pas de repas ; goûter encore prévu.
        var absent = day(MOYENS, List.of(), "10:00", "16:30");
        var count = single(absent, now);
        assertThat(count.lunch()).isFalse();
        assertThat(count.lunchBasis()).isEqualTo(Basis.POINTAGE);
        assertThat(count.snack()).isTrue();
        assertThat(count.snackBasis()).isEqualTo(Basis.PREVISION);
    }

    @Test
    void enfantQuiRevientLApresMidi() {
        var twice = day(MOYENS, List.of(new Passage(t("07:30"), t("10:00")), new Passage(t("14:00"), t("17:00"))),
                null, null);
        var count = single(twice, null);
        assertThat(count.lunch()).isFalse();
        assertThat(count.snack()).isTrue();
    }

    @Test
    void accueilHorsPlanningCompteCommeUnPointage() {
        var unplanned = day(MOYENS, List.of(new Passage(t("08:00"), null)), null, null);
        assertThat(single(unplanned, t("09:30"))).extracting("lunch", "snack").containsExactly(true, true);
        assertThat(single(unplanned, null)).extracting("lunch", "snack").containsExactly(true, true);
    }

    @Test
    void panierRepasCompteAPart() {
        var packed = new ChildDay(UUID.randomUUID(), MOYENS, List.of(new Passage(t("07:30"), t("16:30"))), null, null,
                true, Texture.NORMALE, null, null);
        var other = day(MOYENS, List.of(new Passage(t("07:30"), t("16:30"))), null, null);
        var result = MealCounter.count(LUNCH, SNACK, null, List.of(packed, other));
        assertThat(result.sections().get(MOYENS)).isEqualTo(new SectionTotal(1, 1, 1, 2, 0, 0, 0));
    }

    @Test
    void correctionManuelleLEmporte() {
        var present = new ChildDay(UUID.randomUUID(), MOYENS, List.of(new Passage(t("07:30"), t("16:30"))), null,
                null, false, Texture.NORMALE, false, null);
        var absent = new ChildDay(UUID.randomUUID(), MOYENS, List.of(), null, null, false, Texture.NORMALE, null, true);
        assertThat(single(present, null)).extracting("lunch", "lunchBasis", "snack").containsExactly(false, Basis.CORRECTION, true);
        assertThat(single(absent, null)).extracting("lunch", "snack", "snackBasis").containsExactly(false, true, Basis.CORRECTION);
    }

    @Test
    void texturesDesBebesEtTotaux() {
        var all = List.of(
                new ChildDay(UUID.randomUUID(), BEBES, List.of(new Passage(t("07:30"), null)), null, null, false, Texture.MIXEE, null, null),
                new ChildDay(UUID.randomUUID(), BEBES, List.of(new Passage(t("07:30"), null)), null, null, false, Texture.MIXEE, null, null),
                new ChildDay(UUID.randomUUID(), BEBES, List.of(new Passage(t("07:30"), null)), null, null, false, Texture.HACHEE, null, null),
                new ChildDay(UUID.randomUUID(), MOYENS, List.of(new Passage(t("07:30"), null)), null, null, false, null, null, null));
        var result = MealCounter.count(LUNCH, SNACK, t("09:30"), all);
        assertThat(result.sections().get(BEBES)).isEqualTo(new SectionTotal(3, 3, 0, 3, 1, 2, 0));
        assertThat(result.total().lunches()).isEqualTo(4);
    }

    @Test
    void sansPassageNiPlanningRienNEstCompte() {
        var nothing = day(MOYENS, List.of(), null, null);
        assertThat(single(nothing, t("09:30"))).extracting("lunch", "snack", "present").containsExactly(false, false, false);
    }
}
