package nc.ticagou.billing;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Aide réclamée pour un enfant. Après un avoir total et une nouvelle facture, la ligne ne réclame que l'écart
 * avec ce qui a déjà été demandé (négative : régularisation en faveur du financeur).
 */
@Entity
@Table(name = "funder_request_line")
public class FunderRequestLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "child_id", nullable = false, updatable = false)
    private UUID childId;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    private String beneficiary;

    @Column(name = "attendance_days", nullable = false)
    private int attendanceDays;

    @Column(nullable = false)
    private long amount;

    protected FunderRequestLine() {
    }

    public FunderRequestLine(FunderRequest request, UUID childId, UUID invoiceId, String beneficiary,
                             int attendanceDays, long amount) {
        this.structureId = request.getStructureId();
        this.requestId = request.getId();
        this.childId = childId;
        this.invoiceId = invoiceId;
        this.beneficiary = beneficiary;
        this.attendanceDays = attendanceDays;
        this.amount = amount;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public UUID getChildId() {
        return childId;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public String getBeneficiary() {
        return beneficiary;
    }

    public int getAttendanceDays() {
        return attendanceDays;
    }

    public long getAmount() {
        return amount;
    }
}
