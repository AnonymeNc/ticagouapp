package nc.ticagou.attendance;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Départ avec une personne absente de la liste, validé par la direction (code personnel sur la tablette
 * ou session direction au bureau). {@code pinVerified} reste faux si le code saisi hors connexion était faux.
 */
@Entity
@Table(name = "departure_override")
public class DepartureOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "attendance_id", nullable = false, updatable = false)
    private UUID attendanceId;

    @Column(name = "person_name", nullable = false)
    private String personName;

    @Column(nullable = false)
    private String relationship;

    @Column(name = "id_checked", nullable = false)
    private boolean idChecked;

    @Column(name = "validated_by_id")
    private UUID validatedById;

    @Column(name = "validated_by", nullable = false)
    private String validatedBy;

    @Column(name = "validated_at", nullable = false)
    private Instant validatedAt;

    @Column(name = "pin_verified", nullable = false)
    private boolean pinVerified;

    private String reason;

    protected DepartureOverride() {
    }

    public DepartureOverride(UUID structureId, UUID attendanceId, String personName, String relationship,
                             boolean idChecked, UUID validatedById, String validatedBy, Instant validatedAt,
                             boolean pinVerified, String reason) {
        this.structureId = structureId;
        this.attendanceId = attendanceId;
        this.personName = personName;
        this.relationship = relationship;
        this.idChecked = idChecked;
        this.validatedById = validatedById;
        this.validatedBy = validatedBy;
        this.validatedAt = validatedAt;
        this.pinVerified = pinVerified;
        this.reason = reason;
    }

    public UUID getAttendanceId() {
        return attendanceId;
    }

    public String getPersonName() {
        return personName;
    }

    public String getRelationship() {
        return relationship;
    }

    public boolean isIdChecked() {
        return idChecked;
    }

    public String getValidatedBy() {
        return validatedBy;
    }

    public Instant getValidatedAt() {
        return validatedAt;
    }

    public boolean isPinVerified() {
        return pinVerified;
    }

    public String getReason() {
        return reason;
    }
}
