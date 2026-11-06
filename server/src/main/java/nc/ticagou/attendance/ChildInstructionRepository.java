package nc.ticagou.attendance;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ChildInstructionRepository extends JpaRepository<ChildInstruction, UUID> {

    List<ChildInstruction> findByAttendanceIdInOrderByCreatedAtAsc(Collection<UUID> attendanceIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ChildInstruction i where i.attendanceId in :attendanceIds")
    int deleteByAttendanceIdIn(Collection<UUID> attendanceIds);
}
