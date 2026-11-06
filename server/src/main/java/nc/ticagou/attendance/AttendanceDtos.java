package nc.ticagou.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import nc.ticagou.attendance.PickupRules.Candidate;
import nc.ticagou.auth.Role;
import nc.ticagou.child.Allergy;

public final class AttendanceDtos {

    private AttendanceDtos() {
    }

    static final String PIN_PATTERN = "^\\d{4}$";
    static final String PIN_MESSAGE = "Le code PIN comporte 4 chiffres";

    // --- Saisies communes ------------------------------------------------------------

    public record InstructionInput(
            @NotNull UUID id,
            @NotNull ChildInstruction.Kind kind,
            @Size(max = 300) String details,
            LocalTime medicationAt,
            @Size(max = 150) String medicationDose,
            boolean prescriptionGiven) {
    }

    /** Personne de la liste (responsable légal ou personne autorisée) venue chercher l'enfant. */
    public record PickupInput(@NotNull PickupRules.Kind kind, UUID personId) {
    }

    /** Personne absente de la liste, validée par le code personnel d'une directrice (tablette). */
    public record OverrideInput(
            @NotBlank @Size(max = 170) String personName,
            @NotBlank @Size(max = 120) String relationship,
            boolean idChecked,
            UUID directorId,
            @Pattern(regexp = PIN_PATTERN, message = PIN_MESSAGE) String directorPin,
            @Size(max = 300) String reason) {
    }

    public record InstructionDto(UUID id, ChildInstruction.Kind kind, String details, LocalTime medicationAt,
                                 String medicationDose, boolean prescriptionGiven, Instant givenAt, String givenBy) {

        static InstructionDto from(ChildInstruction i) {
            return new InstructionDto(i.getId(), i.getKind(), i.getDetails(), i.getMedicationAt(),
                    i.getMedicationDose(), i.isPrescriptionGiven(), i.getGivenAt(), i.getGivenBy());
        }
    }

    public record OverrideDto(String personName, String relationship, boolean idChecked, String validatedBy,
                              Instant validatedAt, boolean pinVerified, String reason) {

        static OverrideDto from(DepartureOverride o) {
            return o == null ? null : new OverrideDto(o.getPersonName(), o.getRelationship(), o.isIdChecked(),
                    o.getValidatedBy(), o.getValidatedAt(), o.isPinVerified(), o.getReason());
        }
    }

    // --- Poste bureau : journée, corrections ---------------------------------------------

    public record ChildRow(UUID id, LocalDate day, UUID childId, String firstName, String lastName, UUID sectionId,
                           Instant arrivedAt, Instant departedAt, String droppedOffBy, PickupRules.Kind pickedUpByKind,
                           String pickedUpByName, boolean unplanned, String arrivalRecordedBy,
                           String departureRecordedBy, ChildAttendance.Source source, boolean needsReview,
                           String reviewReason, List<InstructionDto> instructions, OverrideDto override,
                           long version) {
    }

    public record ExpectedRow(UUID childId, String firstName, String lastName, UUID sectionId, LocalTime expectedAt,
                              boolean late) {
    }

    public record StaffRow(UUID id, LocalDate day, UUID userId, String displayName, String initials, String jobTitle,
                           Role role, boolean countsInRatio, UUID sectionId, Instant arrivedAt, Instant departedAt,
                           StaffAttendance.Source source, boolean pinVerified, boolean needsReview,
                           String reviewReason, long version) {
    }

    /** Personne qui n'a pas encore pointé aujourd'hui (pas de planning du personnel avant la phase Personnel). */
    public record AbsentStaff(UUID userId, String displayName, String initials, String jobTitle, Role role,
                              boolean countsInRatio, UUID homeSectionId) {
    }

    public record DayView(LocalDate day, List<ChildRow> children, List<ExpectedRow> notArrived,
                          List<StaffRow> staff, List<AbsentStaff> staffNotArrived, int expected) {
    }

    public record RefusedOperation(UUID clientId, Instant receivedAt, Instant occurredAt, String type, String code,
                                   String message) {
    }

    public record ReviewList(List<ChildRow> children, List<StaffRow> staff, List<RefusedOperation> refused) {
    }

    public record CorrectionDto(String field, String oldValue, String newValue, String reason, String author,
                                Instant createdAt) {

        static CorrectionDto from(AttendanceCorrection c) {
            return new CorrectionDto(c.getField(), c.getOldValue(), c.getNewValue(), c.getReason(), c.getAuthor(),
                    c.getCreatedAt());
        }
    }

    /** Arrivée pointée depuis le poste bureau, à l'heure du serveur. */
    public record DeskArrival(UUID sectionId, @Size(max = 120) String droppedOffBy) {
    }

    /** Départ pointé au bureau : une personne de la liste, ou une autre personne validée par la direction connectée. */
    public record DeskDeparture(@Valid PickupInput pickup, @Valid OverrideInput other) {
    }

