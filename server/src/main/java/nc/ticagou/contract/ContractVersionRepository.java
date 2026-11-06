package nc.ticagou.contract;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractVersionRepository extends JpaRepository<ContractVersion, UUID> {

    List<ContractVersion> findByContractIdOrderByNumberAsc(UUID contractId);

    List<ContractVersion> findByContractIdIn(Collection<UUID> contractIds);

    List<ContractVersion> findByStructureIdAndStatus(UUID structureId, ContractVersion.Status status);

    Optional<ContractVersion> findByContractIdAndNumber(UUID contractId, int number);

    boolean existsByTariffScheduleId(UUID tariffScheduleId);

    boolean existsBySignedDocumentId(UUID documentId);
}
