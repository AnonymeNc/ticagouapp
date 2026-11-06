package nc.ticagou.family;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FamilyRepository extends JpaRepository<Family, UUID> {

    Optional<Family> findByIdAndStructureId(UUID id, UUID structureId);

    List<Family> findByStructureIdOrderByLabelAsc(UUID structureId);
}
