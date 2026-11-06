package nc.ticagou.family;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Adulte du foyer : responsable légal ou non, payeur ou non. */
@Entity
@Table(name = "guardian")
public class Guardian {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    /** « Mère », « Père », « Tuteur »… */
    @Column(nullable = false)
    private String relationship;

    @Column(name = "legal_guardian", nullable = false)
    private boolean legalGuardian;

    @Column(nullable = false)
    private boolean payer;

    @Column(name = "mobile_phone")
    private String mobilePhone;

    @Column(name = "work_phone")
    private String workPhone;

    private String email;

    private String employer;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Guardian() {
    }

    public Guardian(UUID structureId, UUID familyId) {
        this.structureId = structureId;
        this.familyId = familyId;
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

    public void update(String firstName, String lastName, String relationship, boolean legalGuardian, boolean payer,
                       String mobilePhone, String workPhone, String email, String employer, int displayOrder) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.relationship = relationship;
        this.legalGuardian = legalGuardian;
        this.payer = payer;
        this.mobilePhone = mobilePhone;
        this.workPhone = workPhone;
        this.email = email;
        this.employer = employer;
        this.displayOrder = displayOrder;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getRelationship() {
        return relationship;
    }

    public boolean isLegalGuardian() {
        return legalGuardian;
    }

    public boolean isPayer() {
        return payer;
    }

    public String getMobilePhone() {
        return mobilePhone;
    }

    public String getWorkPhone() {
        return workPhone;
    }

    public String getEmail() {
        return email;
    }

    public String getEmployer() {
        return employer;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }
}
