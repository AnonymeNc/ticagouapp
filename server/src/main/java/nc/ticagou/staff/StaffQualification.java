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

/** Qualification d'une personne (diplôme, SST, HACCP, aptitude), avec sa validité et son justificatif chiffré. */
@Entity
@Table(name = "staff_qualification")
public class StaffQualification {

    public enum Kind { DIPLOME, SST, HACCP, APTITUDE, AUTRE }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Column(nullable = false)
    private String label;

    @Column(name = "obtained_on")
    private LocalDate obtainedOn;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "file_id")
    private UUID fileId;

    @Column(name = "file_name")
    private String fileName;

    @Column(nullable = false)
    private boolean verified;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected StaffQualification() {
    }

    public StaffQualification(UUID structureId, UUID userId, Instant createdAt) {
        this.structureId = structureId;
        this.userId = userId;
        this.createdAt = createdAt;
    }

    public void update(Kind kind, String label, LocalDate obtainedOn, LocalDate validUntil, boolean verified) {
        this.kind = kind;
        this.label = label;
        this.obtainedOn = obtainedOn;
        this.validUntil = validUntil;
        this.verified = verified;
    }

    public void attach(UUID fileId, String fileName) {
        this.fileId = fileId;
        this.fileName = fileName;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public UUID getUserId() {
        return userId;
    }

    public Kind getKind() {
        return kind;
    }

    public String getLabel() {
        return label;
    }

    public LocalDate getObtainedOn() {
        return obtainedOn;
    }

    public LocalDate getValidUntil() {
        return validUntil;
    }

    public UUID getFileId() {
        return fileId;
    }

    public String getFileName() {
        return fileName;
    }

    public boolean isVerified() {
        return verified;
    }

    public long getVersion() {
        return version;
    }
}
