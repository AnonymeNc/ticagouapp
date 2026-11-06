package nc.ticagou.child;

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

/** Document joint au dossier d'un enfant ; contenu chiffré dans {@code stored_file}. */
@Entity
@Table(name = "child_document")
public class ChildDocument {

    public enum Category { CONTRAT, SANTE, AUTORISATIONS, FAMILLE, AUTRE }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "child_id", nullable = false, updatable = false)
    private UUID childId;

    @Column(name = "file_id", nullable = false, updatable = false)
    private UUID fileId;

    @Column(nullable = false, updatable = false)
    private String name;

    /** Nom du fichier d'origine, proposé au téléchargement. */
    @Column(name = "file_name", nullable = false, updatable = false)
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Category category;

    @Column(name = "valid_until", updatable = false)
    private LocalDate validUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    protected ChildDocument() {
    }

    public ChildDocument(UUID structureId, UUID childId, UUID fileId, String name, String fileName, Category category,
                         LocalDate validUntil, Instant createdAt, String createdBy) {
        this.structureId = structureId;
        this.childId = childId;
        this.fileId = fileId;
        this.name = name;
        this.fileName = fileName;
        this.category = category;
        this.validUntil = validUntil;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
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

    public UUID getFileId() {
        return fileId;
    }

    public String getName() {
        return name;
    }

    public String getFileName() {
        return fileName;
    }

    public Category getCategory() {
        return category;
    }

    public LocalDate getValidUntil() {
        return validUntil;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }
}
