package nc.ticagou.billing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FunderRepository extends JpaRepository<Funder, UUID> {

    List<Funder> findByStructureIdOrderByPriorityAscNameAsc(UUID structureId);

    Optional<Funder> findByIdAndStructureId(UUID id, UUID structureId);
}
