package nc.ticagou.enrollment;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public final class CapacityDtos {

    private CapacityDtos() {
    }

    /** Places libres : {@code free[i]} correspond au mois {@code months[i]}. Négatif = dépassement. */
    public record DayRow(int dayOfWeek, int[] free) {
    }

    public record SectionCapacity(UUID sectionId, String name, String color, int capacity, List<DayRow> days) {
    }

    public record CapacityDto(List<YearMonth> months, List<Integer> openDays, List<SectionCapacity> sections) {
    }

    /** Demande à tester : l'enfant suit la section de son âge mois après mois. */
    public record TestRequest(
            LocalDate birthDate,
            UUID sectionId,
            @NotEmpty Set<@Min(1) @Max(7) Integer> days,
            @NotNull LocalDate desiredStart,
            UUID excludeRequestId) {
    }

    public record TestResult(CapacityCalculator.Verdict verdict, YearMonth firstMonth, List<Integer> fullDays) {

        static TestResult from(CapacityCalculator.Test test) {
            return new TestResult(test.verdict(), test.firstMonth(), test.fullDays());
        }
    }
}
