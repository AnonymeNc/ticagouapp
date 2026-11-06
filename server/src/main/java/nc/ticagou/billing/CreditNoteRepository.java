package nc.ticagou.billing;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditNoteRepository extends JpaRepository<CreditNote, UUID> {

    Optional<CreditNote> findByIdAndStructureId(UUID id, UUID structureId);

    List<CreditNote> findByInvoiceIdOrderByCreatedAtAsc(UUID invoiceId);

    List<CreditNote> findByInvoiceIdIn(Collection<UUID> invoiceIds);
}
