package nc.ticagou.meal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Semaine de menus, du lundi {@code weekStart} ; brouillon tant que la cuisine ne l'a pas publiée. */
@Entity
@Table(name = "menu_week")
public class MenuWeek {

    public enum Status { BROUILLON, PUBLIE }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "week_start", nullable = false, updatable = false)
    private LocalDate weekStart;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.BROUILLON;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false)
    private String updatedBy;

    @Version
    private long version;

    protected MenuWeek() {
    }

    public MenuWeek(UUID structureId, LocalDate weekStart) {
        this.structureId = structureId;
        this.weekStart = weekStart;
    }

    public void touch(Status status, Instant at, String by) {
        this.status = status;
        this.updatedAt = at;
        this.updatedBy = by;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public LocalDate getWeekStart() {
        return weekStart;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public long getVersion() {
        return version;
    }
}
