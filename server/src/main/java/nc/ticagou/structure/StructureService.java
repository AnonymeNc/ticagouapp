package nc.ticagou.structure;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import nc.ticagou.license.LicenseService;
import nc.ticagou.child.Child;
import nc.ticagou.child.ChildRepository;
import nc.ticagou.common.ApiException;
import nc.ticagou.common.Iban;
import nc.ticagou.structure.StructureDtos.PassageDto;
import nc.ticagou.structure.StructureDtos.PassageRequest;
import nc.ticagou.structure.StructureDtos.PassageRulesDto;
import nc.ticagou.structure.StructureDtos.SectionDto;
import nc.ticagou.structure.StructureDtos.SectionRow;
import nc.ticagou.structure.StructureDtos.SectionsOverview;
import nc.ticagou.structure.StructureDtos.SectionRequest;
import nc.ticagou.structure.StructureDtos.StructureDto;
import nc.ticagou.structure.StructureDtos.StructureIdentity;
import nc.ticagou.structure.StructureDtos.UpdateStructureRequest;

@Service
public class StructureService {

    static final long MAX_LOGO_BYTES = 1024 * 1024;
    static final Set<String> LOGO_TYPES = Set.of("image/png", "image/jpeg", "image/svg+xml");

    /** Horaires proposés à la création : lundi → vendredi, 06:30 – 17:30 (modifiables dans Paramètres). */
    static final List<OpeningHours> DEFAULT_OPENING_HOURS = List.of(1, 2, 3, 4, 5).stream()
            .map(day -> new OpeningHours(day, LocalTime.of(6, 30), LocalTime.of(17, 30)))
            .toList();

