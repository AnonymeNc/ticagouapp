package nc.ticagou.attendance;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Ligne du journal des corrections de pointage (ajout seul, imposé par trigger). */
@Entity
@Table(name = "attendance_correction")
public class AttendanceCorrection {

    public enum Target { ENFANT, PERSONNEL }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Target target;

    @Column(name = "attendance_id", nullable = false, updatable = false)
    private UUID attendanceId;

    @Column(nullable = false, updatable = false)
    private String field;

    @Column(name = "old_value", updatable = false)
    private String oldValue;

    @Column(name = "new_value", updatable = false)
    private String newValue;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Column(name = "author_id", updatable = false)
    private UUID authorId;

    @Column(nullable = false, updatable = false)
    private String author;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AttendanceCorrection() {
    }

    public AttendanceCorrection(UUID structureId, Target target, UUID attendanceId, String field, String oldValue,
                                String newValue, String reason, UUID authorId, String author, Instant createdAt) {
        this.structureId = structureId;
        this.target = target;
        this.attendanceId = attendanceId;
        this.field = field;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.reason = reason;
        this.authorId = authorId;
        this.author = author;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public Target getTarget() {
        return target;
    }

    public UUID getAttendanceId() {
        return attendanceId;
    }

    public String getField() {
        return field;
    }

    public String getOldValue() {
        return oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public String getReason() {
        return reason;
    }

    public String getAuthor() {
        return author;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
