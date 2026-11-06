package nc.ticagou.billing;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceLineRepository extends JpaRepository<InvoiceLine, UUID> {

    List<InvoiceLine> findByInvoiceIdOrderByPositionAsc(UUID invoiceId);

    List<InvoiceLine> findByInvoiceIdIn(Collection<UUID> invoiceIds);

    List<InvoiceLine> findByChildIdAndKind(UUID childId, InvoiceCalculator.Kind kind);
}
