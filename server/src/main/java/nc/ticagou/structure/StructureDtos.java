package nc.ticagou.structure;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import nc.ticagou.common.Iban;

public final class StructureDtos {

    private StructureDtos() {
    }

    static final String RIDET_PATTERN = "^\\d{7}(\\.\\d{3})?$";
    static final String RIDET_MESSAGE = "Le RIDET comporte 7 chiffres, suivis éventuellement d’un point et de 3 chiffres (ex. 1234567.001)";
    static final String COLOR_PATTERN = "^#[0-9a-f]{6}$";

    public record StructureDto(
            UUID id,
            String name,
            String city,
            String ridet,
            String agrementNumber,
            LocalDate agrementDate,
            int capacity,
            String invoiceFooter,
            boolean hasLogo,
            List<OpeningHoursDto> openingHours,
            ContactDto contact,
            BankDto bank,
            PassageRulesDto passageRules,
            long version) {

        static StructureDto from(Structure s) {
            return new StructureDto(s.getId(), s.getName(), s.getCity(), s.getRidet(), s.getAgrementNumber(),
                    s.getAgrementDate(), s.getCapacity(), s.getInvoiceFooter(), s.hasLogo(),
                    s.getOpeningHours().stream().map(OpeningHoursDto::from).toList(),
                    new ContactDto(s.getAddress(), s.getPostalCode(), s.getPhone(), s.getEmail()),
                    new BankDto(s.getBankHolder(), s.getBankName(), s.getIban(), s.getBic(), s.isPrintRib()),
                    new PassageRulesDto(s.isPassagePropose(), s.isPassageFirstOfMonth(), s.isPassageBlockFull()),
                    s.getVersion());
        }
    }

    public record ContactDto(
            @Size(max = 300) String address,
            @Size(max = 10) String postalCode,
            @Size(max = 30) String phone,
            @Email @Size(max = 254) String email) {
    }

    /** Coordonnées bancaires imprimées sur les factures (RIB) ; l'IBAN est contrôlé (clé modulo 97). */
    public record BankDto(
            @Size(max = 150) String holder,
            @Size(max = 100) String bankName,
            @Size(max = 40) String iban,
            @Pattern(regexp = "^([A-Za-z]{6}[A-Za-z0-9]{2}([A-Za-z0-9]{3})?)?$", message = "Le BIC compte 8 ou 11 caractères") String bic,
            boolean printRib) {

        @AssertTrue(message = "L’IBAN n’est pas valide : vérifiez les chiffres saisis")
        @JsonIgnore
        public boolean isIbanValid() {
            return iban == null || iban.isBlank() || Iban.isValid(iban);
        }

        @AssertTrue(message = "Pour imprimer le RIB, renseignez le titulaire et l’IBAN")
        @JsonIgnore
        public boolean isRibComplete() {
            return !printRib || (holder != null && !holder.isBlank() && iban != null && !iban.isBlank());
        }
    }

    public record PassageRulesDto(boolean propose, boolean firstOfMonth, boolean blockFull) {
    }

    /** Section avec ses inscrits ce jour. */
    public record SectionRow(SectionDto section, int enrolled) {
    }

    public record PassageDto(UUID childId, String name, UUID fromSectionId, UUID toSectionId, LocalDate on,
                             long ageMonths, boolean blocked) {
    }

    /** Sections, inscrits, tranches sans section, règles et passages à proposer (Paramètres › Sections). */
    public record SectionsOverview(List<SectionRow> sections, int totalPlaces, int capacity, List<String> gaps,
                                   PassageRulesDto rules, List<PassageDto> passages) {
    }

    public record PassageRequest(@NotNull UUID childId, @NotNull UUID toSectionId) {
    }

    public record OpeningHoursDto(
            @Min(1) @Max(7) int dayOfWeek,
            @NotNull LocalTime opensAt,
            @NotNull LocalTime closesAt) {

        @AssertTrue(message = "L’heure de fermeture doit être après l’heure d’ouverture")
        @JsonIgnore
        public boolean isClosingAfterOpening() {
            return opensAt == null || closesAt == null || closesAt.isAfter(opensAt);
        }

        static OpeningHoursDto from(OpeningHours h) {
            return new OpeningHoursDto(h.dayOfWeek(), h.opensAt(), h.closesAt());
        }

        OpeningHours toEntity() {
            return new OpeningHours(dayOfWeek, opensAt, closesAt);
        }
    }

    /** Identité et agrément, saisis à l'assistant de premier lancement puis dans Paramètres. */
    public record StructureIdentity(
            @NotBlank @Size(max = 150) String name,
            @Size(max = 100) String city,
            @Pattern(regexp = RIDET_PATTERN, message = RIDET_MESSAGE) String ridet,
            @Size(max = 50) String agrementNumber,
            @PastOrPresent LocalDate agrementDate,
            @Min(1) @Max(500) int capacity) {
    }

    public record UpdateStructureRequest(
            @NotNull @Valid StructureIdentity identity,
            @Size(max = 2000) String invoiceFooter,
            @NotNull @Size(max = 7) List<@Valid OpeningHoursDto> openingHours,
            /* Facultatifs : absents, les coordonnées et la banque ne changent pas. */
            @Valid ContactDto contact,
            @Valid BankDto bank) {

        @AssertTrue(message = "Chaque jour ne peut apparaître qu’une fois dans les horaires")
        @JsonIgnore
        public boolean isOneEntryPerDay() {
            return openingHours == null
                    || openingHours.stream().map(OpeningHoursDto::dayOfWeek).distinct().count() == openingHours.size();
        }
    }

    public record SectionDto(UUID id, String name, int minAgeMonths, Integer maxAgeMonths, int capacity, String color,
                             int displayOrder) {

        static SectionDto from(Section s) {
            return new SectionDto(s.getId(), s.getName(), s.getMinAgeMonths(), s.getMaxAgeMonths(), s.getCapacity(),
                    s.getColor(), s.getDisplayOrder());
        }
    }

    public record SectionRequest(
            @NotBlank @Size(max = 60) String name,
            @Min(0) @Max(72) int minAgeMonths,
            @Min(1) @Max(84) Integer maxAgeMonths,
            @Min(1) @Max(200) int capacity,
            @NotNull @Pattern(regexp = COLOR_PATTERN, message = "Couleur attendue au format #rrggbb") String color,
            @Min(0) int displayOrder) {

        @AssertTrue(message = "L’âge de sortie doit être supérieur à l’âge d’entrée")
        @JsonIgnore
        public boolean isAgeRangeValid() {
            return maxAgeMonths == null || maxAgeMonths > minAgeMonths;
        }

        SectionRules.Range range(UUID id) {
            return new SectionRules.Range(id, name, minAgeMonths, maxAgeMonths, capacity);
        }
    }
}
