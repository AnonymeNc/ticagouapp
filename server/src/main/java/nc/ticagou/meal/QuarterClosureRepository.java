package nc.ticagou.meal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuarterClosureRepository extends JpaRepository<QuarterClosure, UUID> {

    Optional<QuarterClosure> findByStructureIdAndYearAndQuarterAndReopenedAtIsNull(UUID structureId, int year, int quarter);
}
