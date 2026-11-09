package nc.ticagou.portal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Demande d'un parent depuis le portail (lot 2) : un accueil occasionnel à accepter ou refuser, ou un retard annoncé
 * (« j'arrive à 17:45 »), simplement affiché à l'équipe. L'identifiant est celui de l'élément du portail.
 */
@Entity
@Table(name = "booking_request")
public class BookingRequest {

    public enum Kind { RESERVATION, RETARD }

    public enum Status { DEMANDEE, ACCEPTEE, REFUSEE, ANNULEE }

    @Id
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Column(name = "child_id", nullable = false, updatable = false)
    private UUID childId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Kind kind;

    @Column(nullable = false, updatable = false)
    private LocalDate day;

    @Column(name = "arrives_at", updatable = false)
    private LocalTime arrivesAt;

    @Column(name = "leaves_at", nullable = false, updatable = false)
    private LocalTime leavesAt;

    @Column(updatable = false)
    private String note;

    @Column(name = "guardian_name", updatable = false)
    private String guardianName;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private String answer;

    @Column(name = "over_capacity", nullable = false)
    private boolean overCapacity;

    @Column(name = "handled_by")
    private String handledBy;

    @Column(name = "handled_at")
    private Instant handledAt;

    protected BookingRequest() {
    }

    public BookingRequest(UUID id, UUID structureId, UUID familyId, UUID childId, Kind kind, LocalDate day,
                          LocalTime arrivesAt, LocalTime leavesAt, String note, String guardianName, Instant requestedAt) {
        this.id = id;
        this.structureId = structureId;
        this.familyId = familyId;
        this.childId = childId;
        this.kind = kind;
        this.day = day;
        this.arrivesAt = arrivesAt;
        this.leavesAt = leavesAt;
        this.note = note;
        this.guardianName = guardianName;
        this.requestedAt = requestedAt;
        // Un retard n'attend pas de réponse : il est affiché à l'équipe.
        this.status = kind == Kind.RETARD ? Status.ACCEPTEE : Status.DEMANDEE;
    }

    public void answer(Status status, String answer, boolean overCapacity, String by, Instant at) {
        this.status = status;
        this.answer = answer;
        this.overCapacity = overCapacity;
        this.handledBy = by;
        this.handledAt = at;
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

    public UUID getChildId() {
        return childId;
    }

    public Kind getKind() {
        return kind;
    }

    public LocalDate getDay() {
        return day;
    }

    public LocalTime getArrivesAt() {
        return arrivesAt;
    }

    public LocalTime getLeavesAt() {
        return leavesAt;
    }

    public String getNote() {
        return note;
    }

    public String getGuardianName() {
        return guardianName;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Status getStatus() {
        return status;
    }

    public String getAnswer() {
        return answer;
    }

    public boolean isOverCapacity() {
        return overCapacity;
    }

    public String getHandledBy() {
        return handledBy;
    }

    public Instant getHandledAt() {
        return handledAt;
    }
}
