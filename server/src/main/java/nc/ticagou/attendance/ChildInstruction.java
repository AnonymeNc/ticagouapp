package nc.ticagou.attendance;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Consigne du parent à l'arrivée ; un médicament porte son heure, sa dose et la trace de sa prise. */
@Entity
@Table(name = "child_instruction")
public class ChildInstruction {

    public enum Kind { A_MAL_DORMI, PEU_MANGE, MEDICAMENT, NEZ_QUI_COULE, SIESTE_AVANCER, AUTRE }

    @Id
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "attendance_id", nullable = false, updatable = false)
    private UUID attendanceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    private String details;

    @Column(name = "medication_at")
    private LocalTime medicationAt;

    @Column(name = "medication_dose")
    private String medicationDose;

    @Column(name = "prescription_given", nullable = false)
    private boolean prescriptionGiven;

    @Column(name = "given_at")
    private Instant givenAt;

    @Column(name = "given_by")
    private String givenBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ChildInstruction() {
    }

    public ChildInstruction(UUID id, UUID structureId, UUID attendanceId, Kind kind, String details,
                            LocalTime medicationAt, String medicationDose, boolean prescriptionGiven, Instant createdAt) {
        this.id = id;
        this.structureId = structureId;
        this.attendanceId = attendanceId;
        this.kind = kind;
        this.details = details;
        this.medicationAt = medicationAt;
        this.medicationDose = medicationDose;
        this.prescriptionGiven = prescriptionGiven;
        this.createdAt = createdAt;
    }

    public void given(Instant at, String by) {
        this.givenAt = at;
        this.givenBy = by;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAttendanceId() {
        return attendanceId;
    }

    public Kind getKind() {
        return kind;
    }

    public String getDetails() {
        return details;
    }

    public LocalTime getMedicationAt() {
        return medicationAt;
    }

    public String getMedicationDose() {
        return medicationDose;
    }

    public boolean isPrescriptionGiven() {
        return prescriptionGiven;
    }

    public Instant getGivenAt() {
        return givenAt;
    }

    public String getGivenBy() {
        return givenBy;
    }
}
