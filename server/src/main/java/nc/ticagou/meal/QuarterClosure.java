package nc.ticagou.meal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Trimestre clos par la direction : comptes figés, pointages et corrections des enfants refusés. */
@Entity
@Table(name = "meal_quarter_closure")
public class QuarterClosure {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(nullable = false, updatable = false)
    private int year;

    @Column(nullable = false, updatable = false)
    private int quarter;

    @Column(name = "closed_at", nullable = false, updatable = false)
    private Instant closedAt;

    @Column(name = "closed_by", nullable = false, updatable = false)
    private String closedBy;

    @Column(name = "reopened_at")
    private Instant reopenedAt;

    @Column(name = "reopened_by")
    private String reopenedBy;

    @Column(name = "reopen_reason")
    private String reopenReason;

    protected QuarterClosure() {
    }

    public QuarterClosure(UUID structureId, int year, int quarter, Instant closedAt, String closedBy) {
        this.structureId = structureId;
        this.year = year;
        this.quarter = quarter;
        this.closedAt = closedAt;
        this.closedBy = closedBy;
    }

    public void reopen(Instant at, String by, String reason) {
        this.reopenedAt = at;
        this.reopenedBy = by;
        this.reopenReason = reason;
    }

    public UUID getId() {
        return id;
    }

    public int getYear() {
        return year;
    }

    public int getQuarter() {
        return quarter;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public String getClosedBy() {
        return closedBy;
    }
}
