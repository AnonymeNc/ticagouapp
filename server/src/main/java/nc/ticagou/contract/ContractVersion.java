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
import nc.ticagou.funder.TariffCriterion;

/**
 * Version d'un contrat : n° 0 pour le contrat initial, puis un avenant par version. Chaque version porte
 * son planning et le calcul du forfait, figé à l'enregistrement. Une version signée est immuable (trigger en base).
 */
@Entity
@Table(name = "contract_version")
public class ContractVersion {

    public enum Status { BROUILLON, SIGNE }

    public enum PlanningMode { FIXE, VARIABLE }

    /** Planning et paramètres saisis. */
    public record Planning(LocalDate effectiveFrom, String reason, PlanningMode mode, BillingMode billingMode,
                           Collection<ScheduleDay> days,
                           Integer monthlyReservedMinutes, Integer planningNoticeDays, Long familyQuotient) {
    }

    /** Résultat du calcul, recopié dans la version. */
    public record Pricing(UUID tariffScheduleId, TariffCriterion criterion, long bandLowerBound, long hourlyRate,
                          int weeksPerYear, int weeklyMinutes, int monthlyMinutes, long monthlyFee,
                          long registrationFee) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "contract_id", nullable = false, updatable = false)
    private UUID contractId;

    @Column(nullable = false, updatable = false)
    private int number;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "planning_mode", nullable = false)
    private PlanningMode planningMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_mode", nullable = false)
    private BillingMode billingMode = BillingMode.MENSUALISE;

    @Column(name = "monthly_reserved_minutes")
    private Integer monthlyReservedMinutes;

    @Column(name = "planning_notice_days")
    private Integer planningNoticeDays;

    @Column(name = "family_quotient")
    private Long familyQuotient;

    @ElementCollection
    @CollectionTable(name = "contract_schedule_day", joinColumns = @JoinColumn(name = "version_id"))
    @OrderBy("dayOfWeek")
    private List<ScheduleDay> days = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.BROUILLON;

    @Column(name = "signed_on")
    private LocalDate signedOn;

    @Column(name = "signed_document_id")
    private UUID signedDocumentId;

    @Column(name = "tariff_schedule_id", nullable = false)
    private UUID tariffScheduleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tariff_criterion", nullable = false)
    private TariffCriterion tariffCriterion;

    @Column(name = "band_lower_bound", nullable = false)
    private long bandLowerBound;

    @Column(name = "hourly_rate", nullable = false)
    private long hourlyRate;

    @Column(name = "weeks_per_year", nullable = false)
    private int weeksPerYear;

    @Column(name = "weekly_minutes", nullable = false)
    private int weeklyMinutes;

    @Column(name = "monthly_minutes", nullable = false)
    private int monthlyMinutes;

    @Column(name = "monthly_fee", nullable = false)
    private long monthlyFee;

    @Column(name = "registration_fee", nullable = false)
    private long registrationFee;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected ContractVersion() {
    }

    public ContractVersion(UUID structureId, UUID contractId, int number) {
        this.structureId = structureId;
        this.contractId = contractId;
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

    public void update(Planning planning, Pricing pricing) {
        requireDraft();
        this.effectiveFrom = planning.effectiveFrom();
        this.reason = planning.reason();
        this.planningMode = planning.mode();
        this.billingMode = planning.billingMode() == null ? BillingMode.MENSUALISE : planning.billingMode();
        this.days.clear();
        this.days.addAll(planning.days().stream().sorted(Comparator.comparingInt(ScheduleDay::dayOfWeek)).toList());
        this.monthlyReservedMinutes = planning.mode() == PlanningMode.VARIABLE ? planning.monthlyReservedMinutes() : null;
        this.planningNoticeDays = planning.mode() == PlanningMode.VARIABLE ? planning.planningNoticeDays() : null;
        this.familyQuotient = planning.familyQuotient();
        this.tariffScheduleId = pricing.tariffScheduleId();
        this.tariffCriterion = pricing.criterion();
        this.bandLowerBound = pricing.bandLowerBound();
        this.hourlyRate = pricing.hourlyRate();
        this.weeksPerYear = pricing.weeksPerYear();
        this.weeklyMinutes = pricing.weeklyMinutes();
        this.monthlyMinutes = pricing.monthlyMinutes();
        this.monthlyFee = pricing.monthlyFee();
        this.registrationFee = pricing.registrationFee();
    }

    public BillingMode getBillingMode() {
        return billingMode;
    }

    public void sign(LocalDate signedOn, UUID signedDocumentId) {
        requireDraft();
        this.status = Status.SIGNE;
        this.signedOn = signedOn;
        this.signedDocumentId = signedDocumentId;
    }

    public boolean isSigned() {
        return status == Status.SIGNE;
    }

    private void requireDraft() {
        if (isSigned()) {
            throw new IllegalStateException("Version signée : passer par un avenant");
        }
    }

    public Planning planning() {
        return new Planning(effectiveFrom, reason, planningMode, billingMode, List.copyOf(days), monthlyReservedMinutes,
                planningNoticeDays, familyQuotient);
    }

    public UUID getId() {
        return id;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public UUID getContractId() {
        return contractId;
    }

    public int getNumber() {
        return number;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public String getReason() {
        return reason;
    }

    public PlanningMode getPlanningMode() {
        return planningMode;
    }

    public Integer getMonthlyReservedMinutes() {
        return monthlyReservedMinutes;
    }

    public Integer getPlanningNoticeDays() {
        return planningNoticeDays;
    }

    public Long getFamilyQuotient() {
        return familyQuotient;
    }

    public List<ScheduleDay> getDays() {
        return List.copyOf(days);
    }

    public Status getStatus() {
        return status;
    }

    public LocalDate getSignedOn() {
        return signedOn;
    }

    public UUID getSignedDocumentId() {
        return signedDocumentId;
    }

    public UUID getTariffScheduleId() {
        return tariffScheduleId;
    }

    public TariffCriterion getTariffCriterion() {
        return tariffCriterion;
    }

    public long getBandLowerBound() {
        return bandLowerBound;
    }

    public long getHourlyRate() {
        return hourlyRate;
    }

    public int getWeeksPerYear() {
        return weeksPerYear;
    }

    public int getWeeklyMinutes() {
        return weeklyMinutes;
    }

    public int getMonthlyMinutes() {
        return monthlyMinutes;
    }

    public long getMonthlyFee() {
        return monthlyFee;
    }

    public long getRegistrationFee() {
        return registrationFee;
    }
}
