package nc.ticagou.staffing;

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

/** Règle d'encadrement datée : nombre maximal d'enfants par adulte présent pour une tranche d'âge. */
@Entity
@Table(name = "ratio_rule")
public class RatioRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(nullable = false)
    private String label;

    @Column(name = "min_age_months", nullable = false)
    private int minAgeMonths;

    @Column(name = "max_age_months")
    private Integer maxAgeMonths;

    @Column(name = "children_per_adult", nullable = false)
    private int childrenPerAdult;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected RatioRule() {
    }

    public RatioRule(UUID structureId) {
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

    public void update(String label, int minAgeMonths, Integer maxAgeMonths, int childrenPerAdult,
                       LocalDate validFrom, LocalDate validTo) {
        this.label = label;
        this.minAgeMonths = minAgeMonths;
        this.maxAgeMonths = maxAgeMonths;
        this.childrenPerAdult = childrenPerAdult;
        this.validFrom = validFrom;
        this.validTo = validTo;
    }

    public RatioCalculator.Rule toRule() {
        return new RatioCalculator.Rule(label, minAgeMonths, maxAgeMonths, childrenPerAdult, validFrom, validTo);
    }

    /** Même tranche d'âge (au moins en partie) sur des périodes qui se recouvrent. */
    public boolean overlaps(RatioRule other) {
        boolean ages = (other.maxAgeMonths == null || minAgeMonths < other.maxAgeMonths)
                && (maxAgeMonths == null || other.minAgeMonths < maxAgeMonths);
        boolean dates = (other.validTo == null || !validFrom.isAfter(other.validTo))
                && (validTo == null || !other.validFrom.isAfter(validTo));
        return ages && dates;
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

    public int getMinAgeMonths() {
        return minAgeMonths;
    }

    public Integer getMaxAgeMonths() {
        return maxAgeMonths;
    }

    public int getChildrenPerAdult() {
        return childrenPerAdult;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public long getVersion() {
        return version;
    }
}
