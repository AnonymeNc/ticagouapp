package nc.ticagou.portal;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ReportedAbsenceRepository extends JpaRepository<ReportedAbsence, UUID> {

    boolean existsByPortalRef(UUID portalRef);

    Optional<ReportedAbsence> findByIdAndStructureId(UUID id, UUID structureId);

    List<ReportedAbsence> findByStructureIdAndStatusOrderByFromDayAsc(UUID structureId, ReportedAbsence.Status status);

    @Query("select r from ReportedAbsence r where r.structureId = :structureId and r.status <> 'ECARTEE' "
            + "and r.fromDay <= :day and r.toDay >= :day")
    List<ReportedAbsence> findCovering(UUID structureId, LocalDate day);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ReportedAbsence r where r.familyId = :familyId")
    int deleteByFamilyId(UUID familyId);
}
