package nc.ticagou.meal;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.child.Child;
import nc.ticagou.child.ChildRepository;
import nc.ticagou.child.HealthInfoRepository;
import nc.ticagou.common.ApiException;
import nc.ticagou.common.Formats;
import nc.ticagou.meal.KitchenAdvice.Course;
import nc.ticagou.meal.MealDtos.DietDay;
import nc.ticagou.meal.MealDtos.DietRow;
import nc.ticagou.meal.MealDtos.MenuItemDto;
import nc.ticagou.meal.MealDtos.MenuItemInput;
import nc.ticagou.meal.MealDtos.MenuWeekDto;
import nc.ticagou.meal.MealDtos.MenuWeekRequest;
import nc.ticagou.meal.MealDtos.VariantStatus;
import nc.ticagou.structure.OpeningHours;
import nc.ticagou.structure.StructureRepository;

/**
 * Menus de la semaine. Une ligne de variante n'apparaît que pour un régime suivi par au moins un enfant inscrit ;
 * elle est « manquante » quand le plat du jour contient ce que le régime exclut et qu'aucune variante n'est saisie.
 */
@Service
public class MenuService {

    private final MenuWeekRepository weeks;
    private final MenuItemRepository items;
    private final DietService diets;
    private final ChildRepository children;
    private final HealthInfoRepository healthInfos;
    private final StructureRepository structures;
    private final AuditService audit;
    private final Clock clock;

    public MenuService(MenuWeekRepository weeks, MenuItemRepository items, DietService diets, ChildRepository children,
                       HealthInfoRepository healthInfos, StructureRepository structures, AuditService audit,
                       Clock clock) {
        this.weeks = weeks;
        this.items = items;
        this.diets = diets;
        this.children = children;
        this.healthInfos = healthInfos;
        this.structures = structures;
        this.audit = audit;
        this.clock = clock;
    }

    public static LocalDate monday(LocalDate day) {
        return day.with(DayOfWeek.MONDAY);
    }

    @Transactional(readOnly = true)
    public MenuWeekDto week(LocalDate anyDay) {
        return week(CurrentUser.get().structureId(), monday(anyDay));
    }

    /** Plats d'un jour (hors variantes et variantes), pour la vue cuisine. */
    @Transactional(readOnly = true)
    public List<MenuItem> itemsOf(UUID structureId, LocalDate day) {
        return weeks.findByStructureIdAndWeekStart(structureId, monday(day))
                .map(w -> items.findByWeekIdOrderByDayOfWeekAscCourseAsc(w.getId()).stream()
                        .filter(i -> i.getDayOfWeek() == day.getDayOfWeek().getValue()).toList())
                .orElse(List.of());
    }

    @Transactional
    public MenuWeekDto save(LocalDate weekStart, MenuWeekRequest request) {
        var user = CurrentUser.get();
        requireMonday(weekStart);
        var known = diets.byId(user.structureId());
        var seen = new HashSet<String>();
        for (var input : request.items()) {
            boolean variant = input.course() == Course.VARIANTE;
            if (variant != (input.dietId() != null)) {
                throw ApiException.rule("menu_variant", "Une variante se rattache à un régime, et seulement une variante.");
            }
            if (variant && !known.containsKey(input.dietId())) {
                throw ApiException.rule("unknown_diet", "Ce régime n’existe pas dans les paramètres.");
            }
            var unknown = input.tags() == null ? List.<String>of()
                    : input.tags().stream().filter(t -> MealTags.canonical(t) == null).toList();
            if (!unknown.isEmpty()) {
                throw ApiException.rule("unknown_tag", "Étiquette inconnue : %s.".formatted(String.join(", ", unknown)));
            }
            if (!seen.add(input.dayOfWeek() + "/" + input.course() + "/" + input.dietId())) {
                throw ApiException.rule("menu_duplicate", "Un seul plat par jour et par ligne du menu.");
            }
        }
        var week = weeks.findByStructureIdAndWeekStart(user.structureId(), weekStart)
                .orElseGet(() -> new MenuWeek(user.structureId(), weekStart));
        week.touch(request.status(), clock.instant(), user.displayName());
        week = weeks.saveAndFlush(week);
        items.deleteByWeekId(week.getId());
        for (var input : request.items()) {
            items.save(new MenuItem(user.structureId(), week.getId(), input.dayOfWeek(), input.course(), input.dietId(),
                    input.label().trim(), input.tags() == null ? Set.<String>of() : new LinkedHashSet<>(input.tags())));
        }
        audit.record(AuditAction.MENU_SAVED, "menu_week", week.getId(),
                Map.of("weekStart", weekStart.toString(), "status", request.status().name(), "items", request.items().size()));
        return week(user.structureId(), weekStart);
    }