    private final StructureRepository structures;
    private final SectionRepository sections;
    private final ChildRepository children;
    private final AuditService audit;
    private final LicenseService licenses;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public StructureService(StructureRepository structures, SectionRepository sections, ChildRepository children,
                            AuditService audit, LicenseService licenses, ApplicationEventPublisher events, Clock clock) {
        this.licenses = licenses;
        this.structures = structures;
        this.sections = sections;
        this.children = children;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    /** Création au premier lancement (pas d'utilisateur authentifié). */
    @Transactional
    public Structure create(StructureIdentity identity, List<SectionRequest> sectionRequests) {
        var structure = new Structure(identity.name(), identity.capacity());
        structure.updateIdentity(identity.name(), blankToNull(identity.city()), blankToNull(identity.ridet()),
                blankToNull(identity.agrementNumber()), identity.agrementDate(), identity.capacity(), null);
        structure.replaceOpeningHours(DEFAULT_OPENING_HOURS);
        structures.save(structure);

        var ranges = sectionRequests.stream().map(r -> r.range(null)).toList();
        checkSections(ranges, identity.capacity());
        if (sectionRequests.stream().map(r -> r.name().trim().toLowerCase()).distinct().count() != sectionRequests.size()) {
            throw ApiException.rule("section_name_taken", "Deux sections ne peuvent pas porter le même nom.");
        }
        for (var request : sectionRequests) {
            var section = new Section(structure.getId());
            apply(section, request);
            sections.save(section);
        }
        return structure;
    }

    @Transactional(readOnly = true)
    public StructureDto current() {
        return StructureDto.from(load(CurrentUser.get().structureId()));
    }

    @Transactional
    public StructureDto update(UpdateStructureRequest request) {
        var structure = load(CurrentUser.get().structureId());
        var identity = request.identity();
        if (identity.capacity() > structure.getCapacity()) {
            licenses.requireCapacity(identity.capacity());
        }
        var existing = sections.findByStructureIdOrderByDisplayOrderAscMinAgeMonthsAsc(structure.getId());
        SectionRules.checkCapacity(existing.stream().map(Section::range).toList(), identity.capacity())
                .ifPresent(message -> {
                    throw ApiException.rule("capacity_exceeded", message);
                });
        structure.updateIdentity(identity.name().trim(), blankToNull(identity.city()), blankToNull(identity.ridet()),
                blankToNull(identity.agrementNumber()), identity.agrementDate(), identity.capacity(),
                blankToNull(request.invoiceFooter()));
        structure.replaceOpeningHours(request.openingHours().stream().map(StructureDtos.OpeningHoursDto::toEntity)
                .sorted((a, b) -> Integer.compare(a.dayOfWeek(), b.dayOfWeek())).toList());
        if (request.contact() != null) {
            var c = request.contact();
            structure.updateContact(blankToNull(c.address()), blankToNull(c.postalCode()), blankToNull(c.phone()),
                    blankToNull(c.email()));
        }
        if (request.bank() != null) {
            var b = request.bank();
            structure.updateBank(blankToNull(b.holder()), blankToNull(b.bankName()), blankToNull(Iban.normalize(b.iban())),
                    b.bic() == null || b.bic().isBlank() ? null : b.bic().trim().toUpperCase(Locale.ROOT), b.printRib());
        }
        structures.saveAndFlush(structure);
        audit.record(AuditAction.STRUCTURE_UPDATED, "structure", structure.getId(), Map.of());
        events.publishEvent(new PortalChange(structure.getId(), PortalItemType.STRUCTURE, structure.getId()));
        return StructureDto.from(structure);
    }

    @Transactional(readOnly = true)
    public Logo logo() {
        var structure = load(CurrentUser.get().structureId());
        if (!structure.hasLogo()) {
            throw ApiException.notFound("Aucun logo n’est enregistré.");
        }
        return new Logo(structure.getLogo(), structure.getLogoContentType());
    }

    @Transactional
    public void replaceLogo(byte[] content, String contentType) {
        if (content.length == 0 || content.length > MAX_LOGO_BYTES) {
            throw ApiException.rule("logo_size", "Le logo doit peser au plus 1 Mo.");
        }
        if (contentType == null || !LOGO_TYPES.contains(contentType)) {
            throw ApiException.rule("logo_type", "Le logo doit être une image PNG, JPEG ou SVG.");
        }
        var structure = load(CurrentUser.get().structureId());
        structure.replaceLogo(content, contentType);
        audit.record(AuditAction.LOGO_UPDATED, "structure", structure.getId(),
                Map.of("contentType", contentType, "bytes", content.length));
    }

    @Transactional
    public void removeLogo() {
        var structure = load(CurrentUser.get().structureId());
        structure.removeLogo();
        audit.record(AuditAction.LOGO_REMOVED, "structure", structure.getId(), Map.of());
    }

    @Transactional(readOnly = true)
    public List<SectionDto> sections() {
        return sections.findByStructureIdOrderByDisplayOrderAscMinAgeMonthsAsc(CurrentUser.get().structureId())
                .stream().map(SectionDto::from).toList();
    }

    @Transactional
    public SectionDto createSection(SectionRequest request) {
        var structure = load(CurrentUser.get().structureId());
        if (sections.existsByStructureIdAndNameIgnoreCase(structure.getId(), request.name().trim())) {
            throw ApiException.conflict("section_name_taken", "Une section porte déjà ce nom.");
        }
        var ranges = new ArrayList<>(currentRanges(structure.getId(), null));
        ranges.add(request.range(null));
        checkSections(ranges, structure.getCapacity());

        var section = new Section(structure.getId());
        apply(section, request);
        sections.saveAndFlush(section);
        audit.record(AuditAction.SECTION_CREATED, "section", section.getId(), Map.of("name", section.getName()));
        return SectionDto.from(section);
    }

    @Transactional
    public SectionDto updateSection(UUID id, SectionRequest request) {
        var structure = load(CurrentUser.get().structureId());
        var section = sections.findByIdAndStructureId(id, structure.getId())
                .orElseThrow(() -> ApiException.notFound("Section introuvable."));
        if (sections.existsByStructureIdAndNameIgnoreCaseAndIdNot(structure.getId(), request.name().trim(), id)) {
            throw ApiException.conflict("section_name_taken", "Une section porte déjà ce nom.");
        }
        var ranges = new ArrayList<>(currentRanges(structure.getId(), id));
        ranges.add(request.range(id));
        checkSections(ranges, structure.getCapacity());

        apply(section, request);
        sections.saveAndFlush(section);
        audit.record(AuditAction.SECTION_UPDATED, "section", section.getId(), Map.of("name", section.getName()));
        return SectionDto.from(section);
    }

    @Transactional
    public void deleteSection(UUID id) {
        var structureId = CurrentUser.get().structureId();
        var section = sections.findByIdAndStructureId(id, structureId)
                .orElseThrow(() -> ApiException.notFound("Section introuvable."));
        if (children.existsBySectionId(id)) {
            throw ApiException.rule("section_in_use",
                    "Des enfants sont rattachés à cette section : changez-les de section avant de la supprimer.");
        }
        sections.delete(section);
        audit.record(AuditAction.SECTION_DELETED, "section", id, Map.of("name", section.getName()));
    }

    @Transactional(readOnly = true)
    public SectionsOverview sectionsOverview() {
        var structure = load(CurrentUser.get().structureId());
        var today = LocalDate.now(clock);
        var list = sections.findByStructureIdOrderByDisplayOrderAscMinAgeMonthsAsc(structure.getId());
        var enrolled = children.findByStructureId(structure.getId()).stream()
                .filter(c -> c.isEnrolledOn(today) && c.getSectionId() != null).toList();
        var counts = enrolled.stream().collect(Collectors.groupingBy(Child::getSectionId,
                Collectors.counting()));
        var ranges = list.stream().map(Section::range).toList();
        var passages = !structure.isPassagePropose() ? List.<PassageDto>of()
                : SectionRules.passages(ranges, enrolled.stream()
                                .map(c -> new SectionRules.EnrolledChild(c.getId(), c.displayName(), c.getBirthDate(), c.getSectionId()))
                                .toList(), today, structure.isPassageFirstOfMonth(), structure.isPassageBlockFull())
                        .stream().map(p -> new PassageDto(p.childId(), p.name(), p.fromSectionId(), p.toSectionId(), p.on(),
                                p.ageMonths(), p.blocked())).toList();
        return new SectionsOverview(
                list.stream().map(s -> new SectionRow(SectionDto.from(s), counts.getOrDefault(s.getId(), 0L).intValue())).toList(),
                list.stream().mapToInt(Section::getCapacity).sum(), structure.getCapacity(), SectionRules.findGaps(ranges),
                new PassageRulesDto(structure.isPassagePropose(), structure.isPassageFirstOfMonth(), structure.isPassageBlockFull()),
                passages);
    }

    @Transactional
    public SectionsOverview updatePassageRules(PassageRulesDto rules) {
        var structure = load(CurrentUser.get().structureId());
        structure.updatePassageRules(rules.propose(), rules.firstOfMonth(), rules.blockFull());
        structures.saveAndFlush(structure);
        audit.record(AuditAction.SECTION_PASSAGE_RULES_UPDATED, "structure", structure.getId(), Map.of(
                "propose", rules.propose(), "firstOfMonth", rules.firstOfMonth(), "blockFull", rules.blockFull()));
        return sectionsOverview();
    }

    /** Passage validé par la direction : l'enfant change de section (tracé). */
    @Transactional
    public SectionsOverview applyPassage(PassageRequest request) {
        var structure = load(CurrentUser.get().structureId());
        var child = children.findByIdAndStructureId(request.childId(), structure.getId())
                .orElseThrow(() -> ApiException.notFound("Enfant introuvable."));
        var target = sections.findByIdAndStructureId(request.toSectionId(), structure.getId())
                .orElseThrow(() -> ApiException.notFound("Section introuvable."));
        var today = LocalDate.now(clock);
        if (structure.isPassageBlockFull()) {
            long inTarget = children.findByStructureId(structure.getId()).stream()
                    .filter(c -> c.isEnrolledOn(today) && target.getId().equals(c.getSectionId())).count();
            if (inTarget >= target.getCapacity()) {
                throw ApiException.rule("section_full", "La section « %s » est complète (%d places) : le passage est bloqué."
                        .formatted(target.getName(), target.getCapacity()));
            }
        }
        var from = child.getSectionId();
        child.update(child.getFirstName(), child.getLastName(), child.getBirthDate(), target.getId(), child.getEntryDate(),
                child.getExitDate(), child.getHomeLanguages());
        children.saveAndFlush(child);
        audit.record(AuditAction.CHILD_SECTION_CHANGED, "child", child.getId(), Map.of("from", String.valueOf(from),
                "to", target.getName()));
        return sectionsOverview();
    }

    private List<SectionRules.Range> currentRanges(UUID structureId, UUID excludedId) {
        return sections.findByStructureIdOrderByDisplayOrderAscMinAgeMonthsAsc(structureId).stream()
                .filter(s -> !s.getId().equals(excludedId))
                .map(Section::range)
                .toList();
    }

    private static void checkSections(List<SectionRules.Range> ranges, int capacity) {
        SectionRules.findOverlap(ranges).ifPresent(message -> {
            throw ApiException.rule("section_overlap", message);
        });
        SectionRules.checkCapacity(ranges, capacity).ifPresent(message -> {
            throw ApiException.rule("capacity_exceeded", message);
        });
    }

    private static void apply(Section section, SectionRequest request) {
        section.update(request.name().trim(), request.minAgeMonths(), request.maxAgeMonths(), request.capacity(),
                request.color(), request.displayOrder());
    }

    private Structure load(UUID id) {
        return structures.findById(id).orElseThrow(() -> ApiException.notFound("Structure introuvable."));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record Logo(byte[] content, String contentType) {
    }
}
