package nc.ticagou.funder;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class TariffDtos {

    private TariffDtos() {
    }

    public record BandDto(@PositiveOrZero long lowerBound, @Positive long hourlyRate) {

        static BandDto from(TariffBand b) {
            return new BandDto(b.lowerBound(), b.hourlyRate());
        }
    }

    public record TariffScheduleDto(UUID id, String label, TariffCriterion criterion, int weeksPerYear,
                                    long registrationFee, LocalDate validFrom, LocalDate validTo,
                                    List<BandDto> bands, boolean used, long version) {

        static TariffScheduleDto from(TariffSchedule s, boolean used) {
            return new TariffScheduleDto(s.getId(), s.getLabel(), s.getCriterion(), s.getWeeksPerYear(),
                    s.getRegistrationFee(), s.getValidFrom(), s.getValidTo(),
                    s.getBands().stream().map(BandDto::from).toList(), used, s.getVersion());
        }
    }

    public record TariffScheduleRequest(
            @NotBlank @Size(max = 80) String label,
            @NotNull TariffCriterion criterion,
            @Min(1) @Max(52) int weeksPerYear,
            @PositiveOrZero long registrationFee,
            @NotNull LocalDate validFrom,
            LocalDate validTo,
            @NotNull @Size(min = 1, max = 12) List<@Valid BandDto> bands) {

        @AssertTrue(message = "La fin de validité ne peut pas précéder le début")
        @JsonIgnore
        public boolean isPeriodValid() {
            return validTo == null || validFrom == null || !validTo.isBefore(validFrom);
        }

        @AssertTrue(message = "La première tranche doit commencer à 0")
        @JsonIgnore
        public boolean isFirstBandAtZero() {
            return bands == null || bands.stream().anyMatch(b -> b != null && b.lowerBound() == 0);
        }

        @AssertTrue(message = "Deux tranches ne peuvent pas commencer à la même valeur")
        @JsonIgnore
        public boolean isBandsDistinct() {
            return bands == null || bands.stream().map(BandDto::lowerBound).distinct().count() == bands.size();
        }
    }

    public record DuplicateRequest(@NotBlank @Size(max = 80) String label, @NotNull LocalDate validFrom) {
    }
}
