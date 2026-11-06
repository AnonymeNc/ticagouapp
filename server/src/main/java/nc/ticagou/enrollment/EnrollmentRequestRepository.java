package nc.ticagou.enrollment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EnrollmentRequestRepository extends JpaRepository<EnrollmentRequest, UUID> {

    List<EnrollmentRequest> findByStructureId(UUID structureId);

    List<EnrollmentRequest> findByStructureIdAndStage(UUID structureId, EnrollmentRequest.Stage stage);

    Optional<EnrollmentRequest> findByIdAndStructureId(UUID id, UUID structureId);

    @Query("select r from EnrollmentRequest r where r.familyId = :familyId or r.childId in :childIds")
    List<EnrollmentRequest> findByFamilyOrChildren(UUID familyId, Collection<UUID> childIds);
}
