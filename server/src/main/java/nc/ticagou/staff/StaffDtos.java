package nc.ticagou.staff;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import nc.ticagou.auth.Role;
import nc.ticagou.staff.Shifts.AbsenceKind;
import nc.ticagou.staff.StaffProfile.ContractType;
import nc.ticagou.staff.StaffQualification.Kind;
import nc.ticagou.staff.TimesheetCalculator.Anomaly;
import nc.ticagou.staffing.RatioCalculator.Status;

/** Personnel : équipe, fiches, qualifications, planning, prévision d'encadrement, feuilles d'heures. */
public final class StaffDtos {

    private StaffDtos() {
    }

    // ---------------------------------------------------------------- équipe et fiche

    public enum AlertKind { QUALIFICATION, CONTRAT }

    public record AlertDto(UUID userId, String name, AlertKind kind, QualificationState state, String label,
                           LocalDate date) {
    }

    public record MemberSummary(UUID userId, String firstName, String lastName, Role role, boolean active,
                                String jobTitle, String diploma, UUID homeSectionId, boolean countsInRatio,
                                ContractType contractType, Integer weeklyMinutes, LocalDate hiredOn, LocalDate endsOn,
                                List<AlertDto> alerts) {
    }

    public record TeamOverview(List<MemberSummary> members, int people, int inRatio, int expired, int toRenew,
                               LocalDate nextDue, int plannedTrainings, String nextTraining, LocalDate nextTrainingOn,
                               int alertDays) {
    }

    public record QualificationDto(UUID id, Kind kind, String label, LocalDate obtainedOn, LocalDate validUntil,
                                   String fileName, boolean verified, QualificationState state, long version) {
    }

    public record QualificationRequest(
            @NotNull Kind kind,
            @NotBlank @Size(max = 160) String label,
            LocalDate obtainedOn,
            LocalDate validUntil,
            boolean verified) {

        @AssertTrue(message = "La fin de validité ne peut pas précéder l’obtention")
        @JsonIgnore
        public boolean isPeriodValid() {
            return validUntil == null || obtainedOn == null || !validUntil.isBefore(obtainedOn);
        }
    }

    public record TrainingDto(UUID id, String label, int minutes, LocalDate startsOn, LocalDate endsOn,
                              String organization, boolean done, String fileName, long version) {
    }

    public record TrainingRequest(
            @NotBlank @Size(max = 200) String label,
            @Min(1) @Max(100_000) int minutes,
            @NotNull LocalDate startsOn,
            @NotNull LocalDate endsOn,
            @Size(max = 160) String organization,
            boolean done) {

        @AssertTrue(message = "La fin de la formation ne peut pas précéder son début")
        @JsonIgnore
        public boolean isPeriodValid() {
            return endsOn == null || startsOn == null || !endsOn.isBefore(startsOn);
        }
    }

    public record ScheduleDay(@Min(1) @Max(7) int dayOfWeek, @NotNull LocalTime startsAt, @NotNull LocalTime endsAt,
                              @Min(0) @Max(240) int breakMinutes, UUID sectionId) {

        @AssertTrue(message = "L’heure de fin doit suivre l’heure de début")
        @JsonIgnore
        public boolean isOrdered() {
            return startsAt == null || endsAt == null || endsAt.isAfter(startsAt);
        }
    }

    /** Semaine type en vigueur à partir de {@code effectiveFrom}. */
    public record ScheduleDto(LocalDate effectiveFrom, List<ScheduleDay> days, int weekMinutes) {
    }

    public record ScheduleRequest(@NotNull LocalDate effectiveFrom, @NotNull @Size(max = 7) List<@Valid ScheduleDay> days) {

        @AssertTrue(message = "Un seul horaire par jour de la semaine")
        @JsonIgnore
        public boolean isDistinct() {
            return days == null || days.stream().map(ScheduleDay::dayOfWeek).distinct().count() == days.size();
        }
    }

    public record ProfileDto(MemberSummary member, LocalDate birthDate, String phone, String email, String address,
                             String cafatNumber, String emergencyContact, List<QualificationDto> qualifications,
                             List<TrainingDto> trainings, int trainingMinutesSinceJanuary, ScheduleDto schedule,
                             List<ScheduleDto> upcomingSchedules, long version) {
    }

