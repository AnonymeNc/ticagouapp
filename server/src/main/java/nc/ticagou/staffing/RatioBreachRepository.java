package nc.ticagou.staffing;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RatioBreachRepository extends JpaRepository<RatioBreach, UUID> {

    List<RatioBreach> findByStructureIdAndEndedAtIsNull(UUID structureId);
}
