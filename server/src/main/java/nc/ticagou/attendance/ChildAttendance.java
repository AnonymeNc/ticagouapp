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

/**
 * Passage d'un enfant dans la journée : arrivée, puis départ avec la personne venue le chercher.
 * L'identifiant est fourni par la tablette (opération d'arrivée) pour que les opérations suivantes,
 * faites hors connexion, s'y rattachent.
 */
@Entity
@Table(name = "child_attendance")
public class ChildAttendance {

    public enum Source { TABLETTE, BUREAU }

    @Id
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "child_id", nullable = false, updatable = false)
    private UUID childId;

    @Column(nullable = false)
    private LocalDate day;

    @Column(name = "section_id")
    private UUID sectionId;

    @Column(name = "arrived_at", nullable = false)
    private Instant arrivedAt;

    @Column(name = "departed_at")
    private Instant departedAt;

    @Column(name = "dropped_off_by")
    private String droppedOffBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "picked_up_by_kind")
    private PickupRules.Kind pickedUpByKind;

    @Column(name = "picked_up_by_id")
    private UUID pickedUpById;

    @Column(name = "picked_up_by_name")
    private String pickedUpByName;

    @Column(nullable = false)
    private boolean unplanned;

    @Column(name = "arrival_recorded_by", nullable = false)
    private String arrivalRecordedBy;

    @Column(name = "departure_recorded_by")
    private String departureRecordedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source;

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

    protected ChildAttendance() {
    }

    public ChildAttendance(UUID id, UUID structureId, UUID childId, LocalDate day, UUID sectionId, Instant arrivedAt,
                           boolean unplanned, String recordedBy, Source source) {
        this.id = id;
        this.structureId = structureId;
        this.childId = childId;
        this.day = day;
        this.sectionId = sectionId;
        this.arrivedAt = arrivedAt;
        this.unplanned = unplanned;
        this.arrivalRecordedBy = recordedBy;
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

    public void setDroppedOffBy(String droppedOffBy) {
        this.droppedOffBy = droppedOffBy;
    }

    public void depart(Instant at, PickupRules.Kind kind, UUID personId, String personName, String recordedBy) {
        this.departedAt = at;
        this.pickedUpByKind = kind;
        this.pickedUpById = personId;
        this.pickedUpByName = personName;
        this.departureRecordedBy = recordedBy;
    }

    /** Correction de la direction (heures, section). */
    public void correct(Instant arrivedAt, Instant departedAt, UUID sectionId) {
        this.arrivedAt = arrivedAt;
        this.departedAt = departedAt;
        this.sectionId = sectionId;
        if (departedAt == null) {
            this.pickedUpByKind = null;
            this.pickedUpById = null;
            this.pickedUpByName = null;
            this.departureRecordedBy = null;
        }
    }

    /** Ajoute un motif de vérification (plusieurs motifs s'accumulent). */
    public void flag(String reason) {
        this.needsReview = true;
        this.reviewReason = reviewReason == null ? reason : truncate(reviewReason + " · " + reason);
    }

    public void reviewed() {
        this.needsReview = false;
        this.reviewReason = null;
    }

    private static String truncate(String text) {
        return text.length() <= 300 ? text : text.substring(0, 299) + "…";
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

    public String getDroppedOffBy() {
        return droppedOffBy;
    }

    public PickupRules.Kind getPickedUpByKind() {
        return pickedUpByKind;
    }

    public UUID getPickedUpById() {
        return pickedUpById;
    }

    public String getPickedUpByName() {
        return pickedUpByName;
    }

    public boolean isUnplanned() {
        return unplanned;
    }

    public String getArrivalRecordedBy() {
        return arrivalRecordedBy;
    }

    public String getDepartureRecordedBy() {
        return departureRecordedBy;
    }

    public Source getSource() {
        return source;
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
