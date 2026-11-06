package nc.ticagou.reporting;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Indicateurs d'activité d'une période, sans accès à la base.
 * <ul>
 *   <li>heures réalisées : pointages d'arrivée et de départ des enfants ;</li>
 *   <li>heures contractualisées : planning des contrats signés, jour par jour (les plannings variables, sans horaire
 *       fixe, n'y figurent pas) ;</li>
 *   <li>capacité théorique : places agréées (à la date du jour) × heures d'ouverture du jour ; par section, les places
 *       de la section ;</li>
 *   <li>taux d'occupation = réalisé / capacité théorique ; taux de réalisation = réalisé / contractualisé (en pour
 *       mille).</li>
 * </ul>
 */
public final class ActivityCalculator {

    private ActivityCalculator() {
    }

    public record Section(UUID id, String name, int capacity) {
    }

    /** Passage pointé ou journée prévue d'un enfant : minutes, section. */
    public record Presence(LocalDate day, UUID childId, UUID sectionId, int minutes) {
    }

    /**
     * @param openMinutes minutes d'ouverture d'un jour (0 : fermé)
     * @param capacity    places agréées de la structure ce jour-là
     */
    public record Input(LocalDate from, LocalDate to, List<Section> sections, Function<LocalDate, Integer> openMinutes,
                        Function<LocalDate, Integer> capacity, List<Presence> realized, List<Presence> planned) {
    }

    public record Indicators(int children, long realizedMinutes, long plannedMinutes, long capacityMinutes,
                             int occupancy, int realization) {
    }

    public record SectionIndicators(UUID sectionId, String name, int capacity, Indicators indicators) {
    }

    public record MonthIndicators(YearMonth month, int openDays, Indicators indicators) {
    }

    public record Result(int openDays, Indicators total, List<SectionIndicators> sections, List<MonthIndicators> months) {
    }

    public static Result compute(Input input) {
        var total = new Accumulator();
        var bySection = new LinkedHashMap<UUID, Accumulator>();
        input.sections().forEach(s -> bySection.put(s.id(), new Accumulator()));
        var byMonth = new LinkedHashMap<YearMonth, Accumulator>();
        var openDaysByMonth = new LinkedHashMap<YearMonth, Integer>();
        int openDays = 0;
        for (var day = input.from(); !day.isAfter(input.to()); day = day.plusDays(1)) {
            int open = Math.max(input.openMinutes().apply(day), 0);
            var month = YearMonth.from(day);
            byMonth.computeIfAbsent(month, m -> new Accumulator());
            openDaysByMonth.merge(month, open > 0 ? 1 : 0, Integer::sum);
            if (open == 0) {
                continue;
            }
            openDays++;
            long capacity = (long) input.capacity().apply(day) * open;
            total.capacity += capacity;
            byMonth.get(month).capacity += capacity;
            for (var section : input.sections()) {
                bySection.get(section.id()).capacity += (long) section.capacity() * open;
            }
        }
        for (var p : input.realized()) {
            var month = YearMonth.from(p.day());
            total.realize(p);
            byMonth.computeIfAbsent(month, m -> new Accumulator()).realize(p);
            var section = bySection.get(p.sectionId());
            if (section != null) {
                section.realize(p);
            }
        }
        for (var p : input.planned()) {
            if (input.openMinutes().apply(p.day()) <= 0) {
                continue;
            }
            total.planned += p.minutes();
            byMonth.computeIfAbsent(YearMonth.from(p.day()), m -> new Accumulator()).planned += p.minutes();
            var section = bySection.get(p.sectionId());
            if (section != null) {
                section.planned += p.minutes();
            }
        }
        var sections = input.sections().stream()
                .map(s -> new SectionIndicators(s.id(), s.name(), s.capacity(), bySection.get(s.id()).indicators())).toList();
        var months = new ArrayList<MonthIndicators>();
        byMonth.forEach((m, a) -> months.add(new MonthIndicators(m, openDaysByMonth.getOrDefault(m, 0), a.indicators())));
        return new Result(openDays, total.indicators(), sections, List.copyOf(months));
    }

    /** Rapport en pour mille, arrondi ; nul si le dénominateur est nul. */
    static int perMille(long value, long base) {
        return base <= 0 ? 0 : (int) Math.round(value * 1000.0 / base);
    }

    private static final class Accumulator {
        final Set<UUID> children = new HashSet<>();
        long realized;
        long planned;
        long capacity;

        void realize(Presence p) {
            children.add(p.childId());
            realized += p.minutes();
        }

        Indicators indicators() {
            return new Indicators(children.size(), realized, planned, capacity, perMille(realized, capacity),
                    perMille(realized, planned));
        }
    }

    /** Minutes d'ouverture par jour de la semaine (1 = lundi). */
    public static Function<LocalDate, Integer> weekly(Map<Integer, Integer> minutesByDay) {
        return day -> minutesByDay.getOrDefault(day.getDayOfWeek().getValue(), 0);
    }
}
