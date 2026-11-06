package nc.ticagou.billing;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FunderRequestRepository extends JpaRepository<FunderRequest, UUID> {

    Optional<FunderRequest> findByIdAndStructureId(UUID id, UUID structureId);

    List<FunderRequest> findByStructureIdAndMonthOrderByNumberAsc(UUID structureId, LocalDate month);

    List<FunderRequest> findByStructureIdOrderByMonthDescNumberDesc(UUID structureId);

    List<FunderRequest> findByFunderIdAndMonth(UUID funderId, LocalDate month);
}
