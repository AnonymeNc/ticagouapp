package nc.ticagou.contract;

import java.time.LocalDate;
import java.time.LocalTime;
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
import nc.ticagou.child.ChildDtos.CreateChildRequest;
import nc.ticagou.contract.ContractVersion.PlanningMode;
import nc.ticagou.contract.ContractVersion.Status;
import nc.ticagou.contract.ForfaitCalculator.AdaptationBilling;
import nc.ticagou.funder.TariffCriterion;

public final class ContractDtos {

    private ContractDtos() {
    }

    // --- Saisie -------------------------------------------------------------------------------------

    public record DayInput(@Min(1) @Max(7) int dayOfWeek, @NotNull LocalTime arrivesAt, @NotNull LocalTime leavesAt) {

        ScheduleDay toDay() {
            return new ScheduleDay(dayOfWeek, arrivesAt, leavesAt);
        }
    }

    public record SessionInput(@NotNull LocalDate date, @NotNull LocalTime startsAt, @NotNull LocalTime endsAt,
                               @Size(max = 80) String note) {

        AdaptationSession toSession() {
            return new AdaptationSession(date, startsAt, endsAt, note == null || note.isBlank() ? null : note.trim());
        }
    }

    /**
     * Planning type (fixe) ou volume d'heures réservé par mois (variable, la famille transmet son planning
     * {@code planningNoticeDays} jours avant chaque mois). Le quotient familial n'est utile qu'aux barèmes qui en dépendent.
     */
    public record PlanningInput(
            @NotNull PlanningMode mode,
            @NotNull @Size(max = 7) List<@Valid DayInput> days,
            @Positive @Max(300 * 60) Integer monthlyReservedMinutes,
            @Positive @Max(60) Integer planningNoticeDays,
            @PositiveOrZero Long familyQuotient,
            /** Mensualisé ou au réel (planning fixe) ; nul = mode par défaut des règles de facturation. */
            BillingMode billingMode,
            /** Formule de facturation du barème (libellé) à la place du calcul à l'heure ; planning fixe seulement. */
            @Size(max = 80) String formula) {

        @AssertTrue(message = "Indiquez au moins un jour d’accueil")
        @JsonIgnore
        public boolean isFixedPlanningFilled() {
            return mode != PlanningMode.FIXE || days == null || !days.isEmpty();
        }

        @AssertTrue(message = "Indiquez le volume d’heures réservé par mois")
        @JsonIgnore
        public boolean isVariableVolumeFilled() {
            return mode != PlanningMode.VARIABLE || monthlyReservedMinutes != null;
        }

        @AssertTrue(message = "Une formule de facturation s’applique à un planning fixe")
        @JsonIgnore
        public boolean isFormulaWithFixedPlanning() {
            return formula == null || formula.isBlank() || mode != PlanningMode.VARIABLE;
        }

        @AssertTrue(message = "Un jour ne peut figurer qu’une fois dans le planning")
        @JsonIgnore
        public boolean isDaysDistinct() {
            return days == null || days.stream().map(DayInput::dayOfWeek).distinct().count() == days.size();
        }

        List<ScheduleDay> scheduleDays() {
            return mode == PlanningMode.FIXE ? days.stream().map(DayInput::toDay).toList() : List.of();
        }

        /** Libellé de la formule choisie, nul pour le calcul à l'heure. */
        String formulaLabel() {
            return formula == null || formula.isBlank() ? null : formula.strip();
        }
    }

    public record ContractDraft(
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @Min(0) @Max(3) int noticeMonths,
            @NotNull @Valid PlanningInput planning,
            @NotNull AdaptationBilling adaptationBilling,
            @NotNull @Size(max = 20) List<@Valid SessionInput> adaptation) {

        @AssertTrue(message = "La fin du contrat doit suivre son début")
        @JsonIgnore
        public boolean isPeriodValid() {
            return startDate == null || endDate == null || endDate.isAfter(startDate);
        }

        @AssertTrue(message = "Les séances d’adaptation ont lieu avant le début du contrat")
        @JsonIgnore
        public boolean isAdaptationBeforeStart() {
            return adaptation == null || startDate == null
                    || adaptation.stream().allMatch(s -> s == null || s.date() == null || s.date().isBefore(startDate));
        }

        @AssertTrue(message = "Une seule séance d’adaptation par jour")
        @JsonIgnore
        public boolean isAdaptationDistinct() {
            return adaptation == null || adaptation.stream().map(SessionInput::date).distinct().count() == adaptation.size();
        }
    }

