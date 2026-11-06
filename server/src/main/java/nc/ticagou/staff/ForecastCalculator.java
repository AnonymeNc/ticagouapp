package nc.ticagou.staff;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import nc.ticagou.staffing.RatioCalculator;
import nc.ticagou.staffing.RatioCalculator.Load;
import nc.ticagou.staffing.RatioCalculator.PresentAdult;
import nc.ticagou.staffing.RatioCalculator.PresentChild;
import nc.ticagou.staffing.RatioCalculator.Rule;

/**
 * Encadrement prévisionnel d'une journée, par créneau : enfants attendus selon leur contrat contre adultes planifiés
 * qui comptent dans l'encadrement, avec les mêmes ratios datés que le temps réel ({@link RatioCalculator}).
 * Par prudence, un enfant compte dès qu'il est attendu pendant une partie du créneau ; un adulte seulement s'il
 * couvre tout le créneau.
 */
public final class ForecastCalculator {

    private ForecastCalculator() {
    }

    public record ExpectedChild(UUID childId, UUID sectionId, LocalDate birthDate, LocalTime arrivesAt, LocalTime leavesAt) {
    }

    public record PlannedAdult(UUID userId, UUID sectionId, LocalTime startsAt, LocalTime endsAt) {
    }

    public record Slot(LocalTime startsAt, LocalTime endsAt, Map<UUID, Load> sections, Load global) {
    }

    public static List<Slot> compute(LocalDate day, LocalTime opensAt, LocalTime closesAt, int slotMinutes,
                                     List<Rule> rules, Collection<UUID> sectionIds, List<ExpectedChild> children,
                                     List<PlannedAdult> adults) {
        var slots = new ArrayList<Slot>();
        for (var start = opensAt; start.isBefore(closesAt); start = start.plusMinutes(slotMinutes)) {
            var end = start.plusMinutes(slotMinutes);
            if (end.isAfter(closesAt) || end.isBefore(start)) {
                end = closesAt;
            }
            var from = start;
            var to = end;
            var present = children.stream()
                    .filter(c -> c.arrivesAt().isBefore(to) && c.leavesAt().isAfter(from))
                    .map(c -> new PresentChild(c.sectionId(), c.birthDate())).toList();
            var staffed = adults.stream()
                    .filter(a -> !a.startsAt().isAfter(from) && !a.endsAt().isBefore(to))
                    .map(a -> new PresentAdult(a.sectionId())).toList();
            var result = RatioCalculator.compute(day, rules, sectionIds, present, staffed);
            slots.add(new Slot(from, to, result.sections(), result.global()));
        }
        return slots;
    }
}
