package nc.ticagou.billing;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ChildAbsenceRepository extends JpaRepository<ChildAbsence, UUID> {

    List<ChildAbsence> findByChildIdOrderByFromDayDesc(UUID childId);

    List<ChildAbsence> findByChildIdIn(Collection<UUID> childIds);

    Optional<ChildAbsence> findByIdAndStructureId(UUID id, UUID structureId);

    boolean existsByReasonId(UUID reasonId);

    /** Absences qui chevauchent la période [from, to]. */
    @Query("""
            select a from ChildAbsence a
            where a.structureId = :structureId and a.fromDay <= :to and a.toDay >= :from
            order by a.fromDay""")
    List<ChildAbsence> overlapping(UUID structureId, LocalDate from, LocalDate to);
}
