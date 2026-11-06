package nc.ticagou.staff;

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

import nc.ticagou.staff.Shifts.AbsenceKind;

/** Absence du personnel (congés, maladie, formation, récupération), saisie au bureau. */
@Entity
@Table(name = "staff_absence")
public class StaffAbsence {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AbsenceKind kind;

    @Column(name = "from_day", nullable = false)
    private LocalDate fromDay;

    @Column(name = "to_day", nullable = false)
    private LocalDate toDay;

    @Column(name = "half_day", nullable = false)
    private boolean halfDay;

    private String note;

    @Column(nullable = false)
    private String author;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected StaffAbsence() {
    }

    public StaffAbsence(UUID structureId, UUID userId, Instant createdAt) {
        this.structureId = structureId;
        this.userId = userId;
        this.createdAt = createdAt;
    }

    public void update(AbsenceKind kind, LocalDate fromDay, LocalDate toDay, boolean halfDay, String note, String author) {
        this.kind = kind;
        this.fromDay = fromDay;
        this.toDay = toDay;
        this.halfDay = halfDay;
        this.note = note;
        this.author = author;
    }

    public Shifts.Absence toAbsence() {
        return new Shifts.Absence(kind, fromDay, toDay, halfDay);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public AbsenceKind getKind() {
        return kind;
    }

    public LocalDate getFromDay() {
        return fromDay;
    }

    public LocalDate getToDay() {
        return toDay;
    }

    public boolean isHalfDay() {
        return halfDay;
    }

    public String getNote() {
        return note;
    }

    public String getAuthor() {
        return author;
    }

    public long getVersion() {
        return version;
    }
}
