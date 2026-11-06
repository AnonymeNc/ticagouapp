package nc.ticagou.child;

import java.time.Instant;
import java.time.LocalDate;
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

/**
 * Personne autorisée à venir chercher l'enfant, en plus des responsables légaux. La pièce
 * d'identité est vérifiée au premier passage (date et auteur conservés).
 */
@Entity
@Table(name = "authorized_person")
public class AuthorizedPerson {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "child_id", nullable = false, updatable = false)
    private UUID childId;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(nullable = false)
    private String relationship;

    private String phone;

    @Column(name = "id_verified_on")
    private LocalDate idVerifiedOn;

    @Column(name = "id_verified_by")
    private String idVerifiedBy;

    @Column(name = "photo_file_id")
    private UUID photoFileId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected AuthorizedPerson() {
    }

    public AuthorizedPerson(UUID structureId, UUID childId) {
        this.structureId = structureId;
        this.childId = childId;
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

    public void update(String firstName, String lastName, String relationship, String phone) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.relationship = relationship;
        this.phone = phone;
    }

    public void markIdVerified(LocalDate on, String by) {
        this.idVerifiedOn = on;
        this.idVerifiedBy = by;
    }

    public void clearIdVerification() {
        markIdVerified(null, null);
    }

    public void setPhotoFileId(UUID photoFileId) {
        this.photoFileId = photoFileId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public UUID getChildId() {
        return childId;
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

    public String getPhone() {
        return phone;
    }

    public LocalDate getIdVerifiedOn() {
        return idVerifiedOn;
    }

    public String getIdVerifiedBy() {
        return idVerifiedBy;
    }

    public UUID getPhotoFileId() {
        return photoFileId;
    }
}
