package nc.ticagou.staff;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffTrainingRepository extends JpaRepository<StaffTraining, UUID> {

    List<StaffTraining> findByStructureId(UUID structureId);

    List<StaffTraining> findByUserIdOrderByStartsOnDesc(UUID userId);

    Optional<StaffTraining> findByIdAndStructureId(UUID id, UUID structureId);
}
