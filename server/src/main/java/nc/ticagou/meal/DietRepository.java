package nc.ticagou.meal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DietRepository extends JpaRepository<Diet, UUID> {

    List<Diet> findByStructureIdOrderByDisplayOrderAscLabelAsc(UUID structureId);

    Optional<Diet> findByIdAndStructureId(UUID id, UUID structureId);
}
