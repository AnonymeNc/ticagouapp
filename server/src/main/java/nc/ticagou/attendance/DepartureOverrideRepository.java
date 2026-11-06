package nc.ticagou.attendance;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DepartureOverrideRepository extends JpaRepository<DepartureOverride, UUID> {

    List<DepartureOverride> findByAttendanceIdIn(Collection<UUID> attendanceIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from DepartureOverride o where o.attendanceId in :attendanceIds")
    int deleteByAttendanceIdIn(Collection<UUID> attendanceIds);
}
