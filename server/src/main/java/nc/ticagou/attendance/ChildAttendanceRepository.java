package nc.ticagou.attendance;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ChildAttendanceRepository extends JpaRepository<ChildAttendance, UUID> {

    List<ChildAttendance> findByStructureIdAndDayOrderByArrivedAtAsc(UUID structureId, LocalDate day);

    List<ChildAttendance> findByStructureIdAndDayAndDepartedAtIsNull(UUID structureId, LocalDate day);

    List<ChildAttendance> findByStructureIdAndDayBetween(UUID structureId, LocalDate from, LocalDate to);

    Optional<ChildAttendance> findByChildIdAndDayAndDepartedAtIsNull(UUID childId, LocalDate day);

    List<ChildAttendance> findByChildIdAndDayGreaterThanEqual(UUID childId, LocalDate since);

    Optional<ChildAttendance> findByIdAndStructureId(UUID id, UUID structureId);

    /** Pointages restés ouverts les jours précédents (départ jamais enregistré) ou marqués à vérifier. */
    @Query("""
            select a from ChildAttendance a where a.structureId = :structureId
            and (a.needsReview = true or (a.departedAt is null and a.day < :today)) order by a.day desc, a.arrivedAt""")
    List<ChildAttendance> findToReview(UUID structureId, LocalDate today);

    List<ChildAttendance> findByChildIdInOrderByDayAscArrivedAtAsc(Collection<UUID> childIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ChildAttendance a where a.childId in :childIds")
    int deleteByChildIdIn(Collection<UUID> childIds);
}
