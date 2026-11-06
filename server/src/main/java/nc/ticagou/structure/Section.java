package nc.ticagou.structure;

import java.time.Instant;
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

/** Section d'accueil (Bébés, Moyens, Grands…) avec sa tranche d'âge [min, max[ en mois. */
@Entity
@Table(name = "section")
public class Section {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(nullable = false)
    private String name;

    @Column(name = "min_age_months", nullable = false)
    private int minAgeMonths;

    /** Âge de sortie exclu ; nul = jusqu'à l'entrée à l'école. */
    @Column(name = "max_age_months")
    private Integer maxAgeMonths;

    @Column(nullable = false)
    private int capacity;

    /** Couleur d'affichage, « #rrggbb ». */
    @Column(nullable = false)
    private String color;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Section() {
    }

    public Section(UUID structureId) {
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

    public void update(String name, int minAgeMonths, Integer maxAgeMonths, int capacity, String color,
                       int displayOrder) {
        this.name = name;
        this.minAgeMonths = minAgeMonths;
        this.maxAgeMonths = maxAgeMonths;
        this.capacity = capacity;
        this.color = color;
        this.displayOrder = displayOrder;
    }

    public SectionRules.Range range() {
        return new SectionRules.Range(id, name, minAgeMonths, maxAgeMonths, capacity);
    }

    public UUID getId() {
        return id;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public String getName() {
        return name;
    }

    public int getMinAgeMonths() {
        return minAgeMonths;
    }

    public Integer getMaxAgeMonths() {
        return maxAgeMonths;
    }

    public int getCapacity() {
        return capacity;
    }

    public String getColor() {
        return color;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }
}
