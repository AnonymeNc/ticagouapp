package nc.ticagou.child;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Santé d'un enfant : régime, PAI, protocole d'urgence, médecin, consignes. Allergies et vaccins à part. */
@Entity
@Table(name = "health_info")
public class HealthInfo {

    @Id
    @Column(name = "child_id")
    private UUID childId;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    private String diet;

    @Column(name = "diet_details")
    private String dietDetails;

    /** Régime de la liste (Paramètres › Repas et régimes) ; {@code diet} en garde le libellé. */
    @Column(name = "diet_id")
    private UUID dietId;

    @Column(name = "meal_texture", nullable = false)
    private String mealTexture = "NORMALE";

    /** Panier repas fourni par la famille (PAI) : ni repas ni goûter préparés par la cuisine. */
    @Column(name = "packed_lunch", nullable = false)
    private boolean packedLunch;

    @Column(name = "pai_active", nullable = false)
    private boolean paiActive;

    @Column(name = "pai_reason")
    private String paiReason;

    @Column(name = "pai_valid_from")
    private LocalDate paiValidFrom;

    @Column(name = "pai_valid_until")
    private LocalDate paiValidUntil;

    @Column(name = "pai_kit_location")
    private String paiKitLocation;

    @Column(name = "pai_kit_details")
    private String paiKitDetails;

    @Column(name = "pai_document_id")
    private UUID paiDocumentId;

    /** « En cas de réaction » : affiché dans le bandeau santé critique. */
    @Column(name = "emergency_protocol")
    private String emergencyProtocol;

    @Column(name = "doctor_name")
    private String doctorName;

    @Column(name = "doctor_phone")
    private String doctorPhone;

    @Column(name = "doctor_address")
    private String doctorAddress;

    /** Consignes, une par ligne. */
    private String instructions;

    @Column(name = "vaccination_checked_on")
    private LocalDate vaccinationCheckedOn;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected HealthInfo() {
    }

    public HealthInfo(UUID childId, UUID structureId) {
        this.childId = childId;
        this.structureId = structureId;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public void update(String diet, String dietDetails, boolean paiActive, String paiReason, LocalDate paiValidFrom,
                       LocalDate paiValidUntil, String paiKitLocation, String paiKitDetails, UUID paiDocumentId,
                       String emergencyProtocol, String doctorName, String doctorPhone, String doctorAddress,
                       String instructions, LocalDate vaccinationCheckedOn) {
        this.diet = diet;
        this.dietDetails = dietDetails;
        this.paiActive = paiActive;
        this.paiReason = paiReason;
        this.paiValidFrom = paiValidFrom;
        this.paiValidUntil = paiValidUntil;
        this.paiKitLocation = paiKitLocation;
        this.paiKitDetails = paiKitDetails;
        this.paiDocumentId = paiDocumentId;
        this.emergencyProtocol = emergencyProtocol;
        this.doctorName = doctorName;
        this.doctorPhone = doctorPhone;
        this.doctorAddress = doctorAddress;
        this.instructions = instructions;
        this.vaccinationCheckedOn = vaccinationCheckedOn;
    }

    public void updateMeals(UUID dietId, String mealTexture, boolean packedLunch) {
        this.dietId = dietId;
        this.mealTexture = mealTexture == null ? "NORMALE" : mealTexture;
        this.packedLunch = packedLunch;
    }

    public UUID getDietId() {
        return dietId;
    }

    public String getMealTexture() {
        return mealTexture;
    }

    public boolean isPackedLunch() {
        return packedLunch;
    }

    /** PAI en vigueur à cette date (actif et non expiré). */
    public boolean isPaiValidOn(LocalDate day) {
        return paiActive && (paiValidUntil == null || !paiValidUntil.isBefore(day));
    }

    public UUID getChildId() {
        return childId;
    }

    public String getDiet() {
        return diet;
    }

    public String getDietDetails() {
        return dietDetails;
    }

    public boolean isPaiActive() {
        return paiActive;
    }

    public String getPaiReason() {
        return paiReason;
    }

    public LocalDate getPaiValidFrom() {
        return paiValidFrom;
    }

    public LocalDate getPaiValidUntil() {
        return paiValidUntil;
    }

    public String getPaiKitLocation() {
        return paiKitLocation;
    }

    public String getPaiKitDetails() {
        return paiKitDetails;
    }

    public UUID getPaiDocumentId() {
        return paiDocumentId;
    }

    public String getEmergencyProtocol() {
        return emergencyProtocol;
    }

    public String getDoctorName() {
        return doctorName;
    }

    public String getDoctorPhone() {
        return doctorPhone;
    }

    public String getDoctorAddress() {
        return doctorAddress;
    }

    public String getInstructions() {
        return instructions;
    }

    public LocalDate getVaccinationCheckedOn() {
        return vaccinationCheckedOn;
    }

    public long getVersion() {
        return version;
    }
}
