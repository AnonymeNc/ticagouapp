package nc.ticagou.billing;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Motif d'absence : déductible ou non, justificatif exigé, carence, préavis (règlement de fonctionnement). */
@Entity
@Table(name = "absence_reason")
public class AbsenceReason {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private boolean deductible;

    @Column(name = "proof_required", nullable = false)
    private boolean proofRequired;

    @Column(name = "waiting_days_apply", nullable = false)
    private boolean waitingDaysApply;

    @Column(name = "notice_applies", nullable = false)
    private boolean noticeApplies;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private boolean active = true;

    @Version
    private long version;

    protected AbsenceReason() {
    }

    public AbsenceReason(UUID structureId) {
        this.structureId = structureId;
    }

    public void update(String label, boolean deductible, boolean proofRequired, boolean waitingDaysApply,
                       boolean noticeApplies, int displayOrder, boolean active) {
        this.label = label;
        this.deductible = deductible;
        this.proofRequired = proofRequired;
        this.waitingDaysApply = waitingDaysApply;
        this.noticeApplies = noticeApplies;
        this.displayOrder = displayOrder;
        this.active = active;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public String getLabel() {
        return label;
    }

    public boolean isDeductible() {
        return deductible;
    }

    public boolean isProofRequired() {
        return proofRequired;
    }

    public boolean isWaitingDaysApply() {
        return waitingDaysApply;
    }

    public boolean isNoticeApplies() {
        return noticeApplies;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public boolean isActive() {
        return active;
    }

    public long getVersion() {
        return version;
    }
}
