package nc.ticagou.meal;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Comptage des repas et des goûters, sans dépendance à Spring.
 * <ul>
 *   <li>servi : l'enfant est pointé présent à l'heure du repas (arrivé au plus tard à cette heure, parti au plus
 *       tôt à cette heure ou toujours présent) ;</li>
 *   <li>prévu, pour la journée en cours avant l'heure : présent sans départ prévu avant l'heure, ou attendu d'après
 *       son contrat avec une arrivée prévue au plus tard à l'heure ;</li>
 *   <li>panier repas fourni par la famille : compté à part, ni repas ni goûter ;</li>
 *   <li>une correction manuelle l'emporte sur le calcul.</li>
 * </ul>
 */
public final class MealCounter {

    private MealCounter() {
    }

    public enum Meal { REPAS, GOUTER }

    public enum Texture { NORMALE, HACHEE, MIXEE }

    /** Passage pointé ; {@code departedAt} nul = toujours présent. */
    public record Passage(LocalTime arrivedAt, LocalTime departedAt) {

        boolean covers(LocalTime time) {
            return !arrivedAt.isAfter(time) && (departedAt == null || !departedAt.isBefore(time));
        }
    }

    /**
     * Journée d'un enfant. {@code plannedArrival}/{@code plannedLeave} : contrat du jour (nuls sans planning) ;
     * {@code lunchOverride}/{@code snackOverride} : correction manuelle (nulle sans correction).
     */
    public record ChildDay(UUID childId, UUID sectionId, List<Passage> passages, LocalTime plannedArrival,
                           LocalTime plannedLeave, boolean packedLunch, Texture texture, Boolean lunchOverride,
                           Boolean snackOverride) {
    }

    /** Origine d'un compte : pointage passé, prévision du jour, correction manuelle. */
    public enum Basis { POINTAGE, PREVISION, CORRECTION }

    public record ChildCount(UUID childId, UUID sectionId, boolean lunch, boolean snack, boolean packedLunch,
                             boolean present, Basis lunchBasis, Basis snackBasis, Texture texture) {
    }

    public record SectionTotal(int lunches, int snacks, int packedLunches, int children, int minced, int mashed,
                               int leavingBeforeSnack) {
    }

    public record Result(List<ChildCount> children, Map<UUID, SectionTotal> sections, SectionTotal total) {
    }

    /**
     * @param now heure actuelle pour la journée en cours ; nulle pour une journée passée (seuls les pointages
     *            comptent)
     */
    public static Result count(LocalTime lunchTime, LocalTime snackTime, LocalTime now, List<ChildDay> days) {
        var counts = new ArrayList<ChildCount>();
        for (var day : days) {
            var lunch = decide(day, lunchTime, now, day.lunchOverride());
            var snack = decide(day, snackTime, now, day.snackOverride());
            boolean packed = day.packedLunch() && (lunch.counted || snack.counted);
            boolean present = lunch.counted || snack.counted || day.passages().stream().anyMatch(p -> p.departedAt() == null);
            counts.add(new ChildCount(day.childId(), day.sectionId(), lunch.counted && !day.packedLunch(),
                    snack.counted && !day.packedLunch(), packed, present, lunch.basis, snack.basis,
                    day.texture() == null ? Texture.NORMALE : day.texture()));
        }
        var bySection = new LinkedHashMap<UUID, List<ChildCount>>();
        for (var c : counts) {
            bySection.computeIfAbsent(c.sectionId(), k -> new ArrayList<>()).add(c);
        }
        var sections = new LinkedHashMap<UUID, SectionTotal>();
        bySection.forEach((section, list) -> sections.put(section, total(list)));
        return new Result(counts, sections, total(counts));
    }

    private record Decision(boolean counted, Basis basis) {
    }

    private static Decision decide(ChildDay day, LocalTime time, LocalTime now, Boolean override) {
        if (override != null) {
            return new Decision(override, Basis.CORRECTION);
        }
        boolean past = now == null || !now.isBefore(time);
        if (past) {
            return new Decision(day.passages().stream().anyMatch(p -> p.covers(time)), Basis.POINTAGE);
        }
        boolean leavesBefore = day.plannedLeave() != null && day.plannedLeave().isBefore(time);
        var open = day.passages().stream().anyMatch(p -> p.departedAt() == null);
        if (open) {
            return new Decision(!leavesBefore, Basis.PREVISION);
        }
        if (!day.passages().isEmpty()) {
            // Venu puis reparti avant l'heure : pas de retour prévu par le contrat.
            return new Decision(false, Basis.PREVISION);
        }
        boolean expected = day.plannedArrival() != null && !day.plannedArrival().isAfter(time) && !leavesBefore;
        return new Decision(expected, Basis.PREVISION);
    }

    private static SectionTotal total(List<ChildCount> list) {
        int lunches = 0, snacks = 0, packed = 0, children = 0, minced = 0, mashed = 0, leaving = 0;
        for (var c : list) {
            if (c.lunch()) {
                lunches++;
                if (c.texture() == Texture.HACHEE) {
                    minced++;
                } else if (c.texture() == Texture.MIXEE) {
                    mashed++;
                }
            }
            if (c.snack()) {
                snacks++;
            }
            if (c.packedLunch()) {
                packed++;
            }
            if (c.lunch() || c.snack() || c.packedLunch()) {
                children++;
            }
            if (c.lunch() && !c.snack() && !c.packedLunch()) {
                leaving++;
            }
        }
        return new SectionTotal(lunches, snacks, packed, children, minced, mashed, leaving);
    }
}
