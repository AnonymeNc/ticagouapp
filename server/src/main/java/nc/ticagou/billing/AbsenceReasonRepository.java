package nc.ticagou.billing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AbsenceReasonRepository extends JpaRepository<AbsenceReason, UUID> {

    List<AbsenceReason> findByStructureIdOrderByDisplayOrderAscLabelAsc(UUID structureId);

    Optional<AbsenceReason> findByIdAndStructureId(UUID id, UUID structureId);
}
