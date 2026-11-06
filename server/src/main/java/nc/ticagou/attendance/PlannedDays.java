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
 * n'annonce pas d'heure : l'enfant n'est pas « attendu », mais son accueil n'est pas « hors planning ».
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

    public PlannedDays(ContractRepository contracts, ContractVersionRepository versions) {
        this.contracts = contracts;
        this.versions = versions;
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
        return result;
    }
}
