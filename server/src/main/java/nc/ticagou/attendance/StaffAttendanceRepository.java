package nc.ticagou.attendance;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StaffAttendanceRepository extends JpaRepository<StaffAttendance, UUID> {

    List<StaffAttendance> findByStructureIdAndDayOrderByArrivedAtAsc(UUID structureId, LocalDate day);

    List<StaffAttendance> findByStructureIdAndDayAndDepartedAtIsNull(UUID structureId, LocalDate day);

    Optional<StaffAttendance> findByUserIdAndDayAndDepartedAtIsNull(UUID userId, LocalDate day);

    Optional<StaffAttendance> findByIdAndStructureId(UUID id, UUID structureId);

    List<StaffAttendance> findByStructureIdAndDayBetween(UUID structureId, LocalDate from, LocalDate to);

    @Query("""
            select a from StaffAttendance a where a.structureId = :structureId
            and (a.needsReview = true or (a.departedAt is null and a.day < :today)) order by a.day desc, a.arrivedAt""")
    List<StaffAttendance> findToReview(UUID structureId, LocalDate today);
}
