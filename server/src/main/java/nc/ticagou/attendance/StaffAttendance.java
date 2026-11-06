package nc.ticagou.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Présence d'un membre du personnel, pointée par son code PIN (ou ajoutée par la direction). */
@Entity
@Table(name = "staff_attendance")
public class StaffAttendance {

    public enum Source { PIN, PIN_HORS_LIGNE, BUREAU }

    @Id
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false)
    private LocalDate day;

    @Column(name = "section_id")
    private UUID sectionId;

    @Column(name = "arrived_at", nullable = false)
    private Instant arrivedAt;

    @Column(name = "departed_at")
    private Instant departedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source;

    @Column(name = "pin_verified", nullable = false)
    private boolean pinVerified = true;

    @Column(name = "needs_review", nullable = false)
    private boolean needsReview;

    @Column(name = "review_reason")
    private String reviewReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected StaffAttendance() {
    }

    public StaffAttendance(UUID id, UUID structureId, UUID userId, LocalDate day, UUID sectionId, Instant arrivedAt,
                           Source source) {
        this.id = id;
        this.structureId = structureId;
        this.userId = userId;
        this.day = day;
        this.sectionId = sectionId;
        this.arrivedAt = arrivedAt;
        this.source = source;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isPresent() {
        return departedAt == null;
    }

    public void depart(Instant at) {
        this.departedAt = at;
    }

    public void moveTo(UUID sectionId) {
        this.sectionId = sectionId;
    }

    public void correct(Instant arrivedAt, Instant departedAt, UUID sectionId) {
        this.arrivedAt = arrivedAt;
        this.departedAt = departedAt;
        this.sectionId = sectionId;
    }

    /** Code saisi hors connexion qui ne correspond pas : le pointage est gardé mais à vérifier. */
    public void pinMismatch(String reason) {
        this.pinVerified = false;
        flag(reason);
    }

    public void flag(String reason) {
        this.needsReview = true;
        this.reviewReason = reviewReason == null ? reason : reviewReason + " · " + reason;
    }

    public void reviewed() {
        this.needsReview = false;
        this.reviewReason = null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public UUID getUserId() {
        return userId;
    }

    public LocalDate getDay() {
        return day;
    }

    public UUID getSectionId() {
        return sectionId;
    }

    public Instant getArrivedAt() {
        return arrivedAt;
    }

    public Instant getDepartedAt() {
        return departedAt;
    }

    public Source getSource() {
        return source;
    }

    public boolean isPinVerified() {
        return pinVerified;
    }

    public boolean isNeedsReview() {
        return needsReview;
    }

    public String getReviewReason() {
        return reviewReason;
    }

    public long getVersion() {
        return version;
    }
}
