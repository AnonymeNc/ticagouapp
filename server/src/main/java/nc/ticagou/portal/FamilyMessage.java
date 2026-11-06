package nc.ticagou.portal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Message du fil d'une famille : écrit par l'équipe au bureau, ou par un parent sur le portail. */
@Entity
@Table(name = "family_message")
public class FamilyMessage {

    public enum Direction { FAMILLE, EQUIPE }

    @Id
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Direction direction;

    @Column(name = "author_name", nullable = false, updatable = false)
    private String authorName;

    @Column(name = "author_user_id", updatable = false)
    private UUID authorUserId;

    @Column(nullable = false, updatable = false)
    private String body;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "portal_ref", updatable = false)
    private UUID portalRef;

    protected FamilyMessage() {
    }

    public static FamilyMessage fromTeam(UUID structureId, UUID familyId, UUID userId, String authorName, String body,
                                         Instant at) {
        var message = new FamilyMessage();
        message.id = UUID.randomUUID();
        message.structureId = structureId;
        message.familyId = familyId;
        message.direction = Direction.EQUIPE;
        message.authorUserId = userId;
        message.authorName = authorName;
        message.body = body;
        message.sentAt = at;
        message.readAt = at;
        return message;
    }

    public static FamilyMessage fromFamily(UUID structureId, UUID familyId, UUID portalRef, String authorName,
                                           String body, Instant at) {
        var message = new FamilyMessage();
        message.id = UUID.randomUUID();
        message.structureId = structureId;
        message.familyId = familyId;
        message.direction = Direction.FAMILLE;
        message.portalRef = portalRef;
        message.authorName = authorName;
        message.body = body;
        message.sentAt = at;
        return message;
    }

    public void read(Instant at) {
        if (readAt == null) {
            readAt = at;
        }
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

    public Direction getDirection() {
        return direction;
    }

    public String getAuthorName() {
        return authorName;
    }

    public String getBody() {
        return body;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getReadAt() {
        return readAt;
    }
}
