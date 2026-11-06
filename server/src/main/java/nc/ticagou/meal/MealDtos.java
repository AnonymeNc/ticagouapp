package nc.ticagou.meal;

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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import nc.ticagou.meal.KitchenAdvice.Course;
import nc.ticagou.meal.MealCounter.Basis;
import nc.ticagou.meal.MealCounter.Meal;

public final class MealDtos {

    private MealDtos() {
    }

    // --- Régimes et réglages ------------------------------------------------------------------

    public record DietDto(UUID id, String label, List<String> excludes, int displayOrder, long children, long version) {
    }

    public record DietRequest(
            @NotBlank @Size(max = 60) String label,
            @NotNull @Size(max = 16) List<@NotBlank String> excludes,
            @Min(0) @Max(99) int displayOrder) {
    }

    public record MealSettings(@NotNull LocalTime lunchTime, @NotNull LocalTime snackTime, List<String> tags) {

        @AssertTrue(message = "Le goûter doit suivre le repas")
        @JsonIgnore
        public boolean isOrderValid() {
            return lunchTime == null || snackTime == null || snackTime.isAfter(lunchTime);
        }
    }

    // --- Menus ---------------------------------------------------------------------------------

    public record MenuItemDto(int dayOfWeek, Course course, UUID dietId, String label, List<String> tags) {

        static MenuItemDto from(MenuItem item) {
            return new MenuItemDto(item.getDayOfWeek(), item.getCourse(), item.getDietId(), item.getLabel(),
                    List.copyOf(item.tagSet()));
        }
    }

    public enum VariantStatus { CONVIENT, VARIANTE, MANQUANTE, SANS_MENU }

    public record DietDay(int dayOfWeek, VariantStatus status, List<String> conflicts) {
    }

    /** Ligne de variante : n'apparaît que si au moins un enfant inscrit suit ce régime. */
    public record DietRow(UUID dietId, String label, List<String> excludes, List<String> children, List<DietDay> days) {
    }

    public record MenuWeekDto(LocalDate weekStart, LocalDate weekEnd, int isoWeek, MenuWeek.Status status,
                              boolean saved, Instant updatedAt, String updatedBy, List<Integer> openDays,
                              List<MenuItemDto> items, List<DietRow> diets, List<String> warnings, long version) {
    }

    public record MenuItemInput(
            @Min(1) @Max(7) int dayOfWeek,
            @NotNull Course course,
            UUID dietId,
            @NotBlank @Size(max = 200) String label,
            @Size(max = 16) List<String> tags) {
    }

    public record MenuWeekRequest(
            @NotNull MenuWeek.Status status,
            @NotNull @Size(max = 300) List<@Valid @NotNull MenuItemInput> items) {
    }

    // --- Vue cuisine ---------------------------------------------------------------------------

    public record SectionCount(UUID sectionId, String name, String color, int lunches, int snacks, int packedLunches,
                               int children, int minced, int mashed, int leavingBeforeSnack) {

        static SectionCount of(UUID id, String name, String color, MealCounter.SectionTotal total) {
            var t = total == null ? new MealCounter.SectionTotal(0, 0, 0, 0, 0, 0, 0) : total;
            return new SectionCount(id, name, color, t.lunches(), t.snacks(), t.packedLunches(), t.children(),
                    t.minced(), t.mashed(), t.leavingBeforeSnack());
        }
    }

    public record Adjustment(boolean served, String reason, String author, Instant at) {

        static Adjustment from(MealAdjustment a) {
            return a == null ? null : new Adjustment(a.isServed(), a.getReason(), a.getAuthor(), a.getCreatedAt());
        }
    }

    public record KitchenChild(UUID childId, String firstName, String lastName, UUID sectionId, String sectionName,
                               List<String> allergies, boolean pai, String diet, MealCounter.Texture texture,
                               boolean packedLunch, boolean lunch, boolean snack, boolean present, boolean planned,
                               Basis lunchBasis, Basis snackBasis, List<String> advice, String note,
                               Adjustment lunchAdjustment, Adjustment snackAdjustment) {

        boolean needsAdaptation() {
            return !allergies.isEmpty() || pai || diet != null || packedLunch;
        }

        boolean counted() {
            return lunch || snack || packedLunch;
        }
    }

    public record KitchenDay(LocalDate day, Instant countedAt, String structureName, LocalTime lunchTime,
                             LocalTime snackTime, boolean openDay, boolean closedQuarter, List<SectionCount> sections,
                             SectionCount total, List<KitchenChild> toAdapt, List<KitchenChild> absentWithNeeds,
                             List<KitchenChild> children, List<MenuItemDto> menu, Map<UUID, String> diets) {
    }

    /** {@code served} nul : retirer la correction et revenir au calcul d'après les présences. */
    public record AdjustmentRequest(
            @NotNull UUID childId,
            @NotNull LocalDate day,
            @NotNull Meal meal,
            Boolean served,
            @Size(max = 300) String reason) {

        @AssertTrue(message = "Indiquez le motif de la correction")
        @JsonIgnore
        public boolean isReasonGiven() {
            return served == null || (reason != null && !reason.isBlank());
        }
    }

    public record NoteRequest(@NotNull UUID childId, @NotNull LocalDate day, @Size(max = 300) String note) {
    }

    // --- Récapitulatif trimestriel ---------------------------------------------------------------

    public enum QuarterState { A_VENIR, EN_COURS, A_CLORE, CLOS }

    public record SectionInfo(UUID id, String name, String color) {
    }

    /** {@code month} nul pour la ligne du total. */
    public record MonthRow(YearMonth month, String label, int openDays, Map<UUID, Integer> lunchesBySection,
                           int lunches, int snacks, int packedLunches) {
    }

    public record Checks(boolean monthsOver, int toReview, boolean closed) {
    }

    public record QuarterDto(int year, int quarter, String label, LocalDate from, LocalDate to, QuarterState state,
                             Instant closedAt, String closedBy, List<SectionInfo> sections, List<MonthRow> months,
                             MonthRow total, String averagePerDay, Checks checks) {
    }

    public record QuarterSummary(int year, int quarter, String label, QuarterState state) {
    }

    /** Ligne du détail par enfant et par jour (export Excel). */
    public record DetailRow(LocalDate day, String child, String section, boolean lunch, boolean snack,
                            boolean packedLunch, String correction) {
    }

    public record ReopenRequest(@NotBlank @Size(max = 300) String reason) {
    }
}
