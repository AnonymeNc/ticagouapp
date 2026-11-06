package nc.ticagou.meal;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.child.ChildRepository;
import nc.ticagou.child.HealthInfo;
import nc.ticagou.child.HealthInfoRepository;
import nc.ticagou.common.ApiException;
import nc.ticagou.meal.MealDtos.DietDto;
import nc.ticagou.meal.MealDtos.DietRequest;
import nc.ticagou.meal.MealDtos.MealSettings;
import nc.ticagou.structure.StructureRepository;

/** Paramètres › Repas et régimes : régimes et ce qu'ils excluent, heures du repas et du goûter. */
@Service
public class DietService {

    /** Régimes créés au premier lancement. */
    static final List<Map.Entry<String, List<String>>> DEFAULTS = List.of(
            Map.entry("Sans porc", List.of("porc")),
            Map.entry("Végétarien", List.of("viande", "poisson", "porc", "crustacés", "mollusques")),
            Map.entry("Sans lactose", List.of("lait")),
            Map.entry("Sans gluten", List.of("gluten")));

    private final DietRepository diets;
    private final HealthInfoRepository healthInfos;
    private final ChildRepository children;
    private final StructureRepository structures;
    private final AuditService audit;
    private final Clock clock;

    public DietService(DietRepository diets, HealthInfoRepository healthInfos, ChildRepository children,
                       StructureRepository structures, AuditService audit, Clock clock) {
        this.diets = diets;
        this.healthInfos = healthInfos;
        this.children = children;
        this.structures = structures;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<DietDto> list() {
        var structureId = CurrentUser.get().structureId();
        var today = LocalDate.now(clock);
        var enrolled = children.findByStructureId(structureId).stream().filter(c -> c.isEnrolledOn(today))
                .map(c -> c.getId()).collect(Collectors.toSet());
        Map<UUID, Long> counts = healthInfos.findByStructureId(structureId).stream()
                .filter(h -> h.getDietId() != null && enrolled.contains(h.getChildId()))
                .collect(Collectors.groupingBy(HealthInfo::getDietId, Collectors.counting()));
        return diets.findByStructureIdOrderByDisplayOrderAscLabelAsc(structureId).stream()
                .map(d -> new DietDto(d.getId(), d.getLabel(), List.copyOf(d.excludedTags()), d.getDisplayOrder(),
                        counts.getOrDefault(d.getId(), 0L), d.getVersion()))
                .toList();
    }

    @Transactional
    public void createDefaults(UUID structureId) {
        for (int i = 0; i < DEFAULTS.size(); i++) {
            var diet = new Diet(structureId);
            diet.update(DEFAULTS.get(i).getKey(), new LinkedHashSet<>(DEFAULTS.get(i).getValue()), i);
            diets.save(diet);
        }
    }

    @Transactional
    public DietDto create(DietRequest request) {
        var diet = new Diet(CurrentUser.get().structureId());
        apply(diet, request);
        audit.record(AuditAction.DIET_CREATED, "diet", diet.getId(), Map.of("label", diet.getLabel()));
        return toDto(diet);
    }

    @Transactional
    public DietDto update(UUID id, DietRequest request) {
        var diet = load(id);
        apply(diet, request);
        audit.record(AuditAction.DIET_UPDATED, "diet", id, Map.of("label", diet.getLabel()));
        return toDto(diet);
    }

    /** Les fiches qui le portaient gardent son libellé en texte libre ; ses variantes de menu disparaissent. */
    @Transactional
    public void delete(UUID id) {
        var diet = load(id);
        diets.delete(diet);
        audit.record(AuditAction.DIET_DELETED, "diet", id, Map.of("label", diet.getLabel()));
    }

    @Transactional(readOnly = true)
    public MealSettings settings() {
        var structure = structures.findById(CurrentUser.get().structureId()).orElseThrow();
        return new MealSettings(structure.getLunchTime(), structure.getSnackTime(), MealTags.ALL);
    }

    @Transactional
    public MealSettings updateSettings(MealSettings request) {
        var structure = structures.findById(CurrentUser.get().structureId()).orElseThrow();
        structure.setMealTimes(request.lunchTime(), request.snackTime());
        audit.record(AuditAction.MEAL_SETTINGS_UPDATED, "structure", structure.getId(),
                Map.of("lunchTime", request.lunchTime().toString(), "snackTime", request.snackTime().toString()));
        return new MealSettings(structure.getLunchTime(), structure.getSnackTime(), MealTags.ALL);
    }

    private void apply(Diet diet, DietRequest request) {
        var unknown = request.excludes().stream().filter(t -> MealTags.canonical(t) == null).toList();
        if (!unknown.isEmpty()) {
            throw ApiException.rule("unknown_tag", "Étiquette inconnue : %s.".formatted(String.join(", ", unknown)));
        }
        var label = request.label().trim();
        boolean duplicate = diets.findByStructureIdOrderByDisplayOrderAscLabelAsc(diet.getStructureId()).stream()
                .anyMatch(d -> !Objects.equals(d.getId(), diet.getId()) && d.getLabel().equalsIgnoreCase(label));
        if (duplicate) {
            throw ApiException.conflict("diet_exists", "Ce régime existe déjà.");
        }
        diet.update(label, new LinkedHashSet<>(request.excludes()), request.displayOrder());
        diets.saveAndFlush(diet);
    }

    private DietDto toDto(Diet diet) {
        return list().stream().filter(d -> d.id().equals(diet.getId())).findFirst()
                .orElseGet(() -> new DietDto(diet.getId(), diet.getLabel(), List.copyOf(diet.excludedTags()),
                        diet.getDisplayOrder(), 0, diet.getVersion()));
    }

    private Diet load(UUID id) {
        return diets.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Régime introuvable."));
    }

    /** Régimes de la structure par identifiant. */
    @Transactional(readOnly = true)
    public Map<UUID, Diet> byId(UUID structureId) {
        return diets.findByStructureIdOrderByDisplayOrderAscLabelAsc(structureId).stream()
                .collect(Collectors.toMap(Diet::getId, Function.identity()));
    }
}
