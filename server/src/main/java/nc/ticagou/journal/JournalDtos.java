package nc.ticagou.journal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import nc.ticagou.journal.DailyEntry.Kind;

public final class JournalDtos {

    private JournalDtos() {
    }

    /**
     * Transmission saisie sur la tablette, dans une opération de sa file.
     *
     * @param time heure réelle (« 10:30 ») si la saisie est faite après coup ; vide = heure de la saisie
     */
    public record EntryInput(
            @NotNull Kind kind,
            @NotEmpty @Size(max = JournalRules.MAX_CHILDREN) Set<@NotNull UUID> childIds,
            @Size(max = JournalRules.MAX_TEXT) String text,
            Map<String, String> details,
            boolean shared,
            @Size(max = 5) String time) {
    }

    /** Correction au bureau : heure, texte, détails, partage. Les enfants et le type ne changent pas. */
    public record EntryCorrection(
            @NotNull Instant occurredAt,
            @Size(max = JournalRules.MAX_TEXT) String text,
            Map<String, String> details,
            boolean shared) {
    }

    public record ChildRef(UUID id, String firstName, String lastName) {
    }

    public record EntryDto(UUID id, LocalDate day, Instant occurredAt, Kind kind, String text, Map<String, String> details,
                           boolean hasPhoto, boolean shared, List<ChildRef> children, String authorName,
                           Instant updatedAt) {
    }

    public record ReportedAbsenceRef(UUID childId, String reason, String guardianName) {
    }

    /** Ce qu'affiche l'onglet « Journée » de la tablette. */
    public record KioskJournal(List<EntryDto> entries, List<UUID> photoConsents, List<ReportedAbsenceRef> reportedAbsences) {
    }
}
