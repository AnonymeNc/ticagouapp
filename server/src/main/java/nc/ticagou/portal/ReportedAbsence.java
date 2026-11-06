package nc.ticagou.portal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Absence signalée par un parent sur le portail. Elle s'affiche au tableau de bord et sur la tablette ; la
 * direction la confirme (elle devient une absence de facturation, avec le motif choisi) ou l'écarte.
 */
@Entity
@Table(name = "reported_absence")
public class ReportedAbsence {

    public enum Status { SIGNALEE, CONFIRMEE, ECARTEE }

    @Id
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "portal_ref", nullable = false, updatable = false)
    private UUID portalRef;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Column(name = "child_id", nullable = false, updatable = false)
    private UUID childId;

    @Column(name = "guardian_name", updatable = false)
    private String guardianName;

    @Column(name = "from_day", nullable = false, updatable = false)
    private LocalDate fromDay;

    @Column(name = "to_day", nullable = false, updatable = false)
    private LocalDate toDay;

    @Column(updatable = false)
    private String reason;

    @Column(name = "reported_at", nullable = false, updatable = false)
    private Instant reportedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.SIGNALEE;

    @Column(name = "absence_id")
    private UUID absenceId;

    @Column(name = "handled_by")
    private String handledBy;

    @Column(name = "handled_at")
    private Instant handledAt;

    protected ReportedAbsence() {
    }

    public ReportedAbsence(UUID structureId, UUID portalRef, UUID familyId, UUID childId, String guardianName,
                           LocalDate fromDay, LocalDate toDay, String reason, Instant reportedAt) {
        this.id = UUID.randomUUID();
        this.structureId = structureId;
        this.portalRef = portalRef;
        this.familyId = familyId;
        this.childId = childId;
        this.guardianName = guardianName;
        this.fromDay = fromDay;
        this.toDay = toDay;
        this.reason = reason;
        this.reportedAt = reportedAt;
    }

    public void confirmed(UUID absenceId, String by, Instant at) {
        this.status = Status.CONFIRMEE;
        this.absenceId = absenceId;
        this.handledBy = by;
        this.handledAt = at;
    }

    public void dismissed(String by, Instant at) {
        this.status = Status.ECARTEE;
        this.handledBy = by;
        this.handledAt = at;
    }

    public boolean covers(LocalDate day) {
        return !day.isBefore(fromDay) && !day.isAfter(toDay);
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

    public String getGuardianName() {
        return guardianName;
    }

    public LocalDate getFromDay() {
        return fromDay;
    }

    public LocalDate getToDay() {
        return toDay;
    }

    public String getReason() {
        return reason;
    }

    public Instant getReportedAt() {
        return reportedAt;
    }

    public Status getStatus() {
        return status;
    }

    public UUID getAbsenceId() {
        return absenceId;
    }

    public String getHandledBy() {
        return handledBy;
    }

    public Instant getHandledAt() {
        return handledAt;
    }
}
