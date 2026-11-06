package nc.ticagou.billing;

import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class AbsenceDtos {

    private AbsenceDtos() {
    }

    /** {@code invoiced} : un mois couvert est déjà facturé ; l'absence ne se modifie plus (corriger par un avoir). */
    public record AbsenceDto(UUID id, UUID childId, String childName, LocalDate fromDay, LocalDate toDay,
                             UUID reasonId, String reason, boolean deductible, boolean proofRequired,
                             LocalDate notifiedOn, UUID documentId, String documentName, String note,
                             String recordedBy, boolean invoiced, long version) {
    }

    public record AbsenceRequest(
            @NotNull LocalDate fromDay,
            @NotNull LocalDate toDay,
            @NotNull UUID reasonId,
            @NotNull LocalDate notifiedOn,
            UUID documentId,
            @Size(max = 300) String note) {

        @AssertTrue(message = "La fin de l’absence ne peut pas précéder son début")
        @JsonIgnore
        public boolean isPeriodValid() {
            return toDay == null || fromDay == null || !toDay.isBefore(fromDay);
        }

        @AssertTrue(message = "Une absence couvre au plus 92 jours")
        @JsonIgnore
        public boolean isLengthValid() {
            return toDay == null || fromDay == null || !toDay.isAfter(fromDay.plusDays(91));
        }
    }
}
