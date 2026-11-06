package nc.ticagou.staff;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Fiche du personnel, une par compte : identité, contrat de travail (données personnelles, direction). */
@Entity
@Table(name = "staff_profile")
public class StaffProfile {

    public enum ContractType { CDI, CDD, REMPLACEMENT, APPRENTISSAGE, VACATAIRE }

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    private String phone;

    private String email;

    private String address;

    @Column(name = "cafat_number")
    private String cafatNumber;

    @Column(name = "emergency_contact")
    private String emergencyContact;

    private String diploma;

    @Enumerated(EnumType.STRING)
    @Column(name = "contract_type", nullable = false)
    private ContractType contractType = ContractType.CDI;

    /** Nul : rémunération à l'heure, sans durée hebdomadaire. */
    @Column(name = "weekly_minutes")
    private Integer weeklyMinutes;

    @Column(name = "hired_on")
    private LocalDate hiredOn;

    @Column(name = "ends_on")
    private LocalDate endsOn;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected StaffProfile() {
    }

    public StaffProfile(UUID userId, UUID structureId) {
        this.userId = userId;
        this.structureId = structureId;
        this.weeklyMinutes = 39 * 60;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void update(LocalDate birthDate, String phone, String email, String address, String cafatNumber,
                       String emergencyContact, String diploma, ContractType contractType, Integer weeklyMinutes,
                       LocalDate hiredOn, LocalDate endsOn) {
        this.birthDate = birthDate;
        this.phone = phone;
        this.email = email;
        this.address = address;
        this.cafatNumber = cafatNumber;
        this.emergencyContact = emergencyContact;
        this.diploma = diploma;
        this.contractType = contractType;
        this.weeklyMinutes = weeklyMinutes;
        this.hiredOn = hiredOn;
        this.endsOn = endsOn;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public String getPhone() {
        return phone;
    }

    public String getEmail() {
        return email;
    }

    public String getAddress() {
        return address;
    }

    public String getCafatNumber() {
        return cafatNumber;
    }

    public String getEmergencyContact() {
        return emergencyContact;
    }

    public String getDiploma() {
        return diploma;
    }

    public ContractType getContractType() {
        return contractType;
    }

    public Integer getWeeklyMinutes() {
        return weeklyMinutes;
    }

    public LocalDate getHiredOn() {
        return hiredOn;
    }

    public LocalDate getEndsOn() {
        return endsOn;
    }

    public long getVersion() {
        return version;
    }
}
