package nc.ticagou.meal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.attendance.ChildAttendance;
import nc.ticagou.attendance.ChildAttendanceRepository;
import nc.ticagou.attendance.PlannedDays;
import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.child.Allergy;
import nc.ticagou.child.AllergyRepository;
import nc.ticagou.child.Child;
import nc.ticagou.child.ChildRepository;
import nc.ticagou.child.HealthInfo;
import nc.ticagou.child.HealthInfoRepository;
import nc.ticagou.common.ApiException;
import nc.ticagou.config.TiCagouProperties;
import nc.ticagou.meal.KitchenAdvice.Course;
import nc.ticagou.meal.KitchenAdvice.DietRule;
import nc.ticagou.meal.MealCounter.Basis;
import nc.ticagou.meal.MealCounter.ChildDay;
import nc.ticagou.meal.MealCounter.Meal;
import nc.ticagou.meal.MealCounter.Passage;
import nc.ticagou.meal.MealCounter.Texture;
import nc.ticagou.meal.MealDtos.AdjustmentRequest;
import nc.ticagou.meal.MealDtos.Checks;
import nc.ticagou.meal.MealDtos.DetailRow;
import nc.ticagou.meal.MealDtos.KitchenChild;
import nc.ticagou.meal.MealDtos.KitchenDay;
import nc.ticagou.meal.MealDtos.MenuItemDto;
import nc.ticagou.meal.MealDtos.MonthRow;
import nc.ticagou.meal.MealDtos.NoteRequest;
import nc.ticagou.meal.MealDtos.QuarterDto;
import nc.ticagou.meal.MealDtos.QuarterState;
import nc.ticagou.meal.MealDtos.QuarterSummary;
import nc.ticagou.meal.MealDtos.SectionCount;
import nc.ticagou.meal.MealDtos.SectionInfo;
import nc.ticagou.structure.OpeningHours;
import nc.ticagou.structure.Section;
import nc.ticagou.structure.SectionRepository;
import nc.ticagou.structure.Structure;
import nc.ticagou.structure.StructureRepository;

/**
 * Repas et goûters comptés d'après les présences (MealCounter) : vue cuisine du jour, corrections manuelles,
 * récapitulatif trimestriel et sa clôture.
 */
@Service
public class MealService {

    private final ChildRepository children;
    private final HealthInfoRepository healthInfos;
    private final AllergyRepository allergies;
    private final ChildAttendanceRepository attendances;
    private final PlannedDays plannedDays;
    private final MealAdjustmentRepository adjustments;
    private final MealNoteRepository notes;
    private final QuarterClosureRepository closures;
    private final ClosedPeriods closedPeriods;
    private final MenuService menus;
    private final DietService diets;
    private final SectionRepository sections;
    private final StructureRepository structures;
    private final AuditService audit;
    private final ZoneId zone;
    private final Clock clock;

