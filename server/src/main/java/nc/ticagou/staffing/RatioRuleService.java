package nc.ticagou.staffing;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.common.ApiException;
import nc.ticagou.staffing.StaffingDtos.RatioRuleDto;
import nc.ticagou.staffing.StaffingDtos.RatioRuleRequest;
import nc.ticagou.staffing.StaffingDtos.SettingsDto;
import nc.ticagou.structure.StructureRepository;

/**
 * Paramètres › Règles d'encadrement : ratios de l'agrément provincial, datés. Une règle « À venir »
 * remplace l'ancienne à sa date de début ; deux règles ne couvrent jamais la même tranche le même jour.
 */
@Service
public class RatioRuleService {

    /** Règles créées au premier lancement (maquette Paramètres). */
    static final List<RatioCalculator.Rule> DEFAULTS = List.of(
            new RatioCalculator.Rule("Enfants qui ne marchent pas", 0, 18, 5, null, null),
            new RatioCalculator.Rule("Enfants qui marchent", 18, null, 8, null, null));

    private final RatioRuleRepository rules;
    private final StructureRepository structures;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public RatioRuleService(RatioRuleRepository rules, StructureRepository structures, AuditService audit,
                            ApplicationEventPublisher events, Clock clock) {
        this.rules = rules;
        this.structures = structures;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<RatioRuleDto> list() {
        var today = LocalDate.now(clock);
        return rules.findByStructureIdOrderByMinAgeMonthsAscValidFromDesc(CurrentUser.get().structureId()).stream()
                .map(r -> RatioRuleDto.from(r, today)).toList();
    }

    /** Règles par défaut d'une nouvelle structure, en vigueur dès son installation. */
    @Transactional
    public void createDefaults(UUID structureId, LocalDate from) {
        for (var d : DEFAULTS) {
            var rule = new RatioRule(structureId);
            rule.update(d.label(), d.minAgeMonths(), d.maxAgeMonths(), d.childrenPerAdult(), from, null);
            rules.save(rule);
        }
    }

    @Transactional
    public RatioRuleDto create(RatioRuleRequest request) {
        var rule = new RatioRule(CurrentUser.get().structureId());
        apply(rule, request);
        audit.record(AuditAction.RATIO_RULE_CREATED, "ratio_rule", rule.getId(), describe(rule));
        return RatioRuleDto.from(rule, LocalDate.now(clock));
    }

    @Transactional
    public RatioRuleDto update(UUID id, RatioRuleRequest request) {
        var rule = load(id);
        apply(rule, request);
        audit.record(AuditAction.RATIO_RULE_UPDATED, "ratio_rule", id, describe(rule));
        return RatioRuleDto.from(rule, LocalDate.now(clock));
    }

    @Transactional
    public void delete(UUID id) {
        var rule = load(id);
        rules.delete(rule);
        audit.record(AuditAction.RATIO_RULE_DELETED, "ratio_rule", id, describe(rule));
        events.publishEvent(new StaffingChanged(rule.getStructureId()));
    }

    @Transactional(readOnly = true)
    public SettingsDto settings() {
        return new SettingsDto(structures.findById(CurrentUser.get().structureId()).orElseThrow().isRatioAlertDesktop());
    }

    @Transactional
    public SettingsDto updateSettings(SettingsDto request) {
        var structure = structures.findById(CurrentUser.get().structureId()).orElseThrow();
        structure.setRatioAlertDesktop(request.alertDesktop());
        audit.record(AuditAction.RATIO_SETTINGS_UPDATED, "structure", structure.getId(),
                Map.of("alertDesktop", request.alertDesktop()));
        return new SettingsDto(structure.isRatioAlertDesktop());
    }

    private void apply(RatioRule rule, RatioRuleRequest request) {
        rule.update(request.label().trim(), request.minAgeMonths(), request.maxAgeMonths(),
                request.childrenPerAdult(), request.validFrom(), request.validTo());
        for (var other : rules.findByStructureIdOrderByMinAgeMonthsAscValidFromDesc(rule.getStructureId())) {
            if (!other.getId().equals(rule.getId()) && rule.overlaps(other)) {
                throw ApiException.rule("ratio_rule_overlap",
                        "Cette règle recouvre « %s » sur la même tranche d’âge et la même période. Fixez une date de fin à l’ancienne règle."
                                .formatted(other.getLabel()));
            }
        }
        rules.saveAndFlush(rule);
        events.publishEvent(new StaffingChanged(rule.getStructureId()));
    }

    private RatioRule load(UUID id) {
        return rules.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Règle d’encadrement introuvable."));
    }

    private static Map<String, Object> describe(RatioRule rule) {
        return Map.of("label", rule.getLabel(), "childrenPerAdult", rule.getChildrenPerAdult(),
                "validFrom", rule.getValidFrom().toString());
    }
}
