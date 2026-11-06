package nc.ticagou.billing;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FunderRequestLineRepository extends JpaRepository<FunderRequestLine, UUID> {

    List<FunderRequestLine> findByRequestId(UUID requestId);

    List<FunderRequestLine> findByRequestIdIn(Collection<UUID> requestIds);

    List<FunderRequestLine> findByInvoiceId(UUID invoiceId);
}