    public MealService(ChildRepository children, HealthInfoRepository healthInfos, AllergyRepository allergies,
                       ChildAttendanceRepository attendances, PlannedDays plannedDays,
                       MealAdjustmentRepository adjustments, MealNoteRepository notes,
                       QuarterClosureRepository closures, ClosedPeriods closedPeriods, MenuService menus,
                       DietService diets, SectionRepository sections, StructureRepository structures,
                       AuditService audit, TiCagouProperties properties, Clock clock) {
        this.children = children;
        this.healthInfos = healthInfos;
        this.allergies = allergies;
        this.attendances = attendances;
        this.plannedDays = plannedDays;
        this.adjustments = adjustments;
        this.notes = notes;
        this.closures = closures;
        this.closedPeriods = closedPeriods;
        this.menus = menus;
        this.diets = diets;
        this.sections = sections;
        this.structures = structures;
        this.audit = audit;
        this.zone = properties.zone();
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    // --- Vue cuisine ---------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public KitchenDay kitchen(LocalDate day) {
        var structureId = CurrentUser.get().structureId();
        var structure = structures.findById(structureId).orElseThrow();
        var sectionList = sections.findByStructureIdOrderByDisplayOrderAscMinAgeMonthsAsc(structureId);
        var sectionById = sectionList.stream().collect(Collectors.toMap(Section::getId, Function.identity()));
        var now = clock.instant();
        var today = today();
        LocalTime nowTime = day.isBefore(today) ? null : day.isAfter(today) ? LocalTime.MIN
                : now.atZone(zone).toLocalTime();

        var enrolled = children.findByStructureId(structureId).stream().filter(c -> c.isEnrolledOn(day))
                .sorted(Comparator.comparing(Child::getFirstName).thenComparing(Child::getLastName)).toList();
        var health = healthByChild(structureId);
        var allergiesByChild = allergies.findByStructureId(structureId).stream()
                .collect(Collectors.groupingBy(Allergy::getChildId));
        var passages = attendances.findByStructureIdAndDayOrderByArrivedAtAsc(structureId, day).stream()
                .collect(Collectors.groupingBy(ChildAttendance::getChildId));
        var planned = day.isBefore(today) ? Map.<UUID, PlannedDays.Planned>of() : plannedDays.forDay(structureId, day);
        var adjustmentsByKey = adjustments.findByStructureIdAndDayBetween(structureId, day, day).stream()
                .collect(Collectors.toMap(a -> a.getChildId() + "/" + a.getMeal(), Function.identity()));
        var notesByChild = notes.findByStructureIdAndDay(structureId, day).stream()
                .collect(Collectors.toMap(MealNote::getChildId, MealNote::getNote));

        var inputs = enrolled.stream().map(c -> childDay(c, passages.getOrDefault(c.getId(), List.of()),
                planned.get(c.getId()), health.get(c.getId()), adjustmentsByKey)).toList();
        var result = MealCounter.count(structure.getLunchTime(), structure.getSnackTime(), nowTime, inputs);
        var countById = result.children().stream().collect(Collectors.toMap(MealCounter.ChildCount::childId, Function.identity()));

        var dayItems = menus.itemsOf(structureId, day);
        var dishes = dayItems.stream().filter(i -> i.getCourse() != Course.VARIANTE).map(MenuItem::toDish).toList();
        var dietById = diets.byId(structureId);

        var all = new ArrayList<KitchenChild>();
        for (var child : enrolled) {
            var count = countById.get(child.getId());
            var h = health.get(child.getId());
            var allergyNames = allergiesByChild.getOrDefault(child.getId(), List.of()).stream()
                    .sorted(Comparator.comparingInt(Allergy::getDisplayOrder)).map(Allergy::getAllergen).toList();
            var diet = h == null || h.getDietId() == null ? null : dietById.get(h.getDietId());
            var variant = diet == null ? null : dayItems.stream()
                    .filter(i -> i.getCourse() == Course.VARIANTE && Objects.equals(i.getDietId(), diet.getId()))
                    .map(MenuItem::toDish).findFirst().orElse(null);
            var rule = diet == null ? null : new DietRule(diet.getLabel(), diet.excludedTags(), variant);
            boolean packed = h != null && h.isPackedLunch();
            var advice = KitchenAdvice.advise(packed, dishes, allergyNames, rule);
            var section = sectionById.get(count.sectionId());
            all.add(new KitchenChild(child.getId(), child.getFirstName(), child.getLastName(), count.sectionId(),
                    section == null ? null : section.getName(), allergyNames, h != null && h.isPaiValidOn(day),
                    h == null ? null : h.getDiet(), count.texture(), packed, count.lunch(), count.snack(),
                    count.present(), planned.containsKey(child.getId()) && planned.get(child.getId()).expected(),
                    count.lunchBasis(), count.snackBasis(), advice, notesByChild.get(child.getId()),
                    MealDtos.Adjustment.from(adjustmentsByKey.get(child.getId() + "/" + Meal.REPAS)),
                    MealDtos.Adjustment.from(adjustmentsByKey.get(child.getId() + "/" + Meal.GOUTER))));
        }
        var counted = all.stream().filter(KitchenChild::counted).toList();
        var toAdapt = counted.stream().filter(KitchenChild::needsAdaptation).toList();
        var absent = all.stream().filter(c -> !c.counted() && c.planned() && c.needsAdaptation()).toList();
        var sectionCounts = sectionList.stream()
                .map(s -> SectionCount.of(s.getId(), s.getName(), s.getColor(), result.sections().get(s.getId())))
                .toList();
        boolean openDay = structure.getOpeningHours().isEmpty() || structure.getOpeningHours().stream()
                .map(OpeningHours::dayOfWeek).anyMatch(d -> d == day.getDayOfWeek().getValue());
        var dietLabels = dietById.values().stream().collect(Collectors.toMap(Diet::getId, Diet::getLabel));
        return new KitchenDay(day, now, structure.getName(), structure.getLunchTime(), structure.getSnackTime(),
                openDay, closedPeriods.isClosed(structureId, day), sectionCounts,
                SectionCount.of(null, "Total", null, result.total()), toAdapt, absent, counted,
                dayItems.stream().map(MenuItemDto::from).toList(), dietLabels);
    }

