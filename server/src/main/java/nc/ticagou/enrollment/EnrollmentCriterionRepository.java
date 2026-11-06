package nc.ticagou.enrollment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EnrollmentCriterionRepository extends JpaRepository<EnrollmentCriterion, UUID> {

    List<EnrollmentCriterion> findByStructureIdOrderByDisplayOrderAscLabelAsc(UUID structureId);

    Optional<EnrollmentCriterion> findByIdAndStructureId(UUID id, UUID structureId);
}
