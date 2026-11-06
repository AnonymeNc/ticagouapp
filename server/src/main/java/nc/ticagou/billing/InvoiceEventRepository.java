package nc.ticagou.billing;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceEventRepository extends JpaRepository<InvoiceEvent, UUID> {

    List<InvoiceEvent> findByInvoiceIdOrderByCreatedAtAsc(UUID invoiceId);

    List<InvoiceEvent> findByInvoiceIdInAndKind(Collection<UUID> invoiceIds, InvoiceEvent.Kind kind);
}
