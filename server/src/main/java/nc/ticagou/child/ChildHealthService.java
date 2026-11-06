package nc.ticagou.child;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.portal.PortalChange;
import nc.ticagou.portal.PortalItemType;
import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.child.ChildDtos.ConsentDto;
import nc.ticagou.child.ChildDtos.HealthDto;
import nc.ticagou.meal.Diet;
import nc.ticagou.meal.DietRepository;
import nc.ticagou.common.ApiException;

/** Santé (allergies, régime, PAI, vaccins, médecin, consignes) et autorisations parentales. */
@Service
public class ChildHealthService {

    private final ChildService children;
    private final HealthInfoRepository healthInfos;
    private final AllergyRepository allergies;
    private final VaccinationRepository vaccinations;
    private final ConsentRepository consents;
    private final ChildDocumentRepository documents;
    private final ChildRecordAssembler assembler;
    private final AuditService audit;
    private final DietRepository diets;
    private final ApplicationEventPublisher events;

    public ChildHealthService(ChildService children, HealthInfoRepository healthInfos, AllergyRepository allergies,
                              VaccinationRepository vaccinations, ConsentRepository consents,
                              ChildDocumentRepository documents, ChildRecordAssembler assembler, AuditService audit,
                              DietRepository diets, ApplicationEventPublisher events) {
        this.events = events;
        this.diets = diets;
        this.children = children;
        this.healthInfos = healthInfos;
        this.allergies = allergies;
        this.vaccinations = vaccinations;
        this.consents = consents;
        this.documents = documents;
        this.assembler = assembler;
        this.audit = audit;
    }

    /**
     * Régime de la liste : choisi par son identifiant, ou reconnu à son libellé saisi en clair (« sans porc »).
     * Un régime absent de la liste reste en texte libre, sans contrôle des menus.
     */
    private Diet resolveDiet(UUID structureId, UUID dietId, String label) {
        if (dietId != null) {
            return diets.findByIdAndStructureId(dietId, structureId)
                    .orElseThrow(() -> ApiException.rule("unknown_diet", "Ce régime n’existe pas dans les paramètres."));
        }
        var text = ChildService.blankToNull(label);
        return text == null ? null : diets.findByStructureIdOrderByDisplayOrderAscLabelAsc(structureId).stream()
                .filter(d -> d.getLabel().equalsIgnoreCase(text)).findFirst().orElse(null);
    }

    /** Remplace toutes les informations de santé (formulaire unique de l'onglet Santé). */
    @Transactional
    public HealthDto update(UUID childId, HealthDto request) {
        var child = children.load(childId);
        if (request.paiDocumentId() != null) {
            documents.findByIdAndStructureId(request.paiDocumentId(), child.getStructureId())
                    .filter(d -> d.getChildId().equals(childId))
                    .orElseThrow(() -> ApiException.rule("unknown_document", "Le document du PAI est introuvable."));
        }
        var info = healthInfos.findById(childId).orElseGet(() -> new HealthInfo(childId, child.getStructureId()));
        var diet = resolveDiet(child.getStructureId(), request.dietId(), request.diet());
        info.updateMeals(diet == null ? null : diet.getId(), request.mealTexture(), Boolean.TRUE.equals(request.packedLunch()));
        info.update(diet != null ? diet.getLabel() : ChildService.blankToNull(request.diet()),
                ChildService.blankToNull(request.dietDetails()),
                request.paiActive(), ChildService.blankToNull(request.paiReason()), request.paiValidFrom(),
                request.paiValidUntil(), ChildService.blankToNull(request.paiKitLocation()),
                ChildService.blankToNull(request.paiKitDetails()), request.paiDocumentId(),
                ChildService.blankToNull(request.emergencyProtocol()), ChildService.blankToNull(request.doctorName()),
                ChildService.blankToNull(request.doctorPhone()), ChildService.blankToNull(request.doctorAddress()),
                ChildService.blankToNull(request.instructions()), request.vaccinationCheckedOn());
        healthInfos.save(info);

        allergies.deleteByChildIdIn(List.of(childId));
        vaccinations.deleteByChildIdIn(List.of(childId));
        var allergyList = request.allergies();
        allergies.saveAll(IntStream.range(0, allergyList.size())
                .mapToObj(i -> new Allergy(child.getStructureId(), childId, allergyList.get(i).allergen().trim(),
                        ChildService.blankToNull(allergyList.get(i).reaction()), allergyList.get(i).severity(), i))
                .toList());
        var vaccinationList = request.vaccinations();
        vaccinations.saveAll(IntStream.range(0, vaccinationList.size())
                .mapToObj(i -> new Vaccination(child.getStructureId(), childId,
                        vaccinationList.get(i).vaccine().trim(), vaccinationList.get(i).lastDoseOn(), i))
                .toList());

        audit.record(AuditAction.CHILD_HEALTH_UPDATED, "child", childId, Map.of(
                "allergies", allergyList.size(), "pai", request.paiActive()));
        return assembler.health(childId);
    }

    @Transactional
    public List<ConsentDto> updateConsents(UUID childId, List<ConsentDto> request) {
        var child = children.load(childId);
        var existing = consents.findByChildId(childId);
        for (var dto : request) {
            var consent = existing.stream().filter(c -> c.getType() == dto.type()).findFirst()
                    .orElseGet(() -> new Consent(child.getStructureId(), childId, dto.type()));
            consent.update(dto.granted(), ChildService.blankToNull(dto.details()), dto.signedOn(),
                    ChildService.blankToNull(dto.signedBy()));
            consents.save(consent);
        }
        audit.record(AuditAction.CHILD_CONSENTS_UPDATED, "child", childId, Map.of(
                "consents", request.stream().map(c -> c.type() + "=" + c.granted()).toList()));
        events.publishEvent(new PortalChange(child.getStructureId(), PortalItemType.CHILD, childId));
        return consents.findByChildId(childId).stream().map(ConsentDto::of).toList();
    }
}
