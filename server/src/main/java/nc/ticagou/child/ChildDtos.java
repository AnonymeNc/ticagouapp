package nc.ticagou.child;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import nc.ticagou.family.FamilyDtos.FamilyDto;
import nc.ticagou.family.FamilyDtos.FamilyRequest;
import nc.ticagou.family.FamilyDtos.GuardianRequest;

public final class ChildDtos {

    private ChildDtos() {
    }

    // --- Liste -------------------------------------------------------------------

    /**
     * Ligne de la liste des enfants. Les informations de santé critiques y figurent toujours :
     * elles doivent être visibles partout où l'enfant apparaît (y compris pour la cuisine).
     */
    public record ChildListItem(UUID id, String fileNumber, String firstName, String lastName, LocalDate birthDate,
                                UUID sectionId, LocalDate entryDate, LocalDate exitDate, boolean hasPhoto,
                                UUID familyId, HealthSummary health) {
    }

    public record HealthSummary(List<AllergySummary> allergies, boolean pai, String diet) {
    }

    public record AllergySummary(String allergen, Allergy.Severity severity) {
    }

    // --- Création, identité --------------------------------------------------

    public record ChildIdentityRequest(
            @NotBlank @Size(max = 80) String firstName,
            @NotBlank @Size(max = 80) String lastName,
            @NotNull @PastOrPresent LocalDate birthDate,
            UUID sectionId,
            LocalDate entryDate,
            LocalDate exitDate,
            @Size(max = 200) String homeLanguages) {

        @AssertTrue(message = "La date d’entrée ne peut pas précéder la naissance")
        @JsonIgnore
        public boolean isEntryAfterBirth() {
            return entryDate == null || birthDate == null || !entryDate.isBefore(birthDate);
        }

        @AssertTrue(message = "La date de sortie ne peut pas précéder la date d’entrée")
        @JsonIgnore
        public boolean isExitAfterEntry() {
            return exitDate == null || entryDate == null || !exitDate.isBefore(entryDate);
        }
    }

    /** Nouvel enfant : rattaché à une famille existante ({@code familyId}) ou à un nouveau foyer. */
    public record CreateChildRequest(
            @NotNull @Valid ChildIdentityRequest child,
            UUID familyId,
            @Valid NewFamily family) {

        @AssertTrue(message = "Choisissez une famille existante ou créez-en une")
        @JsonIgnore
        public boolean isFamilyChosen() {
            return (familyId == null) != (family == null);
        }
    }

    public record NewFamily(@NotNull @Valid FamilyRequest family, @NotNull @Valid GuardianRequest guardian) {
    }

    public record Created(UUID id) {
    }

    // --- Fiche ---------------------------------------------------------------------

    public record ChildRecord(
            UUID id,
            String fileNumber,
            String firstName,
            String lastName,
            LocalDate birthDate,
            UUID sectionId,
            LocalDate entryDate,
            LocalDate exitDate,
            String homeLanguages,
            boolean hasPhoto,
            long version,
            FamilyDto family,
            List<AuthorizedPersonDto> authorizedPersons,
            HealthDto health,
            List<ConsentDto> consents,
            List<DocumentDto> documents) {
    }

    public record AuthorizedPersonDto(UUID id, String firstName, String lastName, String relationship, String phone,
                                      LocalDate idVerifiedOn, String idVerifiedBy, boolean hasPhoto) {

        static AuthorizedPersonDto of(AuthorizedPerson p) {
            return new AuthorizedPersonDto(p.getId(), p.getFirstName(), p.getLastName(), p.getRelationship(),
                    p.getPhone(), p.getIdVerifiedOn(), p.getIdVerifiedBy(), p.getPhotoFileId() != null);
        }
    }

    public record AuthorizedPersonRequest(
            @NotBlank @Size(max = 80) String firstName,
            @NotBlank @Size(max = 80) String lastName,
            @NotBlank @Size(max = 60) String relationship,
            @Pattern(regexp = "^\\+?[0-9 .-]{6,20}$", message = "Numéro de téléphone invalide") String phone,
            /* Pièce d'identité vérifiée : date et auteur enregistrés par le serveur. */
            boolean idVerified) {
    }

    // --- Santé ---------------------------------------------------------------------

    public record HealthDto(
            @Size(max = 100) String diet,
            @Size(max = 500) String dietDetails,
            boolean paiActive,
            @Size(max = 200) String paiReason,
            LocalDate paiValidFrom,
            LocalDate paiValidUntil,
            @Size(max = 200) String paiKitLocation,
            @Size(max = 500) String paiKitDetails,
            UUID paiDocumentId,
            @Size(max = 500) String emergencyProtocol,
            @Size(max = 120) String doctorName,
            @Size(max = 30) String doctorPhone,
            @Size(max = 200) String doctorAddress,
            @Size(max = 2000) String instructions,
            @PastOrPresent LocalDate vaccinationCheckedOn,
            @NotNull @Size(max = 30) List<@Valid AllergyDto> allergies,
            @NotNull @Size(max = 30) List<@Valid VaccinationDto> vaccinations,
            UUID dietId,
            @Pattern(regexp = "NORMALE|HACHEE|MIXEE", message = "Texture inconnue") String mealTexture,
            Boolean packedLunch) {

        @AssertTrue(message = "La fin de validité du PAI ne peut pas précéder son début")
        @JsonIgnore
        public boolean isPaiPeriodValid() {
            return paiValidFrom == null || paiValidUntil == null || !paiValidUntil.isBefore(paiValidFrom);
        }
    }

    public record AllergyDto(
            @NotBlank @Size(max = 100) String allergen,
            @Size(max = 300) String reaction,
            @NotNull Allergy.Severity severity) {
    }

    public record VaccinationDto(@NotBlank @Size(max = 100) String vaccine, @PastOrPresent LocalDate lastDoseOn) {
    }

    // --- Autorisations ---------------------------------------------------------

    public record ConsentDto(
            @NotNull Consent.Type type,
            boolean granted,
            @Size(max = 500) String details,
            @PastOrPresent LocalDate signedOn,
            @Size(max = 170) String signedBy) {

        static ConsentDto of(Consent c) {
            return new ConsentDto(c.getType(), c.isGranted(), c.getDetails(), c.getSignedOn(), c.getSignedBy());
        }
    }

    public record ConsentsRequest(@NotNull @Size(max = 3) List<@Valid ConsentDto> consents) {

        @AssertTrue(message = "Chaque autorisation ne peut figurer qu’une fois")
        @JsonIgnore
        public boolean isOnePerType() {
            return consents == null
                    || consents.stream().map(ConsentDto::type).distinct().count() == consents.size();
        }
    }

    // --- Documents ---------------------------------------------------------------

    public record DocumentDto(UUID id, String name, String fileName, ChildDocument.Category category,
                              LocalDate validUntil, Instant createdAt, String createdBy, String contentType,
                              long sizeBytes) {
    }
}
