package nc.ticagou.structure;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Règles de cohérence des sections d'une structure : tranches d'âge sans chevauchement,
 * total des places inférieur ou égal à la capacité de l'agrément.
 */
public final class SectionRules {

    private SectionRules() {
    }

    /** Tranche [minAgeMonths, maxAgeMonths[ ; maxAgeMonths nul = sans limite. */
    public record Range(UUID id, String name, int minAgeMonths, Integer maxAgeMonths, int capacity) {

        boolean overlaps(Range other) {
            boolean startsBeforeOtherEnds = other.maxAgeMonths == null || minAgeMonths < other.maxAgeMonths;
            boolean otherStartsBeforeThisEnds = maxAgeMonths == null || other.minAgeMonths < maxAgeMonths;
            return startsBeforeOtherEnds && otherStartsBeforeThisEnds;
        }
    }

    /** Message d'erreur si deux tranches se chevauchent. */
    public static Optional<String> findOverlap(List<Range> ranges) {
        for (int i = 0; i < ranges.size(); i++) {
            for (int j = i + 1; j < ranges.size(); j++) {
                var a = ranges.get(i);
                var b = ranges.get(j);
                if (a.overlaps(b)) {
                    return Optional.of("Les tranches d’âge des sections « %s » et « %s » se chevauchent."
                            .formatted(a.name(), b.name()));
                }
            }
        }
        return Optional.empty();
    }

    /** Trous entre deux tranches consécutives (un enfant de cet âge n'aurait aucune section). */
    public static List<String> findGaps(List<Range> ranges) {
        var sorted = ranges.stream().sorted(Comparator.comparingInt(Range::minAgeMonths)).toList();
        var gaps = new ArrayList<String>();
        for (int i = 0; i + 1 < sorted.size(); i++) {
            var a = sorted.get(i);
            var b = sorted.get(i + 1);
            if (a.maxAgeMonths() != null && a.maxAgeMonths() < b.minAgeMonths()) {
                gaps.add("Aucune section n’accueille les enfants de %d à %d mois, entre « %s » et « %s »."
                        .formatted(a.maxAgeMonths(), b.minAgeMonths(), a.name(), b.name()));
            }
        }
        return gaps;
    }

    /** Enfant inscrit, avec sa section actuelle. */
    public record EnrolledChild(UUID childId, String name, LocalDate birthDate, UUID sectionId) {
    }

    /**
     * Passage à proposer : {@code toSectionId} nul = l'enfant dépasse la dernière section (sortie, entrée à l'école).
     * {@code blocked} : la section d'arrivée est complète et la règle bloque le passage.
     */
    public record Passage(UUID childId, String name, UUID fromSectionId, UUID toSectionId, LocalDate on,
                          long ageMonths, boolean blocked) {
    }

    /**
     * Enfants qui atteignent l'âge de sortie de leur section d'ici un mois. Avec le regroupement, le passage se fait
     * le 1er du mois suivant cet anniversaire.
     */
    public static List<Passage> passages(List<Range> sections, List<EnrolledChild> children, LocalDate today,
                                         boolean firstOfMonth, boolean blockFull) {
        var occupancy = new HashMap<UUID, Integer>();
        children.forEach(c -> occupancy.merge(c.sectionId(), 1, Integer::sum));
        var result = new ArrayList<Passage>();
        for (var child : children) {
            var section = sections.stream().filter(s -> s.id().equals(child.sectionId())).findFirst().orElse(null);
            if (section == null || section.maxAgeMonths() == null) {
                continue;
            }
            var reached = child.birthDate().plusMonths(section.maxAgeMonths());
            var on = firstOfMonth && reached.getDayOfMonth() != 1 ? reached.withDayOfMonth(1).plusMonths(1) : reached;
            if (on.isAfter(today.plusMonths(1))) {
                continue;
            }
            long age = ChronoUnit.MONTHS.between(child.birthDate(), on);
            var target = sections.stream().filter(s -> !s.id().equals(section.id()) && s.minAgeMonths() <= age
                    && (s.maxAgeMonths() == null || age < s.maxAgeMonths())).findFirst().orElse(null);
            boolean blocked = blockFull && target != null && occupancy.getOrDefault(target.id(), 0) >= target.capacity();
            result.add(new Passage(child.childId(), child.name(), section.id(), target == null ? null : target.id(), on,
                    age, blocked));
        }
        result.sort(Comparator.comparing(Passage::on).thenComparing(Passage::name));
        return result;
    }

    /** Message d'erreur si le total des places dépasse la capacité de l'agrément. */
    public static Optional<String> checkCapacity(Collection<Range> ranges, int structureCapacity) {
        int total = ranges.stream().mapToInt(Range::capacity).sum();
        if (total > structureCapacity) {
            return Optional.of("Le total des places des sections (%d) dépasse la capacité de l’agrément (%d)."
                    .formatted(total, structureCapacity));
        }
        return Optional.empty();
    }
}
