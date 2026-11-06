package nc.ticagou.funder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TariffScheduleRepository extends JpaRepository<TariffSchedule, UUID> {

    List<TariffSchedule> findByStructureIdOrderByValidFromDesc(UUID structureId);

    Optional<TariffSchedule> findByIdAndStructureId(UUID id, UUID structureId);
}
