package nc.ticagou.enrollment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Critère de priorité d'une demande (fratrie, domicile, situation…) et ses points, sur une période de validité. */
@Entity
@Table(name = "enrollment_criterion")
public class EnrollmentCriterion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private int points;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected EnrollmentCriterion() {
    }

    public EnrollmentCriterion(UUID structureId) {
        this.structureId = structureId;
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

    public void update(String label, int points, LocalDate validFrom, LocalDate validTo, int displayOrder) {
        this.label = label;
        this.points = points;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.displayOrder = displayOrder;
    }

    public boolean isValidOn(LocalDate day) {
        return !validFrom.isAfter(day) && (validTo == null || !validTo.isBefore(day));
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

    public int getPoints() {
        return points;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }
}
