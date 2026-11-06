package nc.ticagou.staff;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffAbsenceRepository extends JpaRepository<StaffAbsence, UUID> {

    List<StaffAbsence> findByStructureIdAndFromDayLessThanEqualAndToDayGreaterThanEqual(UUID structureId, LocalDate to, LocalDate from);

    List<StaffAbsence> findByUserIdOrderByFromDayDesc(UUID userId);

    Optional<StaffAbsence> findByIdAndStructureId(UUID id, UUID structureId);
}
