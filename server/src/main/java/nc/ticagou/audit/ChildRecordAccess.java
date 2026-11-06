package nc.ticagou.audit;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Trace de consultation d'un dossier enfant. Ajout seul (trigger en base). */
@Entity
@Table(name = "child_record_access")
public class ChildRecordAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "child_id", nullable = false, updatable = false)
    private UUID childId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "user_name", nullable = false, updatable = false)
    private String userName;

    @Column(name = "accessed_at", nullable = false, updatable = false)
    private Instant accessedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ChildAccessContext context;

    @Column(name = "client_ip", updatable = false)
    private String clientIp;

    protected ChildRecordAccess() {
    }

    ChildRecordAccess(UUID structureId, UUID childId, UUID userId, String userName, Instant accessedAt,
                      ChildAccessContext context, String clientIp) {
        this.structureId = structureId;
        this.childId = childId;
        this.userId = userId;
        this.userName = userName;
        this.accessedAt = accessedAt;
        this.context = context;
        this.clientIp = clientIp;
    }

    public Long getId() {
        return id;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public UUID getChildId() {
        return childId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getUserName() {
        return userName;
    }

    public Instant getAccessedAt() {
        return accessedAt;
    }

    public ChildAccessContext getContext() {
        return context;
    }

    public String getClientIp() {
        return clientIp;
    }
}
