package nc.ticagou.billing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Avoir sur une facture : total (la famille peut être refacturée pour le mois) ou partiel. Immuable. */
@Entity
@Immutable
@Table(name = "credit_note")
public class CreditNote {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false)
    private UUID structureId;

    @Column(nullable = false)
    private String number;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "issued_on", nullable = false)
    private LocalDate issuedOn;

    @Column(nullable = false)
    private String reason;

    @Column(name = "full_cancel", nullable = false)
    private boolean fullCancel;

    @Column(name = "family_total", nullable = false)
    private long familyTotal;

    @Column(name = "aid_total", nullable = false)
    private long aidTotal;

    @Column(name = "issued_by", nullable = false)
    private String issuedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CreditNote() {
    }

    public CreditNote(UUID structureId, String number, UUID invoiceId, LocalDate issuedOn, String reason,
                      boolean fullCancel, long familyTotal, long aidTotal, String issuedBy, Instant createdAt) {
        this.structureId = structureId;
        this.number = number;
        this.invoiceId = invoiceId;
        this.issuedOn = issuedOn;
        this.reason = reason;
        this.fullCancel = fullCancel;
        this.familyTotal = familyTotal;
        this.aidTotal = aidTotal;
        this.issuedBy = issuedBy;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public String getNumber() {
        return number;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public LocalDate getIssuedOn() {
        return issuedOn;
    }

    public String getReason() {
        return reason;
    }

    public boolean isFullCancel() {
        return fullCancel;
    }

    public long getFamilyTotal() {
        return familyTotal;
    }

    public long getAidTotal() {
        return aidTotal;
    }

    public String getIssuedBy() {
        return issuedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