    @Transactional
    public KitchenDay adjust(AdjustmentRequest request) {
        var user = CurrentUser.get();
        var child = loadChild(request.childId());
        closedPeriods.requireOpen(user.structureId(), request.day());
        if (request.day().isAfter(today())) {
            throw ApiException.rule("future_day", "Corrigez le comptage le jour même ou après.");
        }
        var existing = adjustments.findByChildIdAndDayAndMeal(child.getId(), request.day(), request.meal());
        if (request.served() == null) {
            existing.ifPresent(adjustments::delete);
            audit.record(AuditAction.MEAL_ADJUSTED, "child", child.getId(), Map.of("day", request.day().toString(),
                    "meal", request.meal().name(), "served", "calcul"));
        } else {
            var adjustment = existing.orElseGet(() -> new MealAdjustment(user.structureId(), child.getId(),
                    request.day(), request.meal()));
            adjustment.set(request.served(), request.reason().trim(), user.userId(), user.displayName(), clock.instant());
            adjustments.save(adjustment);
            audit.record(AuditAction.MEAL_ADJUSTED, "child", child.getId(), Map.of("day", request.day().toString(),
                    "meal", request.meal().name(), "served", request.served(), "reason", request.reason().trim()));
        }
        adjustments.flush();
        return kitchen(request.day());
    }

    @Transactional
    public void note(NoteRequest request) {
        var user = CurrentUser.get();
        var child = loadChild(request.childId());
        var existing = notes.findByChildIdAndDay(child.getId(), request.day());
        if (request.note() == null || request.note().isBlank()) {
            existing.ifPresent(notes::delete);
            return;
        }
        var note = existing.orElseGet(() -> new MealNote(user.structureId(), child.getId(), request.day()));
        note.set(request.note().trim(), user.displayName(), clock.instant());
        notes.save(note);
    }

    // --- Récapitulatif trimestriel -------------------------------------------------------------

    /** Récapitulatif et détail par enfant et par jour (export Excel). */
    public record Quarter(QuarterDto summary, List<DetailRow> detail) {
    }

