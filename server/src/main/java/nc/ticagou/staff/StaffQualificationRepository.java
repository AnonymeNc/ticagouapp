package nc.ticagou.staff;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffQualificationRepository extends JpaRepository<StaffQualification, UUID> {

    List<StaffQualification> findByStructureId(UUID structureId);

    List<StaffQualification> findByUserIdOrderByValidUntilAsc(UUID userId);

    Optional<StaffQualification> findByIdAndStructureId(UUID id, UUID structureId);
}
