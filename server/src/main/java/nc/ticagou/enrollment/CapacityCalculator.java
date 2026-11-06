package nc.ticagou.enrollment;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Places libres par section, par jour de la semaine et par mois, sur une période glissante.
 * <p>
 * Un enfant occupe une place un jour donné d'un mois s'il est accueilli ce jour-là à un moment du mois
 * (calcul prudent : une entrée ou une sortie en cours de mois occupe le mois entier). Sa section est
 * déduite de son âge au premier jour du mois, ce qui compte les passages de section ; si aucune tranche
 * d'âge ne correspond, on garde sa section actuelle.
 */
public final class CapacityCalculator {

    public static final int HORIZON_MONTHS = 6;

    private CapacityCalculator() {
    }

    /** Tranche [minAgeMonths, maxAgeMonths[ ; maxAgeMonths nul = sans limite. */
    public record SectionSlot(UUID id, int minAgeMonths, Integer maxAgeMonths, int capacity) {

        boolean covers(long ageMonths) {
            return ageMonths >= minAgeMonths && (maxAgeMonths == null || ageMonths < maxAgeMonths);
        }
    }

    /** Accueil sur une période (fin incluse, nulle = sans fin), certains jours ISO de la semaine. */
    public record Period(LocalDate from, LocalDate to, Set<Integer> days) {

        boolean overlaps(YearMonth month) {
            return !from.isAfter(month.atEndOfMonth()) && (to == null || !to.isBefore(month.atDay(1)));
        }
    }

    /** Enfant sous contrat signé, ou demande à qui une place a été proposée (réservation). */
    public record Occupant(LocalDate birthDate, UUID currentSectionId, List<Period> periods) {
    }

    /**
     * Grille des places libres : pour chaque section, {@code free[mois][jour]} avec les jours dans l'ordre de
     * {@link #openDays()}. Une valeur négative signale un dépassement.
     */
    public record Grid(YearMonth firstMonth, List<Integer> openDays, Map<UUID, int[][]> free) {

        public int months() {
            return HORIZON_MONTHS;
        }

        public YearMonth month(int index) {
            return firstMonth.plusMonths(index);
        }

        int free(UUID sectionId, int month, int dayOfWeek) {
            int index = openDays.indexOf(dayOfWeek);
            var grid = free.get(sectionId);
            return index < 0 || grid == null ? 0 : grid[month][index];
        }
    }

    public static Grid compute(YearMonth firstMonth, List<SectionSlot> sections, Collection<Integer> openDays,
                               Collection<Occupant> occupants) {
        SortedSet<Integer> days = new TreeSet<>(openDays);
        var dayList = List.copyOf(days);
        Map<UUID, int[][]> free = new LinkedHashMap<>();
        for (var section : sections) {
            var grid = new int[HORIZON_MONTHS][dayList.size()];
            for (var row : grid) {
                Arrays.fill(row, section.capacity());
            }
            free.put(section.id(), grid);
        }
        for (var occupant : occupants) {
            for (int m = 0; m < HORIZON_MONTHS; m++) {
                var month = firstMonth.plusMonths(m);
                var grid = free.get(sectionAt(sections, occupant.birthDate(), occupant.currentSectionId(), month));
                if (grid == null) {
                    continue;
                }
                for (int d = 0; d < dayList.size(); d++) {
                    int day = dayList.get(d);
                    boolean present = occupant.periods().stream().anyMatch(p -> p.overlaps(month) && p.days().contains(day));
                    if (present) {
                        grid[m][d]--;
                    }
                }
            }
        }
        return new Grid(firstMonth, dayList, free);
    }

    /** Section d'un enfant au premier jour du mois, selon son âge ; à défaut, sa section actuelle. */
    public static UUID sectionAt(List<SectionSlot> sections, LocalDate birthDate, UUID currentSectionId, YearMonth month) {
        if (birthDate != null) {
            long age = ChronoUnit.MONTHS.between(birthDate, month.atDay(1));
            for (var section : sections) {
                if (age >= 0 && section.covers(age)) {
                    return section.id();
                }
            }
        }
        return currentSectionId;
    }

    public enum Verdict {
        /** Une place chaque jour demandé dès le mois souhaité et jusqu'à la fin de la période. */
        ACCEPTABLE,
        /** Possible plus tard dans la période ({@link Test#firstMonth()}). */
        LATER,
        /** Aucun mois de la période ne convient. */
        NONE,
        /** Entrée souhaitée au-delà de la période calculée. */
        OUT_OF_RANGE
    }

    /**
     * @param fullDays jours demandés complets au mois souhaité (pour expliquer un refus ou un report)
     */
    public record Test(Verdict verdict, YearMonth firstMonth, List<Integer> fullDays) {
    }

    /**
     * Teste une demande : premier mois, à partir de l'entrée souhaitée, où chaque jour demandé a une place
     * libre dans la section de l'enfant ce mois-là, et le reste jusqu'à la fin de la période.
     */
    public static Test test(Grid grid, List<SectionSlot> sections, LocalDate birthDate, UUID sectionId,
                            Collection<Integer> days, LocalDate desiredStart) {
        var desiredMonth = YearMonth.from(desiredStart);
        int start = (int) Math.max(0, ChronoUnit.MONTHS.between(grid.firstMonth(), desiredMonth));
        if (start >= HORIZON_MONTHS) {
            return new Test(Verdict.OUT_OF_RANGE, null, List.of());
        }
        boolean[] ok = new boolean[HORIZON_MONTHS];
        for (int m = start; m < HORIZON_MONTHS; m++) {
            var section = sectionAt(sections, birthDate, sectionId, grid.month(m));
            int month = m;
            ok[m] = section != null && days.stream().allMatch(d -> grid.free(section, month, d) > 0);
        }
        var desiredSection = sectionAt(sections, birthDate, sectionId, grid.month(start));
        var fullDays = new ArrayList<Integer>();
        for (int d : new TreeSet<>(days)) {
            if (desiredSection == null || grid.free(desiredSection, start, d) <= 0) {
                fullDays.add(d);
            }
        }
        for (int m = start; m < HORIZON_MONTHS; m++) {
            boolean stable = true;
            for (int k = m; k < HORIZON_MONTHS; k++) {
                stable &= ok[k];
            }
            if (stable) {
                return new Test(m == start ? Verdict.ACCEPTABLE : Verdict.LATER, grid.month(m), fullDays);
            }
        }
        return new Test(Verdict.NONE, null, fullDays);
    }
}