    @Transactional(readOnly = true)
    public List<QuarterSummary> quarters() {
        var structureId = CurrentUser.get().structureId();
        var today = today();
        var result = new ArrayList<QuarterSummary>();
        int year = today.getYear();
        int quarter = ClosedPeriods.quarterOf(today);
        for (int i = 0; i < 5; i++) {
            result.add(new QuarterSummary(year, quarter, ClosedPeriods.label(year, quarter), state(structureId, year, quarter)));
            quarter--;
            if (quarter == 0) {
                quarter = 4;
                year--;
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Quarter quarter(int year, int quarter) {
        if (quarter < 1 || quarter > 4) {
            throw ApiException.notFound("Trimestre inconnu.");
        }
        var structureId = CurrentUser.get().structureId();
        var structure = structures.findById(structureId).orElseThrow();
        var from = LocalDate.of(year, (quarter - 1) * 3 + 1, 1);
        var to = from.plusMonths(3).minusDays(1);
        var today = today();
        var last = to.isBefore(today) ? to : today;
        var sectionList = sections.findByStructureIdOrderByDisplayOrderAscMinAgeMonthsAsc(structureId);
        var sectionInfos = sectionList.stream().map(s -> new SectionInfo(s.getId(), s.getName(), s.getColor())).toList();
        var sectionNames = sectionList.stream().collect(Collectors.toMap(Section::getId, Section::getName));

        var childById = children.findByStructureId(structureId).stream().collect(Collectors.toMap(Child::getId, Function.identity()));
        var health = healthByChild(structureId);
        var byDay = last.isBefore(from) ? Map.<LocalDate, List<ChildAttendance>>of()
                : attendances.findByStructureIdAndDayBetween(structureId, from, last).stream()
                .collect(Collectors.groupingBy(ChildAttendance::getDay));
        var adjustmentList = last.isBefore(from) ? List.<MealAdjustment>of()
                : adjustments.findByStructureIdAndDayBetween(structureId, from, last);
        var adjustmentsByDay = adjustmentList.stream().collect(Collectors.groupingBy(MealAdjustment::getDay));

        var months = new LinkedHashMap<YearMonth, MonthAccumulator>();
        for (int m = 0; m < 3; m++) {
            months.put(YearMonth.from(from).plusMonths(m), new MonthAccumulator());
        }
        var detail = new ArrayList<DetailRow>();
        for (var day = from; !day.isAfter(last); day = day.plusDays(1)) {
            var dayAttendances = byDay.getOrDefault(day, List.of());
            var dayAdjustments = adjustmentsByDay.getOrDefault(day, List.of());
            if (dayAttendances.isEmpty() && dayAdjustments.isEmpty()) {
                continue;
            }
            var currentDay = day;
            var dayKeys = dayAdjustments.stream().collect(Collectors.toMap(a -> a.getChildId() + "/" + a.getMeal(),
                    Function.identity()));
            var ids = new java.util.LinkedHashSet<UUID>();
            dayAttendances.forEach(a -> ids.add(a.getChildId()));
            dayAdjustments.forEach(a -> ids.add(a.getChildId()));
            var passagesByChild = dayAttendances.stream().collect(Collectors.groupingBy(ChildAttendance::getChildId));
            var inputs = ids.stream().filter(childById::containsKey)
                    .map(id -> childDay(childById.get(id), passagesByChild.getOrDefault(id, List.of()), null,
                            health.get(id), dayKeys))
                    .toList();
            LocalTime nowTime = day.equals(today) ? clock.instant().atZone(zone).toLocalTime() : null;
            var result = MealCounter.count(structure.getLunchTime(), structure.getSnackTime(), nowTime, inputs);
            var month = months.get(YearMonth.from(day));
            month.openDays++;
            for (var c : result.children()) {
                // Aujourd'hui : seuls les repas déjà servis comptent, pas les prévisions.
                boolean lunch = c.lunch() && c.lunchBasis() != Basis.PREVISION;
                boolean snack = c.snack() && c.snackBasis() != Basis.PREVISION;
                boolean packed = c.packedLunch() && (c.lunchBasis() != Basis.PREVISION || c.snackBasis() != Basis.PREVISION);
                if (lunch) {
                    month.lunches++;
                    month.bySection.merge(c.sectionId(), 1, Integer::sum);
                }
                if (snack) {
                    month.snacks++;
                }
                if (packed) {
                    month.packed++;
                }
                if (lunch || snack || packed) {
                    var child = childById.get(c.childId());
                    var corrections = new ArrayList<String>();
                    for (var meal : Meal.values()) {
                        var a = dayKeys.get(c.childId() + "/" + meal);
                        if (a != null) {
                            corrections.add("%s %s : %s".formatted(meal == Meal.REPAS ? "Repas" : "Goûter",
                                    a.isServed() ? "servi" : "non servi", a.getReason()));
                        }
                    }
                    detail.add(new DetailRow(currentDay, child.displayName(), sectionNames.getOrDefault(c.sectionId(), "—"),
                            lunch, snack, packed, corrections.isEmpty() ? null : String.join(" ; ", corrections)));
                }
            }
        }
        var monthRows = new ArrayList<MonthRow>();
        var total = new MonthAccumulator();
        months.forEach((month, acc) -> {
            monthRows.add(acc.row(month, label(month)));
            total.add(acc);
        });
        var totalRow = total.row(null, "Total du trimestre");
        var average = total.openDays == 0 ? "0" : String.format(Locale.FRANCE, "%.1f", (double) total.lunches / total.openDays);
        var closure = closures.findByStructureIdAndYearAndQuarterAndReopenedAtIsNull(structureId, year, quarter);
        var toReview = (int) attendances.findToReview(structureId, today).stream()
                .filter(a -> !a.getDay().isBefore(from) && !a.getDay().isAfter(to)).count();
        var checks = new Checks(to.isBefore(today), toReview, closure.isPresent());
        var summary = new QuarterDto(year, quarter, ClosedPeriods.label(year, quarter), from, to,
                state(structureId, year, quarter), closure.map(QuarterClosure::getClosedAt).orElse(null),
                closure.map(QuarterClosure::getClosedBy).orElse(null), sectionInfos, monthRows, totalRow, average, checks);
        detail.sort(Comparator.comparing(DetailRow::day).thenComparing(DetailRow::section).thenComparing(DetailRow::child));
        return new Quarter(summary, detail);
    }

    @Transactional
    public QuarterDto close(int year, int quarter) {
        var user = CurrentUser.get();
        var summary = quarter(year, quarter).summary();
        if (summary.state() == QuarterState.CLOS) {
            throw ApiException.conflict("quarter_closed", "Ce trimestre est déjà clos.");
        }
        if (!summary.checks().monthsOver()) {
            throw ApiException.rule("quarter_not_over", "Un trimestre se clôt une fois ses trois mois terminés.");
        }
        if (summary.checks().toReview() > 0) {
            throw ApiException.rule("attendance_to_review", "%d pointage%s du trimestre reste%s à vérifier dans Présences avant la clôture."
                    .formatted(summary.checks().toReview(), summary.checks().toReview() > 1 ? "s" : "",
                            summary.checks().toReview() > 1 ? "nt" : ""));
        }
        var closure = closures.saveAndFlush(new QuarterClosure(user.structureId(), year, quarter, clock.instant(),
                user.displayName()));
        audit.record(AuditAction.MEAL_QUARTER_CLOSED, "meal_quarter", closure.getId(), Map.of("year", year,
                "quarter", quarter, "lunches", summary.total().lunches(), "snacks", summary.total().snacks()));
        return quarter(year, quarter).summary();
    }

    @Transactional
    public QuarterDto reopen(int year, int quarter, String reason) {
        var user = CurrentUser.get();
        var closure = closures.findByStructureIdAndYearAndQuarterAndReopenedAtIsNull(user.structureId(), year, quarter)
                .orElseThrow(() -> ApiException.rule("quarter_open", "Ce trimestre n’est pas clos."));
        closure.reopen(clock.instant(), user.displayName(), reason.trim());
        closures.saveAndFlush(closure);
        audit.record(AuditAction.MEAL_QUARTER_REOPENED, "meal_quarter", closure.getId(), Map.of("year", year,
                "quarter", quarter, "reason", reason.trim()));
        return quarter(year, quarter).summary();
    }

    // --- Outils --------------------------------------------------------------------------------

    private static final class MonthAccumulator {
        int openDays;
        int lunches;
        int snacks;
        int packed;
        final Map<UUID, Integer> bySection = new HashMap<>();

        void add(MonthAccumulator other) {
            openDays += other.openDays;
            lunches += other.lunches;
            snacks += other.snacks;
            packed += other.packed;
            other.bySection.forEach((k, v) -> bySection.merge(k, v, Integer::sum));
        }

        MonthRow row(YearMonth month, String label) {
            return new MonthRow(month, label, openDays, Map.copyOf(bySection.entrySet().stream()
                    .filter(e -> e.getKey() != null).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))),
                    lunches, snacks, packed);
        }
    }

    private static String label(YearMonth month) {
        var name = month.getMonth().getDisplayName(TextStyle.FULL, Locale.FRANCE);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1) + " " + month.getYear();
    }

