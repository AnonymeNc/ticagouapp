package nc.ticagou.staff;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Exception au planning d'un jour : autre horaire, autre section, ou jour non travaillé. */
@Entity
@Table(name = "staff_shift_override")
public class StaffShiftOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private LocalDate day;

    @Column(name = "starts_at")
    private LocalTime startsAt;

    @Column(name = "ends_at")
    private LocalTime endsAt;

    @Column(name = "break_minutes", nullable = false)
    private int breakMinutes;

    @Column(name = "section_id")
    private UUID sectionId;

    private String note;

    @Column(nullable = false)
    private String author;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected StaffShiftOverride() {
    }

    public StaffShiftOverride(UUID structureId, UUID userId, LocalDate day, Instant createdAt) {
        this.structureId = structureId;
        this.userId = userId;
        this.day = day;
        this.createdAt = createdAt;
    }

    public void update(LocalTime startsAt, LocalTime endsAt, int breakMinutes, UUID sectionId, String note, String author) {
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.breakMinutes = breakMinutes;
        this.sectionId = sectionId;
        this.note = note;
        this.author = author;
    }

    public Shifts.Override toOverride() {
        return new Shifts.Override(day, startsAt, endsAt, breakMinutes, sectionId, note);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public LocalDate getDay() {
        return day;
    }
}
