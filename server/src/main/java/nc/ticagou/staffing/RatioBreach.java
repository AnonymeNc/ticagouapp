package nc.ticagou.staffing;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Période pendant laquelle l'encadrement d'une section (ou de la structure, section vide) était insuffisant. */
@Entity
@Table(name = "ratio_breach")
public class RatioBreach {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "section_id", updatable = false)
    private UUID sectionId;

    @Column(name = "section_name", updatable = false)
    private String sectionName;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "max_children", nullable = false)
    private int maxChildren;

    @Column(name = "min_adults", nullable = false)
    private int minAdults;

    @Column(name = "max_required", nullable = false)
    private int maxRequired;

    /** Direction prévenue sur son téléphone (ADR 0026) : une seule fois par dépassement. */
    @Column(name = "notified_at")
    private Instant notifiedAt;

    protected RatioBreach() {
    }

    public RatioBreach(UUID structureId, UUID sectionId, String sectionName, Instant startedAt, int children,
                       int adults, int required) {
        this.structureId = structureId;
        this.sectionId = sectionId;
        this.sectionName = sectionName;
        this.startedAt = startedAt;
        this.maxChildren = children;
        this.minAdults = adults;
        this.maxRequired = required;
    }

    public void observe(int children, int adults, int required) {
        maxChildren = Math.max(maxChildren, children);
        minAdults = Math.min(minAdults, adults);
        maxRequired = Math.max(maxRequired, required);
    }

    public void end(Instant at) {
        this.endedAt = at;
    }

    /** Dépassement qui dure depuis au moins {@code delay} et pas encore signalé : on le marque signalé. */
    public boolean dueForNotification(Instant now, java.time.Duration delay) {
        if (notifiedAt != null || startedAt.plus(delay).isAfter(now)) {
            return false;
        }
        notifiedAt = now;
        return true;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSectionId() {
        return sectionId;
    }

    public String getSectionName() {
        return sectionName;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }
}
