package nc.ticagou.billing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingRuleRepository extends JpaRepository<BillingRule, UUID> {

    List<BillingRule> findByStructureIdOrderByValidFromDesc(UUID structureId);

    Optional<BillingRule> findByIdAndStructureId(UUID id, UUID structureId);
}
