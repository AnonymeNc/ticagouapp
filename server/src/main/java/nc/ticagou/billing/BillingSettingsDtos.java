package nc.ticagou.billing;

import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import nc.ticagou.contract.BillingMode;
import nc.ticagou.billing.InvoiceCalculator.ExtraRateMode;
import nc.ticagou.billing.InvoiceCalculator.Service;
import nc.ticagou.billing.Xpf.Mode;

/** Paramètres › Barème et facturation : règles de facturation, motifs d'absence, taux de TGC. */
public final class BillingSettingsDtos {

    private BillingSettingsDtos() {
    }

    public record BillingRuleDto(UUID id, LocalDate validFrom, LocalDate validTo, ExtraRateMode extraRateMode,
                                 long extraHourlyRate, int extraToleranceMinutes, int extraRoundingMinutes,
                                 long overtimeFee, int overtimeToleranceMinutes, int sickWaitingDays,
                                 int leaveNoticeDays, int paymentDays, int totalRoundingStep, Mode totalRoundingMode,
                                 BillingMode defaultBillingMode,
                                 long version) {

        static BillingRuleDto from(BillingRule r) {
            return new BillingRuleDto(r.getId(), r.getValidFrom(), r.getValidTo(), r.getExtraRateMode(),
                    r.getExtraHourlyRate(), r.getExtraToleranceMinutes(), r.getExtraRoundingMinutes(),
                    r.getOvertimeFee(), r.getOvertimeToleranceMinutes(), r.getSickWaitingDays(),
                    r.getLeaveNoticeDays(), r.getPaymentDays(), r.getTotalRoundingStep(), r.getTotalRoundingMode(),
                    r.getDefaultBillingMode(),
                    r.getVersion());
        }
    }

    public record BillingRuleRequest(
            @NotNull LocalDate validFrom,
            LocalDate validTo,
            @NotNull ExtraRateMode extraRateMode,
            @PositiveOrZero long extraHourlyRate,
            @Min(0) @Max(60) int extraToleranceMinutes,
            int extraRoundingMinutes,
            @PositiveOrZero long overtimeFee,
            @Min(0) @Max(60) int overtimeToleranceMinutes,
            @Min(0) @Max(10) int sickWaitingDays,
            @Min(0) @Max(90) int leaveNoticeDays,
            @Min(0) @Max(60) int paymentDays,
            int totalRoundingStep,
            @NotNull Mode totalRoundingMode,
            /** Mode proposé aux nouveaux contrats ; nul = mensualisé. */
            BillingMode defaultBillingMode) {

        @AssertTrue(message = "La fin de validité ne peut pas précéder le début")
        @JsonIgnore
        public boolean isPeriodValid() {
            return validTo == null || validFrom == null || !validTo.isBefore(validFrom);
        }

        @AssertTrue(message = "Arrondi du temps : 1, 5, 15, 30 ou 60 minutes")
        @JsonIgnore
        public boolean isRoundingValid() {
            return extraRoundingMinutes == 1 || extraRoundingMinutes == 5 || extraRoundingMinutes == 15
                    || extraRoundingMinutes == 30 || extraRoundingMinutes == 60;
        }

        @AssertTrue(message = "Arrondi du total : 1, 10 ou 100 F")
        @JsonIgnore
        public boolean isTotalStepValid() {
            return totalRoundingStep == 1 || totalRoundingStep == 10 || totalRoundingStep == 100;
        }

        @AssertTrue(message = "Indiquez le tarif des heures complémentaires")
        @JsonIgnore
        public boolean isFixedRateGiven() {
            return extraRateMode != ExtraRateMode.FIXE || extraHourlyRate > 0;
        }
    }

    public record AbsenceReasonDto(UUID id, String label, boolean deductible, boolean proofRequired,
                                   boolean waitingDaysApply, boolean noticeApplies, int displayOrder, boolean active,
                                   boolean used, long version) {

        static AbsenceReasonDto from(AbsenceReason r, boolean used) {
            return new AbsenceReasonDto(r.getId(), r.getLabel(), r.isDeductible(), r.isProofRequired(),
                    r.isWaitingDaysApply(), r.isNoticeApplies(), r.getDisplayOrder(), r.isActive(), used,
                    r.getVersion());
        }
    }

    public record AbsenceReasonRequest(
            @NotBlank @Size(max = 80) String label,
            boolean deductible,
            boolean proofRequired,
            boolean waitingDaysApply,
            boolean noticeApplies,
            @Min(0) @Max(99) int displayOrder,
            boolean active) {
    }

    public record TgcRateDto(UUID id, Service service, int rate, boolean exempt, LocalDate validFrom,
                             LocalDate validTo, long version) {

        static TgcRateDto from(TgcRate r) {
            return new TgcRateDto(r.getId(), r.getService(), r.getRate(), r.isExempt(), r.getValidFrom(),
                    r.getValidTo(), r.getVersion());
        }
    }

    /** Taux en dixièmes de pour cent : 60 = 6 %. */
    public record TgcRateRequest(
            @NotNull Service service,
            @Min(0) @Max(1000) int rate,
            boolean exempt,
            @NotNull LocalDate validFrom,
            LocalDate validTo) {

        @AssertTrue(message = "La fin de validité ne peut pas précéder le début")
        @JsonIgnore
        public boolean isPeriodValid() {
            return validTo == null || validFrom == null || !validTo.isBefore(validFrom);
        }
    }
}
