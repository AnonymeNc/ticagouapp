package nc.ticagou.portal;

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

/** Élément de la file d'envoi au portail : seulement un type et un identifiant, le contenu est construit à l'envoi. */
@Entity
@Table(name = "portal_outbox")
public class PortalOutboxItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long seq;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PortalItemType type;

    @Column(name = "entity_id", nullable = false, updatable = false)
    private UUID entityId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected PortalOutboxItem() {
    }

    public PortalOutboxItem(UUID structureId, PortalItemType type, UUID entityId, Instant now) {
        this.structureId = structureId;
        this.type = type;
        this.entityId = entityId;
        this.createdAt = now;
    }

    public void sent(Instant at) {
        this.sentAt = at;
    }

    public Long getSeq() {
        return seq;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public PortalItemType getType() {
        return type;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
