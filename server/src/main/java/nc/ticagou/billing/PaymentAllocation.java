package nc.ticagou.billing;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Lettrage : part d'un paiement affectée à une facture ou à une demande au financeur. */
@Entity
@Table(name = "payment_allocation")
public class PaymentAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "invoice_id", updatable = false)
    private UUID invoiceId;

    @Column(name = "funder_request_id", updatable = false)
    private UUID funderRequestId;

    @Column(nullable = false, updatable = false)
    private long amount;

    protected PaymentAllocation() {
    }

    public PaymentAllocation(Payment payment, UUID invoiceId, UUID funderRequestId, long amount) {
        this.structureId = payment.getStructureId();
        this.paymentId = payment.getId();
        this.invoiceId = invoiceId;
        this.funderRequestId = funderRequestId;
        this.amount = amount;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public UUID getFunderRequestId() {
        return funderRequestId;
    }

    public long getAmount() {
        return amount;
    }
}
