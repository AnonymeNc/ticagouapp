package nc.ticagou.billing;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdAndStructureId(UUID id, UUID structureId);

    List<Payment> findTop50ByStructureIdOrderByPaidOnDescCreatedAtDesc(UUID structureId);

    List<Payment> findByStructureIdAndPaidOnBetween(UUID structureId, LocalDate from, LocalDate to);

    List<Payment> findByFamilyIdIn(Collection<UUID> familyIds);
}
