package nc.ticagou.billing;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAllocationRepository extends JpaRepository<PaymentAllocation, UUID> {

    List<PaymentAllocation> findByPaymentIdIn(Collection<UUID> paymentIds);

    List<PaymentAllocation> findByInvoiceIdIn(Collection<UUID> invoiceIds);
}
