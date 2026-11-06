package nc.ticagou.staff;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffScheduleRepository extends JpaRepository<StaffSchedule, UUID> {

    List<StaffSchedule> findByStructureId(UUID structureId);

    List<StaffSchedule> findByUserIdOrderByEffectiveFromAsc(UUID userId);

    Optional<StaffSchedule> findByUserIdAndEffectiveFrom(UUID userId, LocalDate effectiveFrom);
}
