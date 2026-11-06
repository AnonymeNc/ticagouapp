package nc.ticagou.billing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import nc.ticagou.billing.InvoiceCalculator.ExtraRateMode;
import nc.ticagou.billing.InvoiceCalculator.Rules;
import nc.ticagou.billing.Xpf.Mode;
import nc.ticagou.contract.BillingMode;

/** Règles de facturation datées : heures complémentaires, dépassements, absences, échéance, arrondi du total. */
@Entity
@Table(name = "billing_rule")
public class BillingRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "extra_rate_mode", nullable = false)
    private ExtraRateMode extraRateMode;

    @Column(name = "extra_hourly_rate", nullable = false)
    private long extraHourlyRate;

    @Column(name = "extra_tolerance_minutes", nullable = false)
    private int extraToleranceMinutes;

    @Column(name = "extra_rounding_minutes", nullable = false)
    private int extraRoundingMinutes;

    @Column(name = "overtime_fee", nullable = false)
    private long overtimeFee;

    @Column(name = "overtime_tolerance_minutes", nullable = false)
    private int overtimeToleranceMinutes;

    @Column(name = "sick_waiting_days", nullable = false)
    private int sickWaitingDays;

    @Column(name = "leave_notice_days", nullable = false)
    private int leaveNoticeDays;

    @Column(name = "payment_days", nullable = false)
    private int paymentDays;

    @Column(name = "total_rounding_step", nullable = false)
    private int totalRoundingStep;

    @Enumerated(EnumType.STRING)
    @Column(name = "total_rounding_mode", nullable = false)
    private Mode totalRoundingMode;

    /** Mode proposé par défaut aux nouveaux contrats et avenants (chaque contrat peut en changer). */
    @Enumerated(EnumType.STRING)
    @Column(name = "default_billing_mode", nullable = false)
    private BillingMode defaultBillingMode = BillingMode.MENSUALISE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected BillingRule() {
    }

    public BillingRule(UUID structureId) {
        this.structureId = structureId;
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

    public void update(LocalDate validFrom, LocalDate validTo, ExtraRateMode extraRateMode, long extraHourlyRate,
                       int extraToleranceMinutes, int extraRoundingMinutes, long overtimeFee,
                       int overtimeToleranceMinutes, int sickWaitingDays, int leaveNoticeDays, int paymentDays,
                       int totalRoundingStep, Mode totalRoundingMode) {
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.extraRateMode = extraRateMode;
        this.extraHourlyRate = extraHourlyRate;
        this.extraToleranceMinutes = extraToleranceMinutes;
        this.extraRoundingMinutes = extraRoundingMinutes;
        this.overtimeFee = overtimeFee;
        this.overtimeToleranceMinutes = overtimeToleranceMinutes;
        this.sickWaitingDays = sickWaitingDays;
        this.leaveNoticeDays = leaveNoticeDays;
        this.paymentDays = paymentDays;
        this.totalRoundingStep = totalRoundingStep;
        this.totalRoundingMode = totalRoundingMode;
    }

    public void setDefaultBillingMode(BillingMode mode) {
        this.defaultBillingMode = mode == null ? BillingMode.MENSUALISE : mode;
    }

    public BillingMode getDefaultBillingMode() {
        return defaultBillingMode;
    }

    public boolean isValidOn(LocalDate day) {
        return !day.isBefore(validFrom) && (validTo == null || !day.isAfter(validTo));
    }

    public Rules toRules() {
        return new Rules(extraRateMode, extraHourlyRate, extraToleranceMinutes, extraRoundingMinutes, overtimeFee,
                overtimeToleranceMinutes, sickWaitingDays, leaveNoticeDays, totalRoundingStep, totalRoundingMode);
    }

    public UUID getId() {
        return id;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public ExtraRateMode getExtraRateMode() {
        return extraRateMode;
    }

    public long getExtraHourlyRate() {
        return extraHourlyRate;
    }

    public int getExtraToleranceMinutes() {
        return extraToleranceMinutes;
    }

    public int getExtraRoundingMinutes() {
        return extraRoundingMinutes;
    }

    public long getOvertimeFee() {
        return overtimeFee;
    }

    public int getOvertimeToleranceMinutes() {
        return overtimeToleranceMinutes;
    }

    public int getSickWaitingDays() {
        return sickWaitingDays;
    }

    public int getLeaveNoticeDays() {
        return leaveNoticeDays;
    }

    public int getPaymentDays() {
        return paymentDays;
    }

    public int getTotalRoundingStep() {
        return totalRoundingStep;
    }

    public Mode getTotalRoundingMode() {
        return totalRoundingMode;
    }

    public long getVersion() {
        return version;
    }
}
