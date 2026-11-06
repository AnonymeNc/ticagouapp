package nc.ticagou.staff;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import nc.ticagou.staff.ForecastCalculator.ExpectedChild;
import nc.ticagou.staff.ForecastCalculator.PlannedAdult;
import nc.ticagou.staff.Shifts.Absence;
import nc.ticagou.staff.Shifts.AbsenceKind;
import nc.ticagou.staff.Shifts.DayTemplate;
import nc.ticagou.staff.Shifts.Override;
import nc.ticagou.staff.Shifts.Week;
import nc.ticagou.staff.TimesheetCalculator.Anomaly;
import nc.ticagou.staff.TimesheetCalculator.DayInput;
import nc.ticagou.staff.TimesheetCalculator.Input;
import nc.ticagou.staff.TimesheetCalculator.Punch;
import nc.ticagou.staffing.RatioCalculator.Rule;
import nc.ticagou.staffing.RatioCalculator.Status;

class StaffCalculatorsTest {

    static LocalTime t(String hhmm) {
        return LocalTime.parse(hhmm);
    }

    static final UUID BEBES = UUID.randomUUID();
    static final UUID GRANDS = UUID.randomUUID();
    static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);

    /** Semaine de Céline (maquette) : 39 h, pause de 30 min. */
    static Week celine(LocalDate from) {
        return new Week(from, List.of(
                new DayTemplate(1, t("06:30"), t("15:00"), 30, BEBES), new DayTemplate(2, t("06:30"), t("15:00"), 30, BEBES),
                new DayTemplate(3, t("10:00"), t("18:30"), 30, BEBES), new DayTemplate(4, t("06:30"), t("15:00"), 30, BEBES),
                new DayTemplate(5, t("10:30"), t("18:00"), 30, BEBES)));
    }

    @Nested
    class Planning {

        @Test
        void semaineTypeEnVigueurPuisException() {
            var weeks = List.of(celine(LocalDate.of(2026, 1, 1)));
            var monday = Shifts.resolve(MONDAY, weeks, List.of(), List.of());
            assertThat(monday.plannedMinutes()).isEqualTo(8 * 60);
            assertThat(weeks.getFirst().days().stream().mapToInt(d -> new Shifts.Shift(MONDAY, d.startsAt(), d.endsAt(),
                    d.breakMinutes(), null, false, null, null, false).plannedMinutes()).sum()).as("39 h au contrat").isEqualTo(39 * 60);

            var changed = Shifts.resolve(MONDAY, weeks, List.of(new Override(MONDAY, t("09:00"), t("17:00"), 60, GRANDS,
                    "Remplacement de Linh")), List.of());
            assertThat(changed.overridden()).isTrue();
            assertThat(changed.sectionId()).isEqualTo(GRANDS);
            assertThat(changed.plannedMinutes()).isEqualTo(7 * 60);

            var off = Shifts.resolve(MONDAY, weeks, List.of(new Override(MONDAY, null, null, 0, null, "Récupération")), List.of());
            assertThat(off.planned()).isFalse();
            assertThat(Shifts.resolve(MONDAY.plusDays(5), weeks, List.of(), List.of()).planned()).as("samedi").isFalse();
        }

        @Test
        void nouvelleSemaineTypeDatee() {
            var later = new Week(MONDAY.plusDays(1), List.of(new DayTemplate(2, t("08:00"), t("12:00"), 0, BEBES)));
            var weeks = List.of(celine(LocalDate.of(2026, 1, 1)), later);
            assertThat(Shifts.resolve(MONDAY, weeks, List.of(), List.of()).plannedMinutes()).isEqualTo(8 * 60);
            assertThat(Shifts.resolve(MONDAY.plusDays(1), weeks, List.of(), List.of()).plannedMinutes()).isEqualTo(4 * 60);
        }

        @Test
        void absenceEtDemiJournee() {
            var weeks = List.of(celine(LocalDate.of(2026, 1, 1)));
            var sick = Shifts.resolve(MONDAY, weeks, List.of(), List.of(new Absence(AbsenceKind.MALADIE, MONDAY, MONDAY.plusDays(1), false)));
            assertThat(sick.absence()).isEqualTo(AbsenceKind.MALADIE);
            assertThat(sick.absenceMinutes()).isEqualTo(8 * 60);
            assertThat(sick.working()).isFalse();

            var half = Shifts.resolve(MONDAY, weeks, List.of(), List.of(new Absence(AbsenceKind.CONGES, MONDAY, MONDAY, true)));
            assertThat(half.absenceMinutes()).isEqualTo(4 * 60);
            assertThat(half.working()).isTrue();
            assertThat(half.effectiveEnd()).isEqualTo(t("10:45"));
        }
    }

    @Nested
    class Prevision {

        final List<Rule> rules = List.of(
                new Rule("Bébés", 0, 18, 5, LocalDate.of(2024, 1, 1), null),
                new Rule("Marcheurs", 18, null, 8, LocalDate.of(2024, 1, 1), null));

        ExpectedChild baby(String arrives, String leaves) {
            return new ExpectedChild(UUID.randomUUID(), BEBES, MONDAY.minusMonths(10), t(arrives), t(leaves));
        }

        @Test
        void creneauEnSousEffectif() {
            var children = new ArrayList<ExpectedChild>();
            for (int i = 0; i < 6; i++) {
                children.add(baby("07:30", "16:30"));
            }
            var adults = List.of(new PlannedAdult(UUID.randomUUID(), BEBES, t("06:30"), t("15:00")),
                    new PlannedAdult(UUID.randomUUID(), BEBES, t("08:00"), t("17:30")));
            var slots = ForecastCalculator.compute(MONDAY, t("06:30"), t("17:30"), 30, rules, List.of(BEBES), children, adults);
            assertThat(slots).hasSize(22);
            var at = (java.util.function.Function<String, ForecastCalculator.Slot>) h ->
                    slots.stream().filter(s -> s.startsAt().equals(t(h))).findFirst().orElseThrow();
            assertThat(at.apply("06:30").sections().get(BEBES).status()).as("aucun enfant").isEqualTo(Status.CONFORME);
            assertThat(at.apply("07:30").sections().get(BEBES).status()).as("6 bébés pour 1 adulte").isEqualTo(Status.INSUFFISANT);
            assertThat(at.apply("07:30").sections().get(BEBES).required()).isEqualTo(2);
            assertThat(at.apply("09:00").sections().get(BEBES).status()).isEqualTo(Status.CONFORME);
            assertThat(at.apply("15:00").sections().get(BEBES).status()).as("Céline part à 15:00").isEqualTo(Status.INSUFFISANT);
        }

        @Test
        void unAdulteQuiPartEnCoursDeCreneauNeCouvrePasLeCreneau() {
            var slots = ForecastCalculator.compute(MONDAY, t("08:00"), t("09:00"), 30, rules, List.of(BEBES),
                    List.of(baby("08:00", "09:00")), List.of(new PlannedAdult(UUID.randomUUID(), BEBES, t("08:00"), t("08:45"))));
            assertThat(slots.get(0).sections().get(BEBES).adults()).isEqualTo(1);
            assertThat(slots.get(1).sections().get(BEBES).adults()).isZero();
            assertThat(slots.get(1).sections().get(BEBES).status()).isEqualTo(Status.INSUFFISANT);
        }

        @Test
        void sectionsEtGlobal() {
            var grand = new ExpectedChild(UUID.randomUUID(), GRANDS, MONDAY.minusMonths(30), t("08:00"), t("12:00"));
            var slots = ForecastCalculator.compute(MONDAY, t("08:00"), t("08:30"), 30, rules, List.of(BEBES, GRANDS),
                    List.of(baby("08:00", "12:00"), grand), List.of(new PlannedAdult(UUID.randomUUID(), null, t("07:00"), t("12:00"))));
            var slot = slots.getFirst();
            assertThat(slot.sections().get(GRANDS).status()).as("adulte sans section").isEqualTo(Status.INSUFFISANT);
            assertThat(slot.global().status()).isNotEqualTo(Status.INSUFFISANT);
            assertThat(slot.global().children()).isEqualTo(2);
        }
    }

    @Nested
    class FeuilleDHeures {

        final YearMonth november = YearMonth.of(2026, 11);
        final List<Week> weeks = List.of(celine(LocalDate.of(2026, 1, 1)));

        Input input(Integer weekly, java.util.function.BiFunction<LocalDate, Shifts.Shift, List<Punch>> punches,
                    List<Absence> absences, List<Override> overrides) {
            var days = new ArrayList<DayInput>();
            for (var d = TimesheetCalculator.firstDay(november); !d.isAfter(TimesheetCalculator.rangeEnd(november)); d = d.plusDays(1)) {
                var shift = Shifts.resolve(d, weeks, overrides, absences);
                days.add(new DayInput(shift, punches.apply(d, shift)));
            }
            return new Input(november, weekly, 30, LocalDate.of(2026, 12, 15), days);
        }

        /** Pointage conforme au planning. */
        static List<Punch> asPlanned(LocalDate day, Shifts.Shift shift) {
            return shift.working() ? List.of(new Punch(shift.startsAt(), shift.effectiveEnd())) : List.of();
        }

        @Test
        void semainesRattacheesAuMoisDeLeurDimanche() {
            assertThat(TimesheetCalculator.firstDay(november)).isEqualTo(LocalDate.of(2026, 10, 26));
            assertThat(TimesheetCalculator.lastDay(november)).isEqualTo(LocalDate.of(2026, 11, 29));
            assertThat(TimesheetCalculator.firstDay(YearMonth.of(2026, 3))).isEqualTo(LocalDate.of(2026, 2, 23));
        }

        @Test
        void moisConformeAuPlanning() {
            var result = TimesheetCalculator.compute(input(39 * 60, StaffCalculatorsTest.FeuilleDHeures::asPlanned, List.of(), List.of()));
            assertThat(result.days()).hasSize(30);
            assertThat(result.days().getFirst().day()).isEqualTo(LocalDate.of(2026, 11, 1));
            assertThat(result.workedMinutes()).as("21 jours ouvrés, pause déduite").isEqualTo(result.plannedMinutes());
            assertThat(result.weeks()).hasSize(5);
            assertThat(result.overtimeMinutes()).isZero();
            assertThat(result.anomalies()).isZero();
        }

        @Test
        void heuresAuDelaDuContratParSemaine() {
            // Le mardi 03/11, départ à 17:00 au lieu de 15:00 : 2 h de plus dans la semaine.
            var result = TimesheetCalculator.compute(input(39 * 60, (d, s) -> d.equals(LocalDate.of(2026, 11, 3))
                    ? List.of(new Punch(t("06:30"), t("17:00"))) : asPlanned(d, s), List.of(), List.of()));
            var week = result.weeks().stream().filter(w -> w.monday().equals(MONDAY)).findFirst().orElseThrow();
            assertThat(week.workedMinutes()).isEqualTo(41 * 60);
            assertThat(week.overtimeMinutes()).isEqualTo(2 * 60);
            assertThat(result.overtimeMinutes()).isEqualTo(2 * 60);
        }

        @Test
        void tempsPartielEtRemunerationALHeure() {
            var partial = TimesheetCalculator.compute(input(30 * 60, StaffCalculatorsTest.FeuilleDHeures::asPlanned, List.of(), List.of()));
            assertThat(partial.weeks().get(1).overtimeMinutes()).as("39 h pour 30 h au contrat").isEqualTo(9 * 60);
            var hourly = TimesheetCalculator.compute(input(null, StaffCalculatorsTest.FeuilleDHeures::asPlanned, List.of(), List.of()));
            assertThat(hourly.overtimeMinutes()).isZero();
        }

        @Test
        void absencesEtFormation() {
            var absences = List.of(new Absence(AbsenceKind.MALADIE, MONDAY, MONDAY.plusDays(1), false),
                    new Absence(AbsenceKind.FORMATION, LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 9), false),
                    new Absence(AbsenceKind.CONGES, LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 13), true));
            var result = TimesheetCalculator.compute(input(39 * 60, StaffCalculatorsTest.FeuilleDHeures::asPlanned, absences, List.of()));
            assertThat(result.absenceMinutes()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    AbsenceKind.MALADIE, 16 * 60, AbsenceKind.FORMATION, 8 * 60, AbsenceKind.CONGES, 7 * 60 / 2));
            var friday = result.days().stream().filter(l -> l.day().equals(LocalDate.of(2026, 11, 13))).findFirst().orElseThrow();
            assertThat(friday.workedMinutes()).as("demi-journée travaillée, pause déduite").isEqualTo(3 * 60 + 45 - 30);
            var trainingWeek = result.weeks().stream().filter(w -> w.monday().equals(LocalDate.of(2026, 11, 9))).findFirst().orElseThrow();
            assertThat(trainingWeek.trainingMinutes()).isEqualTo(8 * 60);
            assertThat(result.anomalies()).isZero();
        }

        @Test
        void anomalies() {
            var result = TimesheetCalculator.compute(input(39 * 60, (d, s) -> {
                if (d.equals(MONDAY)) return List.of(new Punch(t("06:30"), null));
                if (d.equals(MONDAY.plusDays(1))) return List.of();
                if (d.equals(MONDAY.plusDays(5))) return List.of(new Punch(t("08:00"), t("10:00")));
                return asPlanned(d, s);
            }, List.of(), List.of()));
            var byDay = (java.util.function.Function<LocalDate, List<Anomaly>>) day ->
                    result.days().stream().filter(l -> l.day().equals(day)).findFirst().orElseThrow().anomalies();
            assertThat(byDay.apply(MONDAY)).containsExactly(Anomaly.DEPART_MANQUANT);
            assertThat(byDay.apply(MONDAY.plusDays(1))).containsExactly(Anomaly.ABSENCE_NON_JUSTIFIEE);
            assertThat(byDay.apply(MONDAY.plusDays(5))).containsExactly(Anomaly.SANS_PLANNING);
            assertThat(result.anomalies()).isEqualTo(3);
        }

        @Test
        void pauseParDefautSansPlanningEtCoupure() {
            var noPlan = new Shifts.Shift(MONDAY.plusDays(5), null, null, 0, null, false, null, null, false);
            assertThat(TimesheetCalculator.day(new DayInput(noPlan, List.of(new Punch(t("08:00"), t("15:00")))), 30, MONDAY).workedMinutes())
                    .isEqualTo(6 * 60 + 30);
            var planned = Shifts.resolve(MONDAY, weeks, List.of(), List.of());
            assertThat(TimesheetCalculator.day(new DayInput(planned, List.of(new Punch(t("06:30"), t("10:30")),
                    new Punch(t("11:30"), t("15:00")))), 30, MONDAY).workedMinutes()).as("la coupure tient lieu de pause").isEqualTo(7 * 60 + 30);
        }
    }

    @Test
    void etatDesQualifications() {
        var today = LocalDate.of(2026, 10, 31);
        assertThat(QualificationState.of(null, today, 30)).isEqualTo(QualificationState.SANS_LIMITE);
        assertThat(QualificationState.of(LocalDate.of(2026, 10, 2), today, 30)).isEqualTo(QualificationState.EXPIREE);
        assertThat(QualificationState.of(LocalDate.of(2026, 11, 14), today, 30)).isEqualTo(QualificationState.A_RENOUVELER);
        assertThat(QualificationState.of(LocalDate.of(2026, 11, 30), today, 30)).as("pile 30 jours").isEqualTo(QualificationState.A_RENOUVELER);
        assertThat(QualificationState.of(LocalDate.of(2026, 12, 1), today, 30)).isEqualTo(QualificationState.VALIDE);
        assertThat(QualificationState.of(LocalDate.of(2026, 12, 1), today, 60)).isEqualTo(QualificationState.A_RENOUVELER);
    }
}
