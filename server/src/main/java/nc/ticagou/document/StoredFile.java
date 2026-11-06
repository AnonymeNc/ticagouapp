package nc.ticagou.document;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Métadonnées d'un fichier chiffré sur disque ; le contenu n'est jamais en base. */
@Entity
@Table(name = "stored_file")
public class StoredFile {

    @Id
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    /** Chemin relatif au répertoire de stockage ; aucun nom d'origine, aucune donnée personnelle. */
    @Column(name = "storage_path", nullable = false, updatable = false)
    private String storagePath;

    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    /** Empreinte du contenu en clair, contrôlée à la lecture. */
    @Column(nullable = false, updatable = false)
    private String sha256;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected StoredFile() {
    }

    StoredFile(UUID id, UUID structureId, String storagePath, String contentType, long sizeBytes, String sha256,
               Instant createdAt) {
        this.id = id;
        this.structureId = structureId;
        this.storagePath = storagePath;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
