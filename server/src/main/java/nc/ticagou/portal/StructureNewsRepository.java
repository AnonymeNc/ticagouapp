package nc.ticagou.portal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StructureNewsRepository extends JpaRepository<StructureNews, UUID> {

    List<StructureNews> findByStructureIdOrderByPublishedAtDesc(UUID structureId);

    Optional<StructureNews> findByIdAndStructureId(UUID id, UUID structureId);
}
