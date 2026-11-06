package nc.ticagou.family;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.portal.PortalChange;
import nc.ticagou.portal.PortalItemType;
import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.common.ApiException;
import nc.ticagou.family.FamilyDtos.FamilyRequest;
import nc.ticagou.family.FamilyDtos.FamilySummary;
import nc.ticagou.family.FamilyDtos.GuardianDto;
import nc.ticagou.family.FamilyDtos.GuardianRequest;

/** Foyers et responsables (direction uniquement). */
@Service
public class FamilyService {

    private final FamilyRepository families;
    private final GuardianRepository guardians;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public FamilyService(FamilyRepository families, GuardianRepository guardians, AuditService audit,
                         ApplicationEventPublisher events) {
        this.events = events;
        this.families = families;
        this.guardians = guardians;
        this.audit = audit;
    }

    public Family load(UUID id) {
        return families.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Famille introuvable."));
    }

    /** Recherche par libellé ou nom d'un responsable, pour rattacher un enfant à un foyer existant. */
    @Transactional(readOnly = true)
    public List<FamilySummary> search(String query) {
        var structureId = CurrentUser.get().structureId();
        var all = families.findByStructureIdOrderByLabelAsc(structureId);
        Map<UUID, List<String>> names = guardians.findByFamilyIdIn(all.stream().map(Family::getId).toList()).stream()
                .collect(Collectors.groupingBy(Guardian::getFamilyId,
                        Collectors.mapping(g -> g.getFirstName() + " " + g.getLastName(), Collectors.toList())));
        var needle = normalize(query);
        return all.stream()
                .map(f -> new FamilySummary(f.getId(), f.getLabel(), f.getCity(), names.getOrDefault(f.getId(), List.of())))
                .filter(s -> needle.isEmpty() || normalize(s.label() + " " + String.join(" ", s.guardianNames()))
                        .contains(needle))
                .toList();
    }

    /** Nouveau foyer avec son premier responsable. */
    @Transactional
    public Family create(FamilyRequest request, GuardianRequest firstGuardian) {
        var family = new Family(CurrentUser.get().structureId());
        apply(family, request);
        families.saveAndFlush(family);
        var guardian = new Guardian(family.getStructureId(), family.getId());
        apply(guardian, firstGuardian);
        guardians.save(guardian);
        audit.record(AuditAction.FAMILY_CREATED, "family", family.getId(), Map.of());
        return family;
    }

    @Transactional
    public void update(UUID id, FamilyRequest request) {
        var family = load(id);
        apply(family, request);
        families.saveAndFlush(family);
        audit.record(AuditAction.FAMILY_UPDATED, "family", id, Map.of());
    }

    @Transactional
    public GuardianDto addGuardian(UUID familyId, GuardianRequest request) {
        var family = load(familyId);
        var guardian = new Guardian(family.getStructureId(), familyId);
        apply(guardian, request);
        guardians.saveAndFlush(guardian);
        audit.record(AuditAction.GUARDIAN_CREATED, "guardian", guardian.getId(), Map.of("family", familyId));
        return GuardianDto.of(guardian);
    }

    @Transactional
    public GuardianDto updateGuardian(UUID id, GuardianRequest request) {
        var guardian = loadGuardian(id);
        if (guardian.isLegalGuardian() && !request.legalGuardian()) {
            requireAnotherLegalGuardian(guardian);
        }
        apply(guardian, request);
        guardians.saveAndFlush(guardian);
        audit.record(AuditAction.GUARDIAN_UPDATED, "guardian", id, Map.of("family", guardian.getFamilyId()));
        events.publishEvent(new PortalChange(guardian.getStructureId(), PortalItemType.GUARDIAN, id));
        return GuardianDto.of(guardian);
    }

    @Transactional
    public void deleteGuardian(UUID id) {
        var guardian = loadGuardian(id);
        if (guardian.isLegalGuardian()) {
            requireAnotherLegalGuardian(guardian);
        }
        guardians.delete(guardian);
        audit.record(AuditAction.GUARDIAN_DELETED, "guardian", id, Map.of("family", guardian.getFamilyId()));
        events.publishEvent(new PortalChange(guardian.getStructureId(), PortalItemType.GUARDIAN, id));
    }

    /** Un foyer garde toujours au moins un responsable légal (personne autorisée par défaut au départ). */
    private void requireAnotherLegalGuardian(Guardian guardian) {
        if (guardians.countByFamilyIdAndLegalGuardianTrue(guardian.getFamilyId()) <= 1) {
            throw ApiException.rule("last_legal_guardian", "La famille doit garder au moins un responsable légal.");
        }
    }

    private Guardian loadGuardian(UUID id) {
        return guardians.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Responsable introuvable."));
    }

    private static void apply(Family family, FamilyRequest r) {
        family.update(r.label().trim(), blankToNull(r.address()), blankToNull(r.postalCode()), blankToNull(r.city()),
                blankToNull(r.situation()), blankToNull(r.notes()));
    }

    private static void apply(Guardian guardian, GuardianRequest r) {
        guardian.update(r.firstName().trim(), r.lastName().trim(), r.relationship().trim(), r.legalGuardian(),
                r.payer(), blankToNull(r.mobilePhone()), blankToNull(r.workPhone()), blankToNull(r.email()),
                blankToNull(r.employer()), r.displayOrder());
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
