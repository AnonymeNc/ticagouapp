package nc.ticagou.child;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "allergy")
public class Allergy {

    public enum Severity { LEGERE, MODEREE, SEVERE }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "child_id", nullable = false, updatable = false)
    private UUID childId;

    @Column(nullable = false)
    private String allergen;

    private String reaction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    protected Allergy() {
    }

    public Allergy(UUID structureId, UUID childId, String allergen, String reaction, Severity severity,
                   int displayOrder) {
        this.structureId = structureId;
        this.childId = childId;
        this.allergen = allergen;
        this.reaction = reaction;
        this.severity = severity;
        this.displayOrder = displayOrder;
    }

    public UUID getChildId() {
        return childId;
    }

    public String getAllergen() {
        return allergen;
    }

    public String getReaction() {
        return reaction;
    }

    public Severity getSeverity() {
        return severity;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }
}
