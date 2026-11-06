package nc.ticagou.contract;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractRepository extends JpaRepository<Contract, UUID> {

    List<Contract> findByStructureId(UUID structureId);

    List<Contract> findByChildIdOrderByStartDateDesc(UUID childId);

    List<Contract> findByChildIdIn(Collection<UUID> childIds);

    List<Contract> findByRequestId(UUID requestId);

    List<Contract> findByRequestIdIn(Collection<UUID> requestIds);

    Optional<Contract> findByIdAndStructureId(UUID id, UUID structureId);
}