    public record ChildCorrection(
            @NotNull Instant arrivedAt,
            Instant departedAt,
            UUID sectionId,
            @Size(max = 170) String pickedUpBy,
            @NotBlank @Size(max = 300) String reason) {
    }

    public record ChildAddition(
            @NotNull UUID childId,
            @NotNull Instant arrivedAt,
            Instant departedAt,
            UUID sectionId,
            @Size(max = 170) String pickedUpBy,
            @NotBlank @Size(max = 300) String reason) {
    }

    public record StaffCorrection(
            @NotNull Instant arrivedAt,
            Instant departedAt,
            UUID sectionId,
            @NotBlank @Size(max = 300) String reason) {
    }

    public record StaffAddition(
            @NotNull UUID userId,
            @NotNull Instant arrivedAt,
            Instant departedAt,
            UUID sectionId,
            @NotBlank @Size(max = 300) String reason) {
    }

    public record ReviewRequest(@NotBlank @Size(max = 300) String note) {
    }

    // --- Tablette : instantané du jour ------------------------------------------------------

    public record KioskSection(UUID id, String name, String color) {
    }

    public record AllergyInfo(String allergen, Allergy.Severity severity, String reaction) {
    }

    public record KioskHealth(List<AllergyInfo> allergies, boolean pai, String paiReason, String paiKitLocation,
                              String diet, String dietDetails, String instructions) {
    }

    public record Contact(String name, String relationship, String phone) {
    }

    public record KioskAttendance(UUID id, Instant arrivedAt, Instant departedAt, String droppedOffBy,
                                  String pickedUpByName, List<InstructionDto> instructions) {
    }

    /**
     * Enfant inscrit ce jour-là. {@code planned} : attendu d'après son contrat ; {@code childrenPerAdult} : ratio
     * de sa tranche d'âge, pour que la tablette recalcule l'encadrement hors connexion.
     */
    public record KioskChild(UUID id, String firstName, String lastName, LocalDate birthDate, UUID sectionId,
                             boolean planned, boolean variablePlanning, LocalTime expectedAt, LocalTime expectedLeaveAt,
                             int childrenPerAdult, KioskHealth health, List<Candidate> pickup,
                             List<Contact> parents, List<KioskAttendance> attendances) {
    }

    public record KioskStaffAttendance(UUID id, Instant arrivedAt, Instant departedAt, UUID sectionId) {
    }

    public record KioskStaff(UUID userId, String displayName, String initials, Role role, String jobTitle,
                             UUID homeSectionId, boolean countsInRatio, boolean hasPin,
                             List<KioskStaffAttendance> attendances) {
    }

    public record Director(UUID userId, String displayName) {
    }

    public record KioskToday(Instant serverTime, LocalDate day, String structureName, List<KioskSection> sections,
                             List<KioskChild> children, List<KioskStaff> staff, List<Director> directors,
                             int fallbackChildrenPerAdult) {
    }

    // --- Tablette : opérations ----------------------------------------------------------------

    public enum OperationType {
        ARRIVEE, ANNULER_ARRIVEE, CONSIGNES, MEDICAMENT_DONNE, DEPART, POINTAGE_PERSONNEL, SECTION_PERSONNEL,
        CONNEXION_HORS_LIGNE, TRANSMISSION
    }

    public enum StaffAction { ARRIVEE, DEPART }

    /**
     * Opération de la tablette, idempotente par {@code clientId}. L'auteur est prouvé par la session PIN
     * ({@code sessionId}) ou, hors connexion, par une opération CONNEXION_HORS_LIGNE ({@code offlineSessionId}).
     */
    public record Operation(
            @NotNull UUID clientId,
            @NotNull OperationType type,
            @NotNull Instant occurredAt,
            boolean offline,
            UUID userId,
            UUID sessionId,
            UUID offlineSessionId,
            UUID childId,
            UUID attendanceId,
            UUID sectionId,
            @Size(max = 120) String droppedOffBy,
            @Size(max = 10) List<@Valid InstructionInput> instructions,
            UUID instructionId,
            @Valid PickupInput pickup,
            @Valid OverrideInput override,
            @Pattern(regexp = PIN_PATTERN, message = PIN_MESSAGE) String pin,
            StaffAction staffAction,
            UUID staffUserId,
            @Valid nc.ticagou.journal.JournalDtos.EntryInput entry) {
    }

    public record OperationBatch(@NotNull @Size(max = 500) List<@Valid @NotNull Operation> operations) {
    }

    public record OperationResult(UUID clientId, KioskOperation.Outcome outcome, String code, String message,
                                  boolean needsReview) {
    }

    public record BatchResult(Instant serverTime, List<OperationResult> results) {
    }

    /** Pointage du personnel par son seul code (en ligne). */
    public record StaffPunchRequest(
            @NotNull UUID clientId,
            @NotNull @Pattern(regexp = PIN_PATTERN, message = PIN_MESSAGE) String pin,
            UUID sectionId) {
    }

    public record StaffPunchResult(UUID userId, String displayName, StaffAction action, Instant at, UUID sectionId,
                                   UUID attendanceId) {
    }
}
