package nc.ticagou.enrollment;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.common.ApiException;
import nc.ticagou.enrollment.EnrollmentDtos.CriterionDto;
import nc.ticagou.enrollment.EnrollmentDtos.CriterionRequest;

/** Critères de priorité des demandes (Paramètres › Critères d'inscription). */
@Service
public class EnrollmentCriterionService {

    /** Critères proposés à l'installation (maquette Fiche demande), modifiables ensuite. */
    static final List<Map.Entry<String, Integer>> DEFAULTS = List.of(
            Map.entry("Fratrie déjà accueillie", 3),
            Map.entry("Famille domiciliée dans la commune", 2),
            Map.entry("Parents en activité ou en formation", 2),
            Map.entry("Parent seul", 2),
            Map.entry("Situation signalée par un service social", 3),
            Map.entry("Besoin d’accueil particulier", 2));

    private final EnrollmentCriterionRepository criteria;
    private final AuditService audit;

    public EnrollmentCriterionService(EnrollmentCriterionRepository criteria, AuditService audit) {
        this.criteria = criteria;
        this.audit = audit;
    }

    @Transactional
    public void createDefaults(UUID structureId, LocalDate validFrom) {
        for (int i = 0; i < DEFAULTS.size(); i++) {
            var criterion = new EnrollmentCriterion(structureId);
            criterion.update(DEFAULTS.get(i).getKey(), DEFAULTS.get(i).getValue(), validFrom, null, i);
            criteria.save(criterion);
        }
        criteria.flush();
    }

    @Transactional(readOnly = true)
    public List<CriterionDto> list() {
        return criteria.findByStructureIdOrderByDisplayOrderAscLabelAsc(CurrentUser.get().structureId()).stream()
                .map(CriterionDto::from).toList();
    }

    @Transactional
    public CriterionDto create(CriterionRequest request) {
        var criterion = new EnrollmentCriterion(CurrentUser.get().structureId());
        apply(criterion, request);
        audit.record(AuditAction.ENROLLMENT_CRITERION_CREATED, "enrollment_criterion", criterion.getId(),
                Map.of("points", criterion.getPoints()));
        return CriterionDto.from(criterion);
    }

    @Transactional
    public CriterionDto update(UUID id, CriterionRequest request) {
        var criterion = load(id);
        apply(criterion, request);
        audit.record(AuditAction.ENROLLMENT_CRITERION_UPDATED, "enrollment_criterion", id,
                Map.of("points", criterion.getPoints()));
        return CriterionDto.from(criterion);
    }

    /** Supprime le critère ; il disparaît des demandes qui l'avaient coché (pour garder l'historique, fixer plutôt une date de fin). */
    @Transactional
    public void delete(UUID id) {
        criteria.delete(load(id));
        audit.record(AuditAction.ENROLLMENT_CRITERION_DELETED, "enrollment_criterion", id, Map.of());
    }

    private void apply(EnrollmentCriterion criterion, CriterionRequest r) {
        criterion.update(r.label().trim(), r.points(), r.validFrom(), r.validTo(), r.displayOrder());
        criteria.saveAndFlush(criterion);
    }

    private EnrollmentCriterion load(UUID id) {
        return criteria.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Critère introuvable."));
    }
}
