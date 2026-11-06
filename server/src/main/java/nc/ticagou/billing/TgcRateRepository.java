package nc.ticagou.billing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TgcRateRepository extends JpaRepository<TgcRate, UUID> {

    List<TgcRate> findByStructureIdOrderByServiceAscValidFromDesc(UUID structureId);

    Optional<TgcRate> findByIdAndStructureId(UUID id, UUID structureId);
}