    public record ProfileRequest(
            LocalDate birthDate,
            @Size(max = 30) String phone,
            @Email @Size(max = 254) String email,
            @Size(max = 300) String address,
            @Size(max = 30) String cafatNumber,
            @Size(max = 200) String emergencyContact,
            @Size(max = 120) String diploma,
            @NotNull ContractType contractType,
            @Min(0) @Max(3600) Integer weeklyMinutes,
            LocalDate hiredOn,
            LocalDate endsOn) {

        @AssertTrue(message = "La fin du contrat ne peut pas précéder l’entrée")
        @JsonIgnore
        public boolean isPeriodValid() {
            return endsOn == null || hiredOn == null || !endsOn.isBefore(hiredOn);
        }
    }

    public record SettingsDto(LocalDate validFrom, @Min(1) @Max(180) int alertDays,
                              @Min(0) @Max(240) int defaultBreakMinutes,
                              @Min(60) @Max(3600) int referenceWeeklyMinutes) {
    }

    // ---------------------------------------------------------------- planning

    public record ShiftDto(LocalDate day, LocalTime startsAt, LocalTime endsAt, int breakMinutes, UUID sectionId,
                           boolean overridden, String note, AbsenceKind absence, UUID absenceId, boolean halfDay,
                           int plannedMinutes) {
    }

    public record PlanningRow(UUID userId, String name, String jobTitle, UUID homeSectionId, boolean countsInRatio,
                              Integer weeklyMinutes, int plannedMinutes, List<ShiftDto> shifts) {
    }

    /** {@code shortages} : nombre de créneaux en sous-effectif, par jour. */
    public record WeekPlanning(LocalDate monday, List<Integer> openDays, List<PlanningRow> rows,
                               Map<LocalDate, Integer> shortages, List<YearMonth> validatedMonths) {
    }

    /** Exception d'un jour ; {@code startsAt} nul : jour non travaillé. */
    public record OverrideRequest(LocalTime startsAt, LocalTime endsAt, @Min(0) @Max(240) int breakMinutes,
                                  UUID sectionId, @Size(max = 200) String note) {

        @AssertTrue(message = "Renseignez le début et la fin, la fin après le début")
        @JsonIgnore
        public boolean isTimesValid() {
            return (startsAt == null) == (endsAt == null) && (startsAt == null || endsAt.isAfter(startsAt));
        }
    }

    public record AbsenceDto(UUID id, UUID userId, String name, AbsenceKind kind, LocalDate fromDay, LocalDate toDay,
                             boolean halfDay, String note, String author, long version) {
    }

    public record AbsenceRequest(@NotNull UUID userId, @NotNull AbsenceKind kind, @NotNull LocalDate fromDay,
                                 @NotNull LocalDate toDay, boolean halfDay, @Size(max = 200) String note) {

        @AssertTrue(message = "La fin de l’absence ne peut pas précéder son début")
        @JsonIgnore
        public boolean isPeriodValid() {
            return toDay == null || fromDay == null || !toDay.isBefore(fromDay);
        }

        @AssertTrue(message = "Une demi-journée porte sur un seul jour")
        @JsonIgnore
        public boolean isHalfDayValid() {
            return !halfDay || fromDay == null || fromDay.equals(toDay);
        }
    }

    // ---------------------------------------------------------------- prévision

    public record LoadDto(int children, int adults, int required, Status status) {
    }

    public record SlotDto(LocalTime startsAt, LocalTime endsAt, Map<UUID, LoadDto> sections, LoadDto global) {
    }

    public record SectionDto(UUID id, String name, String color) {
    }

    public record Forecast(LocalDate day, boolean open, List<SectionDto> sections, List<SlotDto> slots, int shortages,
                           int variableChildren) {
    }

    // ---------------------------------------------------------------- feuilles d'heures

    public record PunchDto(LocalTime arrivedAt, LocalTime departedAt) {
    }

    public record DayDto(LocalDate day, int plannedMinutes, int workedMinutes, AbsenceKind absence, int absenceMinutes,
                         List<PunchDto> punches, List<Anomaly> anomalies) {
    }

    public record WeekDto(LocalDate monday, int workedMinutes, int trainingMinutes, int contractMinutes,
                          int overtimeMinutes) {
    }

    public record PersonSheet(UUID userId, String name, String jobTitle, StaffProfile.ContractType contractType,
                              Integer weeklyMinutes, int plannedMinutes, int workedMinutes, int overtimeMinutes,
                              Map<AbsenceKind, Integer> absenceMinutes, int anomalies, List<DayDto> days,
                              List<WeekDto> weeks) {
    }

    public record MonthSheet(YearMonth month, boolean validated, Instant validatedAt, String validatedBy,
                             List<PersonSheet> people) {
    }
}
