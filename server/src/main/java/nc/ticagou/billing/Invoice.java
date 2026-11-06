package nc.ticagou.billing;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Facture émise : immuable (trigger en base), corrigée par un avoir. L'état de paiement se déduit du lettrage. */
@Entity
@Immutable
@Table(name = "invoice")
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false)
    private UUID structureId;

    @Column(nullable = false)
    private String number;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "payer_name", nullable = false)
    private String payerName;

    @Column(name = "payer_address")
    private String payerAddress;

    @Column(nullable = false)
    private LocalDate month;

    @Column(name = "issued_on", nullable = false)
    private LocalDate issuedOn;

    @Column(name = "due_on", nullable = false)
    private LocalDate dueOn;

    @Column(name = "care_total", nullable = false)
    private long careTotal;

    @Column(name = "tgc_total", nullable = false)
    private long tgcTotal;

    @Column(name = "aid_total", nullable = false)
    private long aidTotal;

    @Column(name = "family_total", nullable = false)
    private long familyTotal;

    @Column(nullable = false)
    private long rounding;

    @Column(name = "contract_text")
    private String contractText;

    @Column(name = "issued_by", nullable = false)
    private String issuedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Invoice() {
    }

    public Invoice(UUID structureId, String number, UUID familyId, String payerName, String payerAddress,
                   YearMonth month, LocalDate issuedOn, LocalDate dueOn, long careTotal, long tgcTotal, long aidTotal,
                   long familyTotal, long rounding, String contractText, String issuedBy, Instant createdAt) {
        this.structureId = structureId;
        this.number = number;
        this.familyId = familyId;
        this.payerName = payerName;
        this.payerAddress = payerAddress;
        this.month = month.atDay(1);
        this.issuedOn = issuedOn;
        this.dueOn = dueOn;
        this.careTotal = careTotal;
        this.tgcTotal = tgcTotal;
        this.aidTotal = aidTotal;
        this.familyTotal = familyTotal;
        this.rounding = rounding;
        this.contractText = contractText;
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

    public UUID getFamilyId() {
        return familyId;
    }

    public String getPayerName() {
        return payerName;
    }

    public String getPayerAddress() {
        return payerAddress;
    }

    public YearMonth getMonth() {
        return YearMonth.from(month);
    }

    public LocalDate getIssuedOn() {
        return issuedOn;
    }

    public LocalDate getDueOn() {
        return dueOn;
    }

    public long getCareTotal() {
        return careTotal;
    }

    public long getTgcTotal() {
        return tgcTotal;
    }

    public long getAidTotal() {
        return aidTotal;
    }

    public long getFamilyTotal() {
        return familyTotal;
    }

    public long getRounding() {
        return rounding;
    }

    public String getContractText() {
        return contractText;
    }

    public String getIssuedBy() {
        return issuedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
