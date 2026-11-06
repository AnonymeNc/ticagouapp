package nc.ticagou.staff;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import nc.ticagou.staff.Shifts.AbsenceKind;
import nc.ticagou.staff.Shifts.Shift;

/**
 * Feuille d'heures mensuelle d'une personne, sans dépendance à Spring.
 * <ul>
 *   <li>Jour : prévu (pause déduite), pointé (pause du poste déduite, ou pause par défaut au-delà de six heures sans
 *       planning), absence et sa durée, anomalies.</li>
 *   <li>Semaine (ISO, rattachée au mois de son dimanche) : heures au-delà du contrat = travaillé + formation − durée
 *       hebdomadaire du contrat, si positif. Sans durée au contrat (rémunération à l'heure) : aucune.</li>
 * </ul>
 */
public final class TimesheetCalculator {

    private TimesheetCalculator() {
    }

    public enum Anomaly { DEPART_MANQUANT, SANS_PLANNING, ABSENCE_NON_JUSTIFIEE }

    public record Punch(LocalTime arrivedAt, LocalTime departedAt) {
    }

    public record DayInput(Shift shift, List<Punch> punches) {
    }

    /**
     * @param days chaque jour de {@link #firstDay} à {@link #rangeEnd}
     */
    public record Input(YearMonth month, Integer weeklyMinutes, int defaultBreakMinutes, LocalDate today,
                        List<DayInput> days) {
    }

    public record DayLine(LocalDate day, int plannedMinutes, int workedMinutes, AbsenceKind absence, int absenceMinutes,
                          List<Punch> punches, List<Anomaly> anomalies) {
    }

    public record WeekLine(LocalDate monday, int workedMinutes, int trainingMinutes, int contractMinutes,
                           int overtimeMinutes) {
    }

    public record Result(YearMonth month, List<DayLine> days, List<WeekLine> weeks, int plannedMinutes,
                         int workedMinutes, int overtimeMinutes, Map<AbsenceKind, Integer> absenceMinutes,
                         int anomalies) {
    }

    /** Lundi de la première semaine rattachée au mois (semaines ISO dont le dimanche tombe dans le mois). */
    public static LocalDate firstDay(YearMonth month) {
        return month.atDay(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).minusDays(6);
    }

    /** Dernière date à fournir : fin du mois (les jours après le dernier dimanche comptent dans la semaine suivante). */
    public static LocalDate rangeEnd(YearMonth month) {
        return month.atEndOfMonth();
    }

    /** Dimanche de la dernière semaine rattachée au mois. */
    public static LocalDate lastDay(YearMonth month) {
        return month.atEndOfMonth().with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
    }

    public static Result compute(Input input) {
        var lines = new ArrayList<DayLine>();
        for (var d : input.days()) {
            lines.add(day(d, input.defaultBreakMinutes(), input.today()));
        }
        var weeks = new ArrayList<WeekLine>();
        for (var monday = firstDay(input.month()); !monday.isAfter(lastDay(input.month())); monday = monday.plusWeeks(1)) {
            var start = monday;
            var week = lines.stream().filter(l -> !l.day().isBefore(start) && l.day().isBefore(start.plusWeeks(1))).toList();
            int worked = week.stream().mapToInt(DayLine::workedMinutes).sum();
            int training = week.stream().filter(l -> l.absence() == AbsenceKind.FORMATION).mapToInt(DayLine::absenceMinutes).sum();
            int contract = input.weeklyMinutes() == null ? 0 : input.weeklyMinutes();
            int overtime = input.weeklyMinutes() == null ? 0 : Math.max(worked + training - contract, 0);
            weeks.add(new WeekLine(monday, worked, training, contract, overtime));
        }
        var inMonth = lines.stream().filter(l -> YearMonth.from(l.day()).equals(input.month())).toList();
        var absences = new EnumMap<AbsenceKind, Integer>(AbsenceKind.class);
        inMonth.stream().filter(l -> l.absence() != null)
                .forEach(l -> absences.merge(l.absence(), l.absenceMinutes(), Integer::sum));
        return new Result(input.month(), inMonth, weeks,
                inMonth.stream().mapToInt(DayLine::plannedMinutes).sum(),
                inMonth.stream().mapToInt(DayLine::workedMinutes).sum(),
                weeks.stream().mapToInt(WeekLine::overtimeMinutes).sum(), absences,
                inMonth.stream().mapToInt(l -> l.anomalies().size()).sum());
    }

    static DayLine day(DayInput input, int defaultBreak, LocalDate today) {
        var shift = input.shift();
        var anomalies = new ArrayList<Anomaly>();
        int span = 0;
        for (var p : input.punches()) {
            if (p.departedAt() == null) {
                anomalies.add(Anomaly.DEPART_MANQUANT);
                continue;
            }
            span += (int) Duration.between(p.arrivedAt(), p.departedAt()).toMinutes();
        }
        int breakMinutes = shift.planned() ? shift.breakMinutes() : span > 6 * 60 ? defaultBreak : 0;
        // Plusieurs passages dans la journée : la coupure tient lieu de pause.
        if (input.punches().size() > 1) {
            breakMinutes = 0;
        }
        int worked = span == 0 ? 0 : Math.max(span - breakMinutes, 0);
        if (!input.punches().isEmpty() && !shift.planned()) {
            anomalies.add(Anomaly.SANS_PLANNING);
        }
        if (shift.planned() && shift.absence() == null && input.punches().isEmpty() && shift.day().isBefore(today)) {
            anomalies.add(Anomaly.ABSENCE_NON_JUSTIFIEE);
        }
        return new DayLine(shift.day(), shift.plannedMinutes(), worked, shift.absence(), shift.absenceMinutes(),
                input.punches(), List.copyOf(anomalies));
    }
}
