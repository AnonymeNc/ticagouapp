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

/** Absence signalée d'un enfant ; déduite de la facture selon son motif (carence, préavis, justificatif). */
@Entity
@Table(name = "child_absence")
public class ChildAbsence {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "child_id", nullable = false, updatable = false)
    private UUID childId;

    @Column(name = "from_day", nullable = false)
    private LocalDate fromDay;

    @Column(name = "to_day", nullable = false)
    private LocalDate toDay;

    @Column(name = "reason_id", nullable = false)
    private UUID reasonId;

    @Column(name = "notified_on", nullable = false)
    private LocalDate notifiedOn;

    @Column(name = "document_id")
    private UUID documentId;

    private String note;

    @Column(name = "recorded_by", nullable = false)
    private String recordedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected ChildAbsence() {
    }

    public ChildAbsence(UUID structureId, UUID childId, String recordedBy, Instant createdAt) {
        this.structureId = structureId;
        this.childId = childId;
        this.recordedBy = recordedBy;
        this.createdAt = createdAt;
    }

    public void update(LocalDate fromDay, LocalDate toDay, UUID reasonId, LocalDate notifiedOn, UUID documentId,
                       String note) {
        this.fromDay = fromDay;
        this.toDay = toDay;
        this.reasonId = reasonId;
        this.notifiedOn = notifiedOn;
        this.documentId = documentId;
        this.note = note;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public UUID getChildId() {
        return childId;
    }

    public LocalDate getFromDay() {
        return fromDay;
    }

    public LocalDate getToDay() {
        return toDay;
    }

    public UUID getReasonId() {
        return reasonId;
    }

    public LocalDate getNotifiedOn() {
        return notifiedOn;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public String getNote() {
        return note;
    }

    public String getRecordedBy() {
        return recordedBy;
    }

    public long getVersion() {
        return version;
    }
}
