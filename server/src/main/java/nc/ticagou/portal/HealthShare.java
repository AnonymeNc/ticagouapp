package nc.ticagou.portal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Information de santé que la direction a choisi de montrer à la famille sur le portail. Le texte est figé au
 * moment du partage : une modification ultérieure de la fiche santé ne sort pas d'elle-même.
 */
@Entity
@Table(name = "health_share")
public class HealthShare {

    @Id
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "child_id", nullable = false, updatable = false)
    private UUID childId;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private String text;

    @Column(name = "shared_by", nullable = false)
    private String sharedBy;

    @Column(name = "shared_at", nullable = false)
    private Instant sharedAt;

    protected HealthShare() {
    }

    public HealthShare(UUID id, UUID structureId, UUID childId, String label, String text, String sharedBy,
                       Instant sharedAt) {
        this.id = id;
        this.structureId = structureId;
        this.childId = childId;
        this.label = label;
        this.text = text;
        this.sharedBy = sharedBy;
        this.sharedAt = sharedAt;
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

    public String getLabel() {
        return label;
    }

    public String getText() {
        return text;
    }

    public String getSharedBy() {
        return sharedBy;
    }

    public Instant getSharedAt() {
        return sharedAt;
    }
}
