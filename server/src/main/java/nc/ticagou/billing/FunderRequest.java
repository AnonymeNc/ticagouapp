package nc.ticagou.billing;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Demande de règlement à un financeur pour un mois : les aides déduites des factures, une ligne par enfant. */
@Entity
@Table(name = "funder_request")
public class FunderRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(nullable = false, updatable = false)
    private String number;

    @Column(name = "funder_id", nullable = false, updatable = false)
    private UUID funderId;

    @Column(nullable = false, updatable = false)
    private LocalDate month;

    @Column(nullable = false)
    private long total;

    @Column(name = "sent_on")
    private LocalDate sentOn;

    @Column(name = "sent_by")
    private String sentBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected FunderRequest() {
    }

    public FunderRequest(UUID structureId, String number, UUID funderId, YearMonth month, Instant createdAt) {
        this.structureId = structureId;
        this.number = number;
        this.funderId = funderId;
        this.month = month.atDay(1);
        this.createdAt = createdAt;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public void markSent(LocalDate sentOn, String sentBy) {
        this.sentOn = sentOn;
        this.sentBy = sentBy;
    }

    public boolean isSent() {
        return sentOn != null;
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

    public UUID getFunderId() {
        return funderId;
    }

    public YearMonth getMonth() {
        return YearMonth.from(month);
    }

    public long getTotal() {
        return total;
    }

    public LocalDate getSentOn() {
        return sentOn;
    }

    public String getSentBy() {
        return sentBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getVersion() {
        return version;
    }
}
