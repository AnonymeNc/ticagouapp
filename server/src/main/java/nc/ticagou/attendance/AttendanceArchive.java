package nc.ticagou.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.attendance.AttendanceDtos.InstructionDto;
import nc.ticagou.attendance.AttendanceDtos.OverrideDto;

/**
 * Pointages dans l'export et la purge des données d'une famille. Le journal des corrections ne garde que des
 * heures et des motifs : il reste, comme le journal des consultations (ADR 0002).
 */
@Service
public class AttendanceArchive {

    public record AttendanceRecord(UUID childId, LocalDate day, Instant arrivedAt, Instant departedAt,
                                   String droppedOffBy, String pickedUpBy, List<InstructionDto> instructions,
                                   OverrideDto override) {
    }

    private final ChildAttendanceRepository attendances;
    private final ChildInstructionRepository instructions;
    private final DepartureOverrideRepository overrides;
    private final KioskOperationRepository operations;

    public AttendanceArchive(ChildAttendanceRepository attendances, ChildInstructionRepository instructions,
                             DepartureOverrideRepository overrides, KioskOperationRepository operations) {
        this.attendances = attendances;
        this.instructions = instructions;
        this.overrides = overrides;
        this.operations = operations;
    }

    @Transactional(readOnly = true)
    public List<AttendanceRecord> export(Collection<UUID> childIds) {
        if (childIds.isEmpty()) {
            return List.of();
        }
        var list = attendances.findByChildIdInOrderByDayAscArrivedAtAsc(childIds);
        var ids = list.stream().map(ChildAttendance::getId).toList();
        var instructionsByAttendance = ids.isEmpty() ? java.util.Map.<UUID, List<InstructionDto>>of()
                : instructions.findByAttendanceIdInOrderByCreatedAtAsc(ids).stream()
                .collect(Collectors.groupingBy(ChildInstruction::getAttendanceId,
                        Collectors.mapping(InstructionDto::from, Collectors.toList())));
        var overrideByAttendance = ids.isEmpty() ? java.util.Map.<UUID, DepartureOverride>of()
                : overrides.findByAttendanceIdIn(ids).stream()
                .collect(Collectors.toMap(DepartureOverride::getAttendanceId, Function.identity()));
        return list.stream().map(a -> new AttendanceRecord(a.getChildId(), a.getDay(), a.getArrivedAt(),
                a.getDepartedAt(), a.getDroppedOffBy(), a.getPickedUpByName(),
                instructionsByAttendance.getOrDefault(a.getId(), List.of()),
                OverrideDto.from(overrideByAttendance.get(a.getId())))).toList();
    }

    /** Supprime pointages, consignes, validations de départ et opérations de tablette des enfants. */
    @Transactional
    public int purge(Collection<UUID> childIds) {
        if (childIds.isEmpty()) {
            return 0;
        }
        var ids = attendances.findByChildIdInOrderByDayAscArrivedAtAsc(childIds).stream()
                .map(ChildAttendance::getId).toList();
        if (!ids.isEmpty()) {
            instructions.deleteByAttendanceIdIn(ids);
            overrides.deleteByAttendanceIdIn(ids);
        }
        operations.deleteByChildIdIn(childIds);
        return attendances.deleteByChildIdIn(childIds);
    }
}
