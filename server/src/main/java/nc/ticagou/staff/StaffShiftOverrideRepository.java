package nc.ticagou.staff;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffShiftOverrideRepository extends JpaRepository<StaffShiftOverride, UUID> {

    List<StaffShiftOverride> findByStructureIdAndDayBetween(UUID structureId, LocalDate from, LocalDate to);

    Optional<StaffShiftOverride> findByUserIdAndDay(UUID userId, LocalDate day);
}