    /** « Copier la semaine précédente » : les plats remplacent ceux de la semaine, qui repasse en brouillon. */
    @Transactional
    public MenuWeekDto copyPrevious(LocalDate weekStart) {
        var user = CurrentUser.get();
        requireMonday(weekStart);
        var previous = weeks.findByStructureIdAndWeekStart(user.structureId(), weekStart.minusWeeks(1))
                .orElseThrow(() -> ApiException.rule("no_previous_week", "La semaine précédente n’a pas de menu."));
        var inputs = items.findByWeekIdOrderByDayOfWeekAscCourseAsc(previous.getId()).stream()
                .map(i -> new MenuItemInput(i.getDayOfWeek(), i.getCourse(), i.getDietId(), i.getLabel(),
                        List.copyOf(i.tagSet())))
                .toList();
        return save(weekStart, new MenuWeekRequest(MenuWeek.Status.BROUILLON, inputs));
    }

    private MenuWeekDto week(UUID structureId, LocalDate weekStart) {
        var structure = structures.findById(structureId).orElseThrow();
        var openDays = structure.getOpeningHours().stream().map(OpeningHours::dayOfWeek).sorted().toList();
        if (openDays.isEmpty()) {
            openDays = List.of(1, 2, 3, 4, 5);
        }
        var week = weeks.findByStructureIdAndWeekStart(structureId, weekStart);
        var weekItems = week.map(w -> items.findByWeekIdOrderByDayOfWeekAscCourseAsc(w.getId())).orElse(List.of());
        var weekEnd = weekStart.plusDays(openDays.getLast() - 1);

        // Régimes suivis par des enfants inscrits pendant la semaine.
        var enrolled = children.findByStructureId(structureId).stream()
                .filter(c -> c.isEnrolledOn(weekStart) || c.isEnrolledOn(weekEnd))
                .collect(Collectors.toMap(Child::getId, c -> c));
        Map<UUID, List<String>> namesByDiet = healthInfos.findByStructureId(structureId).stream()
                .filter(h -> h.getDietId() != null && enrolled.containsKey(h.getChildId()))
                .collect(Collectors.groupingBy(h -> h.getDietId(), Collectors.mapping(
                        h -> enrolled.get(h.getChildId()).displayName(), Collectors.toList())));

        var rows = new ArrayList<DietRow>();
        var warnings = new ArrayList<String>();
        for (var diet : diets.byId(structureId).values().stream()
                .sorted(java.util.Comparator.comparingInt(Diet::getDisplayOrder).thenComparing(Diet::getLabel)).toList()) {
            var names = namesByDiet.get(diet.getId());
            if (names == null) {
                continue;
            }
            var days = new ArrayList<DietDay>();
            for (int day : openDays) {
                var plat = weekItems.stream().filter(i -> i.getDayOfWeek() == day && i.getCourse() == Course.PLAT).findFirst();
                var variant = weekItems.stream().filter(i -> i.getDayOfWeek() == day && i.getCourse() == Course.VARIANTE
                        && Objects.equals(i.getDietId(), diet.getId())).findFirst();
                if (variant.isPresent()) {
                    days.add(new DietDay(day, VariantStatus.VARIANTE, List.of()));
                } else if (plat.isEmpty()) {
                    days.add(new DietDay(day, VariantStatus.SANS_MENU, List.of()));
                } else {
                    var conflicts = plat.get().tagSet().stream().filter(diet.excludedTags()::contains).toList();
                    days.add(new DietDay(day, conflicts.isEmpty() ? VariantStatus.CONVIENT : VariantStatus.MANQUANTE, conflicts));
                    if (!conflicts.isEmpty()) {
                        var date = weekStart.plusDays(day - 1);
                        warnings.add("%s %s : le plat contient %s et %d enfant%s suit le régime « %s » (%s). Saisissez la variante."
                                .formatted(dayName(day), Formats.date(date), String.join(", ", conflicts),
                                        names.size(), names.size() > 1 ? "s" : "", diet.getLabel(), String.join(", ", names)));
                    }
                }
            }
            rows.add(new DietRow(diet.getId(), diet.getLabel(), List.copyOf(diet.excludedTags()), names, days));
        }
        return new MenuWeekDto(weekStart, weekEnd, weekStart.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
                week.map(MenuWeek::getStatus).orElse(MenuWeek.Status.BROUILLON), week.isPresent(),
                week.map(MenuWeek::getUpdatedAt).orElse(null), week.map(MenuWeek::getUpdatedBy).orElse(null), openDays,
                weekItems.stream().map(MenuItemDto::from).toList(), rows, warnings,
                week.map(MenuWeek::getVersion).orElse(0L));
    }

    /** 3 → « Mercredi » */
    static String dayName(int dayOfWeek) {
        var name = DayOfWeek.of(dayOfWeek).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.FRANCE);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static void requireMonday(LocalDate weekStart) {
        if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw ApiException.rule("not_monday", "Une semaine de menus commence un lundi.");
        }
    }
}
