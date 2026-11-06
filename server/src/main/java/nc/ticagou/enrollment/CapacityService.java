package nc.ticagou.enrollment;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.auth.CurrentUser;
import nc.ticagou.child.Child;
import nc.ticagou.child.ChildRepository;
import nc.ticagou.contract.ContractRepository;
import nc.ticagou.contract.ContractVersion;
import nc.ticagou.contract.ContractVersionRepository;
import nc.ticagou.contract.ScheduleDay;
import nc.ticagou.enrollment.CapacityCalculator.Grid;
import nc.ticagou.enrollment.CapacityCalculator.Occupant;
import nc.ticagou.enrollment.CapacityCalculator.Period;
import nc.ticagou.enrollment.CapacityCalculator.SectionSlot;
import nc.ticagou.enrollment.CapacityDtos.CapacityDto;
import nc.ticagou.enrollment.CapacityDtos.DayRow;
import nc.ticagou.enrollment.CapacityDtos.SectionCapacity;
import nc.ticagou.structure.OpeningHours;
import nc.ticagou.structure.Section;
import nc.ticagou.structure.SectionRepository;
import nc.ticagou.structure.StructureRepository;

/**
 * Alimente {@link CapacityCalculator} : versions signées des contrats (avec les sorties prévues),
 * places proposées en attente de réponse, sections et jours d'ouverture de la structure.
 * Un contrat au planning variable occupe une place chaque jour d'ouverture (calcul prudent).
 */
@Service
public class CapacityService {

    private final StructureRepository structures;
    private final SectionRepository sections;
    private final ChildRepository children;
    private final ContractRepository contracts;
    private final ContractVersionRepository versions;
    private final EnrollmentRequestRepository requests;
    private final Clock clock;

    public CapacityService(StructureRepository structures, SectionRepository sections, ChildRepository children,
                           ContractRepository contracts, ContractVersionRepository versions,
                           EnrollmentRequestRepository requests, Clock clock) {
        this.structures = structures;
        this.sections = sections;
        this.children = children;
        this.contracts = contracts;
        this.versions = versions;
        this.requests = requests;
        this.clock = clock;
    }

    /** Grille calculée et sections, pour tester ensuite une ou plusieurs demandes. */
    public record Snapshot(Grid grid, List<SectionSlot> slots, List<Section> sections) {

        public CapacityCalculator.Test test(LocalDate birthDate, UUID sectionId, Collection<Integer> days,
                                            LocalDate desiredStart) {
            return CapacityCalculator.test(grid, slots, birthDate, sectionId, days, desiredStart);
        }
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot(YearMonth from, UUID excludeRequestId) {
        var structureId = CurrentUser.get().structureId();
        var structure = structures.findById(structureId).orElseThrow();
        var sectionList = sections.findByStructureIdOrderByDisplayOrderAscMinAgeMonthsAsc(structureId);
        var slots = sectionList.stream()
                .map(s -> new SectionSlot(s.getId(), s.getMinAgeMonths(), s.getMaxAgeMonths(), s.getCapacity()))
                .toList();
        Set<Integer> openDays = structure.getOpeningHours().stream().map(OpeningHours::dayOfWeek)
                .collect(Collectors.toSet());

        var occupants = new ArrayList<Occupant>();
        occupants.addAll(contractOccupants(structureId, openDays));
        for (var request : requests.findByStructureIdAndStage(structureId, EnrollmentRequest.Stage.PLACE_PROPOSEE)) {
            if (!request.getId().equals(excludeRequestId)) {
                occupants.add(new Occupant(request.effectiveBirthDate(), request.getSectionId(),
                        List.of(new Period(request.getDesiredStart(), null, request.getDays()))));
            }
        }
        return new Snapshot(CapacityCalculator.compute(from, slots, openDays, occupants), slots, sectionList);
    }

    @Transactional(readOnly = true)
    public CapacityDto grid(YearMonth from) {
        var snapshot = snapshot(from, null);
        var grid = snapshot.grid();
        var months = new ArrayList<YearMonth>();
        for (int m = 0; m < grid.months(); m++) {
            months.add(grid.month(m));
        }
        var result = snapshot.sections().stream().map(section -> {
            var free = grid.free().get(section.getId());
            var rows = new ArrayList<DayRow>();
            for (int d = 0; d < grid.openDays().size(); d++) {
                var column = new int[grid.months()];
                for (int m = 0; m < grid.months(); m++) {
                    column[m] = free[m][d];
                }
                rows.add(new DayRow(grid.openDays().get(d), column));
            }
            return new SectionCapacity(section.getId(), section.getName(), section.getColor(), section.getCapacity(), rows);
        }).toList();
        return new CapacityDto(months, grid.openDays(), result);
    }

    public YearMonth currentMonth() {
        return YearMonth.now(clock);
    }

    private List<Occupant> contractOccupants(UUID structureId, Set<Integer> openDays) {
        Map<UUID, Child> childById = children.findByStructureId(structureId).stream()
                .collect(Collectors.toMap(Child::getId, Function.identity()));
        Map<UUID, List<ContractVersion>> signed = versions.findByStructureIdAndStatus(structureId, ContractVersion.Status.SIGNE)
                .stream().collect(Collectors.groupingBy(ContractVersion::getContractId));
        var result = new ArrayList<Occupant>();
        for (var contract : contracts.findByStructureId(structureId)) {
            var contractVersions = signed.getOrDefault(contract.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(ContractVersion::getEffectiveFrom)).toList();
            var child = childById.get(contract.getChildId());
            if (contractVersions.isEmpty() || child == null) {
                continue;
            }
            var end = child.getExitDate() != null && child.getExitDate().isBefore(contract.getEndDate())
                    ? child.getExitDate() : contract.getEndDate();
            var periods = new ArrayList<Period>();
            for (int i = 0; i < contractVersions.size(); i++) {
                var version = contractVersions.get(i);
                var from = version.getEffectiveFrom().isBefore(contract.getStartDate())
                        ? contract.getStartDate() : version.getEffectiveFrom();
                var to = i + 1 < contractVersions.size() ? contractVersions.get(i + 1).getEffectiveFrom().minusDays(1) : end;
                if (to.isAfter(end)) {
                    to = end;
                }
                if (!from.isAfter(to)) {
                    periods.add(new Period(from, to, daysOf(version, openDays)));
                }
            }
            if (!periods.isEmpty()) {
                result.add(new Occupant(child.getBirthDate(), child.getSectionId(), periods));
            }
        }
        return result;
    }

    private static Set<Integer> daysOf(ContractVersion version, Set<Integer> openDays) {
        if (version.getPlanningMode() == ContractVersion.PlanningMode.VARIABLE) {
            return openDays;
        }
        return version.getDays().stream().map(ScheduleDay::dayOfWeek).filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }
}
