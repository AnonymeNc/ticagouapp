package nc.ticagou.enrollment;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Note libre sur une demande (appel, message laissé…), datée et signée. */
@Entity
@Table(name = "enrollment_note")
public class EnrollmentNote {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(nullable = false, updatable = false)
    private String body;

    @Column(nullable = false, updatable = false)
    private String author;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected EnrollmentNote() {
    }

    public EnrollmentNote(UUID structureId, UUID requestId, String body, String author, Instant createdAt) {
        this.structureId = structureId;
        this.requestId = requestId;
        this.body = body;
        this.author = author;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public String getBody() {
        return body;
    }

    public String getAuthor() {
        return author;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
