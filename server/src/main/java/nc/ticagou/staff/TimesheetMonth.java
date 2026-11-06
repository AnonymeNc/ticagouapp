package nc.ticagou.staff;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Mois de feuilles d'heures validé par la direction : la version exportée pour la paie est figée. */
@Entity
@Table(name = "timesheet_month")
public class TimesheetMonth {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(nullable = false, updatable = false)
    private LocalDate month;

    @Column(name = "validated_at", nullable = false, updatable = false)
    private Instant validatedAt;

    @Column(name = "validated_by", nullable = false, updatable = false)
    private String validatedBy;

    @Column(nullable = false, updatable = false)
    private String snapshot;

    protected TimesheetMonth() {
    }

    public TimesheetMonth(UUID structureId, YearMonth month, Instant validatedAt, String validatedBy, String snapshot) {
        this.structureId = structureId;
        this.month = month.atDay(1);
        this.validatedAt = validatedAt;
        this.validatedBy = validatedBy;
        this.snapshot = snapshot;
    }

    public UUID getId() {
        return id;
    }

    public Instant getValidatedAt() {
        return validatedAt;
    }

    public String getValidatedBy() {
        return validatedBy;
    }

    public String getSnapshot() {
        return snapshot;
    }
}
