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

import nc.ticagou.billing.Funder.Category;
import nc.ticagou.billing.Funder.Kind;
import nc.ticagou.billing.InvoiceCalculator.Calculation;
import nc.ticagou.billing.InvoiceCalculator.RateBase;

public final class FunderDtos {

    private FunderDtos() {
    }

    public record FunderDto(UUID id, String name, Kind kind, Category category, Calculation calculation, long amount,
                            int rate, RateBase rateBase, Long monthlyCap, int priority, LocalDate validFrom,
                            LocalDate validTo, String conventionRef, String contact, long beneficiaries,
                            long version) {

        static FunderDto from(Funder f, long beneficiaries) {
            return new FunderDto(f.getId(), f.getName(), f.getKind(), f.getCategory(), f.getCalculation(),
                    f.getAmount(), f.getRate(), f.getRateBase(), f.getMonthlyCap(), f.getPriority(),
                    f.getValidFrom(), f.getValidTo(), f.getConventionRef(), f.getContact(), beneficiaries,
                    f.getVersion());
        }
    }

    public record FunderRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull Kind kind,
            @NotNull Category category,
            @NotNull Calculation calculation,
            @PositiveOrZero long amount,
            @Min(0) @Max(1000) int rate,
            @NotNull RateBase rateBase,
            @PositiveOrZero Long monthlyCap,
            @Min(0) @Max(99) int priority,
            @NotNull LocalDate validFrom,
            LocalDate validTo,
            @Size(max = 80) String conventionRef,
            @Size(max = 200) String contact) {

        @AssertTrue(message = "La fin de validité ne peut pas précéder le début")
        @JsonIgnore
        public boolean isPeriodValid() {
            return validTo == null || validFrom == null || !validTo.isBefore(validFrom);
        }

        @AssertTrue(message = "Indiquez le montant ou le pourcentage de l’aide")
        @JsonIgnore
        public boolean isValueGiven() {
            return kind == Kind.SUBVENTION_STRUCTURE
                    || (calculation == Calculation.MONTANT_FIXE ? amount > 0 : rate > 0);
        }
    }

    public record ChildFundingDto(UUID id, UUID childId, UUID funderId, String funderName, Calculation calculation,
                                  LocalDate fromDay, LocalDate toDay, String beneficiary, String fileNumber,
                                  Long amountOverride, Integer rateOverride, long version) {

        static ChildFundingDto from(ChildFunding c, Funder f) {
            return new ChildFundingDto(c.getId(), c.getChildId(), c.getFunderId(), f.getName(), f.getCalculation(),
                    c.getFromDay(), c.getToDay(), c.getBeneficiary(), c.getFileNumber(), c.getAmountOverride(),
                    c.getRateOverride(), c.getVersion());
        }
    }

    public record ChildFundingRequest(
            @NotNull UUID funderId,
            @NotNull LocalDate fromDay,
            LocalDate toDay,
            @Size(max = 170) String beneficiary,
            @Size(max = 60) String fileNumber,
            @PositiveOrZero Long amountOverride,
            @Min(0) @Max(1000) Integer rateOverride) {

        @AssertTrue(message = "La fin de l’aide ne peut pas précéder son début")
        @JsonIgnore
        public boolean isPeriodValid() {
            return toDay == null || fromDay == null || !toDay.isBefore(fromDay);
        }
    }
}
