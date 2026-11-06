package nc.ticagou.child;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "vaccination")
public class Vaccination {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "child_id", nullable = false, updatable = false)
    private UUID childId;

    @Column(nullable = false)
    private String vaccine;

    @Column(name = "last_dose_on")
    private LocalDate lastDoseOn;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    protected Vaccination() {
    }

    public Vaccination(UUID structureId, UUID childId, String vaccine, LocalDate lastDoseOn, int displayOrder) {
        this.structureId = structureId;
        this.childId = childId;
        this.vaccine = vaccine;
        this.lastDoseOn = lastDoseOn;
        this.displayOrder = displayOrder;
    }

    public String getVaccine() {
        return vaccine;
    }

    public LocalDate getLastDoseOn() {
        return lastDoseOn;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }
}
