package nc.ticagou.attendance;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.contract.ContractRepository;
import nc.ticagou.contract.ContractVersion;
import nc.ticagou.contract.ContractVersionRepository;

/**
 * Enfants attendus un jour donné, d'après la version signée en vigueur de leur contrat. Un planning variable
 * n'annonce pas d'heure : l'enfant n'est pas « attendu », mais son accueil n'est pas « hors planning ». Un accueil
 * occasionnel accepté (lot 2) rend l'enfant attendu ce jour-là, sans changer sa facturation : le temps passé hors
 * du planning du contrat reste facturé en heures complémentaires.
 */
@Component
public class PlannedDays {

    public record Planned(LocalTime arrivesAt, LocalTime leavesAt, boolean variable) {

        public boolean expected() {
            return !variable;
        }
    }

    private final ContractRepository contracts;
    private final ContractVersionRepository versions;
    private final nc.ticagou.portal.BookingRequestRepository bookings;

    public PlannedDays(ContractRepository contracts, ContractVersionRepository versions,
                       nc.ticagou.portal.BookingRequestRepository bookings) {
        this.contracts = contracts;
        this.versions = versions;
        this.bookings = bookings;
    }

    @Transactional(readOnly = true)
    public Map<UUID, Planned> forDay(UUID structureId, LocalDate day) {
        Map<UUID, List<ContractVersion>> signed = versions.findByStructureIdAndStatus(structureId, ContractVersion.Status.SIGNE)
                .stream().collect(Collectors.groupingBy(ContractVersion::getContractId));
        var result = new HashMap<UUID, Planned>();
        for (var contract : contracts.findByStructureId(structureId)) {
            if (!contract.covers(day)) {
                continue;
            }
            var inForce = signed.getOrDefault(contract.getId(), List.of()).stream()
                    .filter(v -> !v.getEffectiveFrom().isAfter(day))
                    .max(Comparator.comparing(ContractVersion::getEffectiveFrom));
            if (inForce.isEmpty()) {
                continue;
            }
            var version = inForce.get();
            if (version.getPlanningMode() == ContractVersion.PlanningMode.VARIABLE) {
                result.putIfAbsent(contract.getChildId(), new Planned(null, null, true));
                continue;
            }
            version.getDays().stream().filter(d -> d.dayOfWeek() == day.getDayOfWeek().getValue()).findFirst()
                    .ifPresent(d -> result.put(contract.getChildId(), new Planned(d.arrivesAt(), d.leavesAt(), false)));
        }
        for (var b : bookings.findByStructureIdAndDayAndStatus(structureId, day, nc.ticagou.portal.BookingRequest.Status.ACCEPTEE)) {
            if (b.getKind() == nc.ticagou.portal.BookingRequest.Kind.RESERVATION) {
                result.merge(b.getChildId(), new Planned(b.getArrivesAt(), b.getLeavesAt(), false), (contract, booked) ->
                        contract.variable() ? booked : new Planned(min(contract.arrivesAt(), booked.arrivesAt()),
                                max(contract.leavesAt(), booked.leavesAt()), false));
            }
        }
        return result;
    }

    private static LocalTime min(LocalTime a, LocalTime b) {
        return a.isBefore(b) ? a : b;
    }

    private static LocalTime max(LocalTime a, LocalTime b) {
        return a.isAfter(b) ? a : b;
    }
}
