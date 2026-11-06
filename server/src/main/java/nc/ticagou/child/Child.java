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

@Entity
@Table(name = "child")
public class Child {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    /** « ENF-2025-014 » : numéro de dossier continu par structure et par année. */
    @Column(name = "file_number", nullable = false, updatable = false)
    private String fileNumber;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "birth_date", nullable = false)
    private LocalDate birthDate;

    @Column(name = "section_id")
    private UUID sectionId;

    @Column(name = "entry_date")
    private LocalDate entryDate;

    @Column(name = "exit_date")
    private LocalDate exitDate;

    @Column(name = "home_languages")
    private String homeLanguages;

    @Column(name = "photo_file_id")
    private UUID photoFileId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Child() {
    }

    public Child(UUID structureId, UUID familyId, String fileNumber) {
        this.structureId = structureId;
        this.familyId = familyId;
        this.fileNumber = fileNumber;
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

    public void update(String firstName, String lastName, LocalDate birthDate, UUID sectionId, LocalDate entryDate,
                       LocalDate exitDate, String homeLanguages) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.birthDate = birthDate;
        this.sectionId = sectionId;
        this.entryDate = entryDate;
        this.exitDate = exitDate;
        this.homeLanguages = homeLanguages;
    }

    public void setPhotoFileId(UUID photoFileId) {
        this.photoFileId = photoFileId;
    }

    /** Inscrit à cette date : pas encore sorti (la date de sortie est le dernier jour d'accueil). */
    public boolean isEnrolledOn(LocalDate day) {
        return exitDate == null || !exitDate.isBefore(day);
    }

    public String displayName() {
        return firstName + " " + lastName;
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

    public String getFileNumber() {
        return fileNumber;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public UUID getSectionId() {
        return sectionId;
    }

    public LocalDate getEntryDate() {
        return entryDate;
    }

    public LocalDate getExitDate() {
        return exitDate;
    }

    public String getHomeLanguages() {
        return homeLanguages;
    }

    public UUID getPhotoFileId() {
        return photoFileId;
    }

    public long getVersion() {
        return version;
    }
}
