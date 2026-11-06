package nc.ticagou.staff;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Planning d'une personne un jour donné, sans dépendance à Spring : semaine type en vigueur, puis exception du jour,
 * puis absence. Une absence d'une demi-journée laisse le poste prévu, à moitié.
 */
public final class Shifts {

    private Shifts() {
    }

    public enum AbsenceKind { CONGES, MALADIE, FORMATION, RECUPERATION, AUTRE }

    public record DayTemplate(int dayOfWeek, LocalTime startsAt, LocalTime endsAt, int breakMinutes, UUID sectionId) {
    }

    /** Semaine type datée. */
    public record Week(LocalDate effectiveFrom, List<DayTemplate> days) {
    }

    /** Exception d'un jour ; {@code startsAt} nul : jour non travaillé. */
    public record Override(LocalDate day, LocalTime startsAt, LocalTime endsAt, int breakMinutes, UUID sectionId,
                           String note) {
    }

    public record Absence(AbsenceKind kind, LocalDate from, LocalDate to, boolean halfDay) {

        boolean covers(LocalDate day) {
            return !day.isBefore(from) && !day.isAfter(to);
        }
    }

    /**
     * Poste du jour. {@code startsAt} nul : rien de prévu. {@code absence} non nulle : absence ce jour-là (le poste
     * prévu reste connu pour valoriser l'absence).
     */
    public record Shift(LocalDate day, LocalTime startsAt, LocalTime endsAt, int breakMinutes, UUID sectionId,
                        boolean overridden, String note, AbsenceKind absence, boolean halfDay) {

        public boolean planned() {
            return startsAt != null;
        }

        /** Durée prévue, pause déduite. */
        public int plannedMinutes() {
            return planned() ? Math.max((int) Duration.between(startsAt, endsAt).toMinutes() - breakMinutes, 0) : 0;
        }

        /** Durée couverte par l'absence (la moitié du poste pour une demi-journée). */
        public int absenceMinutes() {
            if (absence == null) {
                return 0;
            }
            return halfDay ? plannedMinutes() / 2 : plannedMinutes();
        }

        /** Présent au moins une partie du poste (absence d'une demi-journée comprise). */
        public boolean working() {
            return planned() && (absence == null || halfDay);
        }

        /** Heure de fin effective : une demi-journée d'absence ramène la fin au milieu du poste. */
        public LocalTime effectiveEnd() {
            if (absence != null && halfDay) {
                return startsAt.plusMinutes(Duration.between(startsAt, endsAt).toMinutes() / 2);
            }
            return endsAt;
        }
    }

    public static Shift resolve(LocalDate day, List<Week> weeks, List<Override> overrides, List<Absence> absences) {
        var absence = absences.stream().filter(a -> a.covers(day)).findFirst().orElse(null);
        var override = overrides.stream().filter(o -> o.day().equals(day)).findFirst().orElse(null);
        if (override != null) {
            return new Shift(day, override.startsAt(), override.endsAt(), override.breakMinutes(), override.sectionId(),
                    true, override.note(), absence == null ? null : absence.kind(), absence != null && absence.halfDay());
        }
        var week = weeks.stream().filter(w -> !w.effectiveFrom().isAfter(day))
                .max(Comparator.comparing(Week::effectiveFrom)).orElse(null);
        var template = week == null ? null : week.days().stream()
                .filter(d -> d.dayOfWeek() == day.getDayOfWeek().getValue()).findFirst().orElse(null);
        if (template == null) {
            return new Shift(day, null, null, 0, null, false, null, absence == null ? null : absence.kind(),
                    absence != null && absence.halfDay());
        }
        return new Shift(day, template.startsAt(), template.endsAt(), template.breakMinutes(), template.sectionId(), false,
                null, absence == null ? null : absence.kind(), absence != null && absence.halfDay());
    }
}
