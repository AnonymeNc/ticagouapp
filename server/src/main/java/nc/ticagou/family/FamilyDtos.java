package nc.ticagou.family;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class FamilyDtos {

    private FamilyDtos() {
    }

    /** Téléphone calédonien (6 chiffres) ou international ; espaces, points et tirets tolérés. */
    static final String PHONE_PATTERN = "^\\+?[0-9 .-]{6,20}$";
    static final String PHONE_MESSAGE = "Numéro de téléphone invalide";

    public record FamilyDto(UUID id, String label, String address, String postalCode, String city, String situation,
                            String notes, long version, List<GuardianDto> guardians, List<SiblingDto> children) {

        public static FamilyDto of(Family f, List<Guardian> guardians, List<SiblingDto> children) {
            return new FamilyDto(f.getId(), f.getLabel(), f.getAddress(), f.getPostalCode(), f.getCity(),
                    f.getSituation(), f.getNotes(), f.getVersion(),
                    guardians.stream().map(GuardianDto::of).toList(), children);
        }
    }

    public record GuardianDto(UUID id, String firstName, String lastName, String relationship, boolean legalGuardian,
                              boolean payer, String mobilePhone, String workPhone, String email, String employer,
                              int displayOrder) {

        public static GuardianDto of(Guardian g) {
            return new GuardianDto(g.getId(), g.getFirstName(), g.getLastName(), g.getRelationship(),
                    g.isLegalGuardian(), g.isPayer(), g.getMobilePhone(), g.getWorkPhone(), g.getEmail(),
                    g.getEmployer(), g.getDisplayOrder());
        }
    }

    /** Enfant du foyer (fratrie), sans données de santé. */
    public record SiblingDto(UUID id, String firstName, String lastName, LocalDate birthDate, LocalDate exitDate) {
    }

    /** Ligne de recherche de famille (rattacher un nouvel enfant à un foyer existant). */
    public record FamilySummary(UUID id, String label, String city, List<String> guardianNames) {
    }

    public record FamilyRequest(
            @NotBlank @Size(max = 120) String label,
            @Size(max = 200) String address,
            @Size(max = 10) String postalCode,
            @Size(max = 100) String city,
            @Size(max = 500) String situation,
            @Size(max = 2000) String notes) {
    }

    public record GuardianRequest(
            @NotBlank @Size(max = 80) String firstName,
            @NotBlank @Size(max = 80) String lastName,
            @NotBlank @Size(max = 60) String relationship,
            boolean legalGuardian,
            boolean payer,
            @Pattern(regexp = PHONE_PATTERN, message = PHONE_MESSAGE) String mobilePhone,
            @Pattern(regexp = PHONE_PATTERN, message = PHONE_MESSAGE) String workPhone,
            @Email(message = "Adresse e-mail invalide") @Size(max = 150) String email,
            @Size(max = 150) String employer,
            @Min(0) int displayOrder) {
    }

    public record PurgeRequest(@NotBlank String confirmation) {
    }
}
