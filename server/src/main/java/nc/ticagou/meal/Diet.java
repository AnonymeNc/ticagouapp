package nc.ticagou.meal;

import java.time.Instant;
import java.util.Set;
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

/** Régime alimentaire (Paramètres › Repas et régimes) et les étiquettes de plats qu'il exclut. */
@Entity
@Table(name = "diet")
public class Diet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private String excludes = "";

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Diet() {
    }

    public Diet(UUID structureId) {
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

    public void update(String label, Set<String> excludes, int displayOrder) {
        this.label = label;
        this.excludes = MealTags.format(excludes);
        this.displayOrder = displayOrder;
    }

    public Set<String> excludedTags() {
        return MealTags.parse(excludes);
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

    public int getDisplayOrder() {
        return displayOrder;
    }

    public long getVersion() {
        return version;
    }
}
