package nc.ticagou.staffing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import nc.ticagou.staffing.RatioCalculator.Load;
import nc.ticagou.staffing.RatioCalculator.Status;

public final class StaffingDtos {

    private StaffingDtos() {
    }

    public enum RuleState { EN_VIGUEUR, A_VENIR, EXPIREE }

    public record RatioRuleDto(UUID id, String label, int minAgeMonths, Integer maxAgeMonths, int childrenPerAdult,
                               LocalDate validFrom, LocalDate validTo, RuleState state, long version) {

        static RatioRuleDto from(RatioRule rule, LocalDate today) {
            RuleState state = rule.getValidFrom().isAfter(today) ? RuleState.A_VENIR
                    : rule.getValidTo() != null && rule.getValidTo().isBefore(today) ? RuleState.EXPIREE
                    : RuleState.EN_VIGUEUR;
            return new RatioRuleDto(rule.getId(), rule.getLabel(), rule.getMinAgeMonths(), rule.getMaxAgeMonths(),
                    rule.getChildrenPerAdult(), rule.getValidFrom(), rule.getValidTo(), state, rule.getVersion());
        }
    }

    public record RatioRuleRequest(
            @NotBlank @Size(max = 80) String label,
            @NotNull @Min(0) @Max(120) Integer minAgeMonths,
            @Min(1) @Max(121) Integer maxAgeMonths,
            @NotNull @Min(1) @Max(30) Integer childrenPerAdult,
            @NotNull LocalDate validFrom,
            LocalDate validTo) {

        @AssertTrue(message = "L’âge maximal doit être supérieur à l’âge minimal")
        @JsonIgnore
        public boolean isAgeRangeValid() {
            return maxAgeMonths == null || minAgeMonths == null || maxAgeMonths > minAgeMonths;
        }

        @AssertTrue(message = "La date de fin doit suivre la date de début")
        @JsonIgnore
        public boolean isPeriodValid() {
            return validTo == null || validFrom == null || !validTo.isBefore(validFrom);
        }
    }

    public record SettingsDto(boolean alertDesktop) {
    }

    public record StaffMember(UUID userId, String displayName, String initials) {
    }

    public record LoadDto(int children, int adults, int required, int coveredPlaces, Status status,
                          boolean missingRule) {

        static LoadDto from(Load load) {
            return new LoadDto(load.children(), load.adults(), load.required(), load.coveredPlaces(), load.status(),
                    load.missingRule());
        }
    }

    public record SectionLoadDto(UUID sectionId, String name, String color, LoadDto load, List<StaffMember> staff) {
    }

    /** Instantané du taux d'encadrement, diffusé en temps réel. */
    public record StaffingDto(Instant at, LocalDate day, LoadDto global, List<SectionLoadDto> sections,
                              List<String> rules, boolean alertDesktop) {
    }

    /** Franchissement du seuil : {@code started} vrai à l'ouverture d'un dépassement, faux à sa fin. */
    public record AlertDto(UUID sectionId, String sectionName, boolean started, int children, int adults,
                           int required, Instant at) {
    }
}
