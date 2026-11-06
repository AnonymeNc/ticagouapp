package nc.ticagou.billing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Encaissement d'une famille ou d'un financeur. Il ne se supprime pas : on l'annule avec un motif (tracé). */
@Entity
@Table(name = "payment")
public class Payment {

    public enum Method { VIREMENT, CHEQUE, ESPECES, CARTE }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "family_id", updatable = false)
    private UUID familyId;

    @Column(name = "funder_id", updatable = false)
    private UUID funderId;

    @Column(name = "payer_name", nullable = false, updatable = false)
    private String payerName;

    @Column(name = "paid_on", nullable = false, updatable = false)
    private LocalDate paidOn;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Method method;

    @Column(updatable = false)
    private String reference;

    @Column(updatable = false)
    private String bank;

    @Column(name = "receipt_number", updatable = false)
    private String receiptNumber;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private String recordedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by")
    private String cancelledBy;

    @Column(name = "cancel_reason")
    private String cancelReason;

    protected Payment() {
    }

    public Payment(UUID structureId, UUID familyId, UUID funderId, String payerName, LocalDate paidOn, long amount,
                   Method method, String reference, String bank, String receiptNumber, String recordedBy,
                   Instant createdAt) {
        this.structureId = structureId;
        this.familyId = familyId;
        this.funderId = funderId;
        this.payerName = payerName;
        this.paidOn = paidOn;
        this.amount = amount;
        this.method = method;
        this.reference = reference;
        this.bank = bank;
        this.receiptNumber = receiptNumber;
        this.recordedBy = recordedBy;
        this.createdAt = createdAt;
    }

    public void cancel(Instant at, String by, String reason) {
        this.cancelledAt = at;
        this.cancelledBy = by;
        this.cancelReason = reason;
    }

    public boolean isCancelled() {
        return cancelledAt != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public UUID getFunderId() {
        return funderId;
    }

    public String getPayerName() {
        return payerName;
    }

    public LocalDate getPaidOn() {
        return paidOn;
    }

    public long getAmount() {
        return amount;
    }

    public Method getMethod() {
        return method;
    }

    public String getReference() {
        return reference;
    }

    public String getBank() {
        return bank;
    }

    public String getReceiptNumber() {
        return receiptNumber;
    }

    public String getRecordedBy() {
        return recordedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getCancelledBy() {
        return cancelledBy;
    }

    public String getCancelReason() {
        return cancelReason;
    }
}
