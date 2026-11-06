package nc.ticagou.enrollment;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import nc.ticagou.enrollment.CapacityDtos.TestResult;
import nc.ticagou.enrollment.EnrollmentRequest.CareType;
import nc.ticagou.enrollment.EnrollmentRequest.Stage;

public final class EnrollmentDtos {

    private EnrollmentDtos() {
    }

    static final String PHONE_PATTERN = "^\\+?[0-9 .-]{6,20}$";

    // --- Critères de priorité -------------------------------------------------------------------------

    public record CriterionDto(UUID id, String label, int points, LocalDate validFrom, LocalDate validTo,
                               int displayOrder) {

        static CriterionDto from(EnrollmentCriterion c) {
            return new CriterionDto(c.getId(), c.getLabel(), c.getPoints(), c.getValidFrom(), c.getValidTo(),
                    c.getDisplayOrder());
        }
    }

    public record CriterionRequest(
            @NotBlank @Size(max = 120) String label,
            @Min(0) @Max(20) int points,
            @NotNull LocalDate validFrom,
            LocalDate validTo,
            @Min(0) int displayOrder) {

        @AssertTrue(message = "La fin de validité ne peut pas précéder le début")
        @JsonIgnore
        public boolean isPeriodValid() {
            return validTo == null || validFrom == null || !validTo.isBefore(validFrom);
        }
    }

    // --- Tableau des demandes ----------------------------------------------------------------------

    /** Carte du tableau. {@code capacity} est nul pour les demandes closes et les places déjà proposées. */
    public record RequestCard(UUID id, String childFirstName, String childLastName, LocalDate birthDate,
                              LocalDate expectedBirthDate, UUID sectionId, LocalDate desiredStart, Set<Integer> days,
                              Stage stage, LocalDate receivedOn, LocalDate stageChangedOn, LocalDate followUpOn,
                              LocalDateTime visitAt, boolean visitDone, LocalDate offerExpiresOn,
                              String refusalReason, int score, List<String> tags, TestResult capacity) {
    }

    public record BoardStats(int inProgress, int overdueFollowUps, int visitsThisWeek, int awaitingAnswers) {
    }

    public record BoardDto(BoardStats stats, List<RequestCard> requests) {
    }

    // --- Fiche demande ----------------------------------------------------------------------------

    public record Sibling(UUID id, String firstName, String lastName, LocalDate birthDate, UUID sectionId,
                          LocalDate entryDate, LocalDate exitDate) {
    }

    public record NoteDto(UUID id, String body, String author, Instant createdAt) {

        static NoteDto from(EnrollmentNote n) {
            return new NoteDto(n.getId(), n.getBody(), n.getAuthor(), n.getCreatedAt());
        }
    }

    public record EventDto(UUID id, String label, String author, Instant createdAt) {

        static EventDto from(EnrollmentEvent e) {
            return new EventDto(e.getId(), e.getLabel(), e.getAuthor(), e.getCreatedAt());
        }
    }

    /** Rang de la demande parmi les demandes ouvertes de la même section et du même mois d'entrée. */
    public record Ranking(int rank, int of) {
    }

    public record ContractLink(UUID id, String number) {
    }

    public record RequestRecord(UUID id, LocalDate receivedOn, String childFirstName, String childLastName,
                                LocalDate birthDate, LocalDate expectedBirthDate, UUID sectionId,
                                LocalDate desiredStart, CareType careType, LocalTime arrivesAt, LocalTime leavesAt,
                                Set<Integer> days, String guardianFirstName, String guardianLastName,
                                String relationship, String phone, String email, UUID familyId, String familyLabel,
                                List<Sibling> siblings, Stage stage, LocalDate stageChangedOn, LocalDate followUpOn,
                                LocalDateTime visitAt, boolean visitDone, LocalDate offerExpiresOn,
                                String refusalReason, UUID childId, List<ContractLink> contracts,
                                Set<UUID> criteria, List<CriterionDto> availableCriteria, int score, Ranking ranking,
                                TestResult capacity, List<NoteDto> notes, List<EventDto> history, long version) {
    }

    // --- Saisie -------------------------------------------------------------------------------------

    public record RequestForm(
            @NotBlank @Size(max = 80) String childFirstName,
            @NotBlank @Size(max = 80) String childLastName,
            @PastOrPresent LocalDate birthDate,
            LocalDate expectedBirthDate,
            UUID sectionId,
            @NotNull LocalDate desiredStart,
            @NotNull CareType careType,
            LocalTime arrivesAt,
            LocalTime leavesAt,
            @NotEmpty Set<@Min(1) @Max(7) Integer> days,
            @NotBlank @Size(max = 80) String guardianFirstName,
            @NotBlank @Size(max = 80) String guardianLastName,
            @NotBlank @Size(max = 60) String relationship,
            @Pattern(regexp = PHONE_PATTERN, message = "Numéro de téléphone invalide") String phone,
            @Email @Size(max = 150) String email,
            UUID familyId,
            @NotNull Set<UUID> criteria) {

        @AssertTrue(message = "Indiquez la date de naissance, ou la date prévue si l’enfant est à naître")
        @JsonIgnore
        public boolean isBirthKnown() {
            return (birthDate == null) != (expectedBirthDate == null);
        }

        @AssertTrue(message = "Le départ doit suivre l’arrivée")
        @JsonIgnore
        public boolean isHoursOrdered() {
            return arrivesAt == null || leavesAt == null || leavesAt.isAfter(arrivesAt);
        }

        @AssertTrue(message = "Indiquez un moyen de recontacter la famille (téléphone ou e-mail)")
        @JsonIgnore
        public boolean isReachable() {
            return (phone != null && !phone.isBlank()) || (email != null && !email.isBlank());
        }
    }

    public record StageRequest(
            @NotNull Stage stage,
            LocalDate followUpOn,
            LocalDateTime visitAt,
            LocalDate offerExpiresOn,
            @Size(max = 300) String reason) {
    }

    public record VisitRequest(@NotNull LocalDateTime visitAt) {
    }

    public record NoteRequest(@NotBlank @Size(max = 2000) String body) {
    }

    public record Created(UUID id) {
    }
}
