package nc.ticagou.attendance;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Opération reçue d'une tablette, gardée pour l'idempotence : un lot renvoyé après une coupure ne crée pas
 * de doublon. Le contenu n'est pas stocké (il peut contenir un code PIN), seulement le résultat.
 */
@Entity
@Table(name = "kiosk_operation")
public class KioskOperation {

    public enum Outcome { APPLIQUEE, DEJA_RECUE, REFUSEE }

    @Id
    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "device_id", nullable = false, updatable = false)
    private UUID deviceId;

    @Column(nullable = false)
    private String type;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "child_id")
    private UUID childId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Outcome outcome;

    private String code;

    private String message;

    protected KioskOperation() {
    }

    public KioskOperation(UUID clientId, UUID structureId, UUID deviceId, String type, UUID userId, UUID childId,
                          Instant occurredAt, Instant receivedAt, Outcome outcome, String code, String message) {
        this.clientId = clientId;
        this.structureId = structureId;
        this.deviceId = deviceId;
        this.type = type;
        this.userId = userId;
        this.childId = childId;
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
        this.outcome = outcome;
        this.code = code;
        this.message = message == null || message.length() <= 300 ? message : message.substring(0, 300);
    }

    public UUID getClientId() {
        return clientId;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public String getType() {
        return type;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getChildId() {
        return childId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
