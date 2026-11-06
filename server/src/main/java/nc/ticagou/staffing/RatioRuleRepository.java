package nc.ticagou.staffing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RatioRuleRepository extends JpaRepository<RatioRule, UUID> {

    List<RatioRule> findByStructureIdOrderByMinAgeMonthsAscValidFromDesc(UUID structureId);

    Optional<RatioRule> findByIdAndStructureId(UUID id, UUID structureId);
}
