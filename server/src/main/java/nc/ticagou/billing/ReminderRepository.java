package nc.ticagou.billing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReminderRepository extends JpaRepository<Reminder, UUID> {

    Optional<Reminder> findByIdAndStructureId(UUID id, UUID structureId);

    List<Reminder> findByInvoiceIdOrderByLevelAsc(UUID invoiceId);
}