    private QuarterState state(UUID structureId, int year, int quarter) {
        if (closures.findByStructureIdAndYearAndQuarterAndReopenedAtIsNull(structureId, year, quarter).isPresent()) {
            return QuarterState.CLOS;
        }
        var from = LocalDate.of(year, (quarter - 1) * 3 + 1, 1);
        var to = from.plusMonths(3).minusDays(1);
        var today = today();
        return from.isAfter(today) ? QuarterState.A_VENIR : to.isBefore(today) ? QuarterState.A_CLORE : QuarterState.EN_COURS;
    }

    private Map<UUID, HealthInfo> healthByChild(UUID structureId) {
        return healthInfos.findByStructureId(structureId).stream()
                .collect(Collectors.toMap(HealthInfo::getChildId, Function.identity()));
    }

    private ChildDay childDay(Child child, List<ChildAttendance> passages, PlannedDays.Planned planned, HealthInfo health,
                              Map<String, MealAdjustment> adjustmentsByKey) {
        var list = passages.stream().map(a -> new Passage(a.getArrivedAt().atZone(zone).toLocalTime(),
                a.getDepartedAt() == null ? null : a.getDepartedAt().atZone(zone).toLocalTime())).toList();
        var section = passages.isEmpty() || passages.getLast().getSectionId() == null ? child.getSectionId()
                : passages.getLast().getSectionId();
        var lunch = adjustmentsByKey.get(child.getId() + "/" + Meal.REPAS);
        var snack = adjustmentsByKey.get(child.getId() + "/" + Meal.GOUTER);
        return new ChildDay(child.getId(), section, list,
                planned == null || !planned.expected() ? null : planned.arrivesAt(),
                planned == null || !planned.expected() ? null : planned.leavesAt(),
                health != null && health.isPackedLunch(),
                health == null ? Texture.NORMALE : Texture.valueOf(health.getMealTexture()),
                lunch == null ? null : lunch.isServed(), snack == null ? null : snack.isServed());
    }

    private Child loadChild(UUID childId) {
        return children.findByIdAndStructureId(childId, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Enfant introuvable."));
    }

    public Structure structure() {
        return structures.findById(CurrentUser.get().structureId()).orElseThrow();
    }
}
