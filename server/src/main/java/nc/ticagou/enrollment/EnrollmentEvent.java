package nc.ticagou.enrollment;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Ligne de l'historique d'une demande (« Passée à « À recontacter » », « Visite fixée au … »). */
@Entity
@Table(name = "enrollment_event")
public class EnrollmentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(nullable = false, updatable = false)
    private String label;

    @Column(nullable = false, updatable = false)
    private String author;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected EnrollmentEvent() {
    }

    public EnrollmentEvent(UUID structureId, UUID requestId, String label, String author, Instant createdAt) {
        this.structureId = structureId;
        this.requestId = requestId;
        this.label = label;
        this.author = author;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public String getLabel() {
        return label;
    }

    public String getAuthor() {
        return author;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