    /**
     * Nouveau contrat, pour un enfant existant ({@code childId}) ou un enfant créé à partir d'une demande
     * ({@code newChild}, sa date d'entrée devient le début du contrat).
     */
    public record CreateContractRequest(
            UUID childId,
            UUID requestId,
            @Valid CreateChildRequest newChild,
            @NotNull @Valid ContractDraft contract) {

        @AssertTrue(message = "Choisissez l’enfant ou créez sa fiche")
        @JsonIgnore
        public boolean isChildChosen() {
            return (childId == null) != (newChild == null);
        }
    }

    public record AmendmentRequest(
            @NotNull LocalDate effectiveFrom,
            @NotBlank @Size(max = 200) String reason,
            @NotNull @Valid PlanningInput planning) {
    }

    /** Aperçu du calcul pendant la saisie, sans rien enregistrer. */
    public record QuoteRequest(
            @NotNull LocalDate effectiveFrom,
            @NotNull @Valid PlanningInput planning,
            AdaptationBilling adaptationBilling,
            @Size(max = 20) List<@Valid SessionInput> adaptation) {
    }

    // --- Lecture ------------------------------------------------------------------------------------

    public record BandDto(long lowerBound, long hourlyRate) {
    }

    /** Formule proposée par le barème applicable. */
    public record FormulaDto(String label, long monthlyFee) {
    }

    /** {@code problems} : incohérences du planning (jour fermé, départ avant l'arrivée…), à corriger avant d'enregistrer. 
     * {@code formula} : formule retenue (nulle : calcul à l'heure) ; {@code hourlyFee} : forfait calculé à l'heure, pour le
     * comparer aux {@code formulas} du barème.
     */
    public record QuoteDto(UUID tariffScheduleId, String tariffLabel, TariffCriterion criterion, int weeksPerYear,
                           List<BandDto> bands, long bandLowerBound, long hourlyRate, int weeklyMinutes,
                           int monthlyMinutes, long monthlyFee, long registrationFee, int adaptationMinutes,
                           long adaptationRate, long adaptationCost, BillingMode billingMode, String formula, long hourlyFee,
                           List<FormulaDto> formulas, List<String> problems) {
    }

    public record DayDto(int dayOfWeek, LocalTime arrivesAt, LocalTime leavesAt) {

        static DayDto from(ScheduleDay d) {
            return new DayDto(d.dayOfWeek(), d.arrivesAt(), d.leavesAt());
        }
    }

    public record SessionDto(LocalDate date, LocalTime startsAt, LocalTime endsAt, String note) {

        static SessionDto from(AdaptationSession s) {
            return new SessionDto(s.sessionDate(), s.startsAt(), s.endsAt(), s.note());
        }
    }

    /**
     * @param inForce  version appliquée aujourd'hui (ou, avant le début, celle qui s'appliquera)
     * @param replaced version signée remplacée par un avenant signé
     */
    public record VersionDto(int number, LocalDate effectiveFrom, String reason, PlanningMode planningMode,
                             BillingMode billingMode,
                             List<DayDto> days, Integer monthlyReservedMinutes, Integer planningNoticeDays,
                             Long familyQuotient, Status status, LocalDate signedOn, UUID signedDocumentId,
                             UUID tariffScheduleId, TariffCriterion criterion, long bandLowerBound, long hourlyRate,
                             int weeksPerYear, int weeklyMinutes, int monthlyMinutes, long monthlyFee,
                             long registrationFee, String formula, boolean inForce, boolean replaced) {
    }

    public enum State { BROUILLON, A_VENIR, EN_COURS, TERMINE }

    public record ContractDto(UUID id, String number, UUID childId, String childFirstName, String childLastName,
                              LocalDate childBirthDate, UUID sectionId, UUID requestId, LocalDate startDate,
                              LocalDate endDate, int noticeMonths, AdaptationBilling adaptationBilling,
                              long adaptationRate, long adaptationCost, List<SessionDto> adaptation,
                              State state, List<VersionDto> versions, long version) {
    }

    public record Created(UUID id) {
    }
}
