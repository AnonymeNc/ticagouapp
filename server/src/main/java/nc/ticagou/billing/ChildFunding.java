package nc.ticagou.billing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Aide d'un financeur accordée à un enfant, sur une période ; montant ou taux propres éventuels. */
@Entity
@Table(name = "child_funding")
public class ChildFunding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "child_id", nullable = false, updatable = false)
    private UUID childId;

    @Column(name = "funder_id", nullable = false)
    private UUID funderId;

    @Column(name = "from_day", nullable = false)
    private LocalDate fromDay;

    @Column(name = "to_day")
    private LocalDate toDay;

    private String beneficiary;

    @Column(name = "file_number")
    private String fileNumber;

    @Column(name = "amount_override")
    private Long amountOverride;

    @Column(name = "rate_override")
    private Integer rateOverride;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected ChildFunding() {
    }

    public ChildFunding(UUID structureId, UUID childId, Instant createdAt) {
        this.structureId = structureId;
        this.childId = childId;
        this.createdAt = createdAt;
    }

    public void update(UUID funderId, LocalDate fromDay, LocalDate toDay, String beneficiary, String fileNumber,
                       Long amountOverride, Integer rateOverride) {
        this.funderId = funderId;
        this.fromDay = fromDay;
        this.toDay = toDay;
        this.beneficiary = beneficiary;
        this.fileNumber = fileNumber;
        this.amountOverride = amountOverride;
        this.rateOverride = rateOverride;
    }

    public UUID getId() {
        return id;
    }

    public UUID getChildId() {
        return childId;
    }

    public UUID getFunderId() {
        return funderId;
    }

    public LocalDate getFromDay() {
        return fromDay;
    }

    public LocalDate getToDay() {
        return toDay;
    }

    public String getBeneficiary() {
        return beneficiary;
    }

    public String getFileNumber() {
        return fileNumber;
    }

    public Long getAmountOverride() {
        return amountOverride;
    }

    public Integer getRateOverride() {
        return rateOverride;
    }

    public long getVersion() {
        return version;
    }
}
