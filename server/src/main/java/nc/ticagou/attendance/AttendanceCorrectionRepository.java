package nc.ticagou.attendance;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AttendanceCorrectionRepository extends JpaRepository<AttendanceCorrection, UUID> {

    List<AttendanceCorrection> findByStructureIdAndAttendanceIdOrderByCreatedAtAsc(UUID structureId, UUID attendanceId);
}
