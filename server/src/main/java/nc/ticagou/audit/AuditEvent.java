package nc.ticagou.audit;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Événement du journal d'audit. Ajout seul (trigger en base). */
@Entity
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "structure_id", updatable = false)
    private UUID structureId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "actor_name", updatable = false)
    private String actorName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AuditAction action;

    @Column(name = "target_type", updatable = false)
    private String targetType;

    @Column(name = "target_id", updatable = false)
    private String targetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private String details;

    @Column(name = "client_ip", updatable = false)
    private String clientIp;

    protected AuditEvent() {
    }

    AuditEvent(UUID structureId, Instant occurredAt, UUID actorUserId, String actorName, AuditAction action,
               String targetType, String targetId, String details, String clientIp) {
        this.structureId = structureId;
        this.occurredAt = occurredAt;
        this.actorUserId = actorUserId;
        this.actorName = actorName;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.details = details;
        this.clientIp = clientIp;
    }

    public Long getId() {
        return id;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public String getActorName() {
        return actorName;
    }

    public AuditAction getAction() {
        return action;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getDetails() {
        return details;
    }

    public String getClientIp() {
        return clientIp;
    }
}
