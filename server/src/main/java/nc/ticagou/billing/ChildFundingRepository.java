package nc.ticagou.billing;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChildFundingRepository extends JpaRepository<ChildFunding, UUID> {

    List<ChildFunding> findByChildIdOrderByFromDayDesc(UUID childId);

    List<ChildFunding> findByChildIdIn(Collection<UUID> childIds);

    Optional<ChildFunding> findByIdAndStructureId(UUID id, UUID structureId);

    boolean existsByFunderId(UUID funderId);

    long countByFunderId(UUID funderId);
}
