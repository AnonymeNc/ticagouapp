package nc.ticagou.contract;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import nc.ticagou.contract.ForfaitCalculator.AdaptationBilling;

/** Contrat d'accueil d'un enfant : période, adaptation ; le planning et le forfait sont portés par ses versions. */
@Entity
@Table(name = "contract")
public class Contract {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "child_id", nullable = false, updatable = false)
    private UUID childId;

    @Column(name = "request_id", updatable = false)
    private UUID requestId;

    /** « 2027-014 » : numérotation continue par structure et par année de début. */
    @Column(nullable = false, updatable = false)
    private String number;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "notice_months", nullable = false)
    private int noticeMonths;

    @Enumerated(EnumType.STRING)
    @Column(name = "adaptation_billing", nullable = false)
    private AdaptationBilling adaptationBilling;

    @Column(name = "adaptation_rate", nullable = false)
    private long adaptationRate;

    @Column(name = "adaptation_cost", nullable = false)
    private long adaptationCost;

    @ElementCollection
    @CollectionTable(name = "adaptation_session", joinColumns = @JoinColumn(name = "contract_id"))
    @OrderBy("sessionDate")
    private List<AdaptationSession> adaptationSessions = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Contract() {
    }

    public Contract(UUID structureId, UUID childId, UUID requestId, String number) {
        this.structureId = structureId;
        this.childId = childId;
        this.requestId = requestId;
        this.number = number;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void update(LocalDate startDate, LocalDate endDate, int noticeMonths, AdaptationBilling billing,
                       long adaptationRate, long adaptationCost, Collection<AdaptationSession> sessions) {
        this.startDate = startDate;
        this.endDate = endDate;
        this.noticeMonths = noticeMonths;
        this.adaptationBilling = billing;
        this.adaptationRate = adaptationRate;
        this.adaptationCost = adaptationCost;
        this.adaptationSessions.clear();
        this.adaptationSessions.addAll(sessions.stream().sorted(Comparator.comparing(AdaptationSession::sessionDate)).toList());
    }

    public boolean covers(LocalDate day) {
        return !startDate.isAfter(day) && !endDate.isBefore(day);
    }

    public boolean overlaps(LocalDate from, LocalDate to) {
        return !endDate.isBefore(from) && !to.isBefore(startDate);
    }

    public UUID getId() {
        return id;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public UUID getChildId() {
        return childId;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public String getNumber() {
        return number;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public int getNoticeMonths() {
        return noticeMonths;
    }

    public AdaptationBilling getAdaptationBilling() {
        return adaptationBilling;
    }

    public long getAdaptationRate() {
        return adaptationRate;
    }

    public long getAdaptationCost() {
        return adaptationCost;
    }

    public List<AdaptationSession> getAdaptationSessions() {
        return List.copyOf(adaptationSessions);
    }

    public long getVersion() {
        return version;
    }
}
