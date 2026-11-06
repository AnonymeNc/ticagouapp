package nc.ticagou.child;

import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
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
import nc.ticagou.audit.ChildAccessContext;
import nc.ticagou.audit.ChildAccessLogger;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.child.ChildDtos.AllergySummary;
import nc.ticagou.child.ChildDtos.ChildIdentityRequest;
import nc.ticagou.child.ChildDtos.ChildListItem;
import nc.ticagou.child.ChildDtos.ChildRecord;
import nc.ticagou.child.ChildDtos.CreateChildRequest;
import nc.ticagou.child.ChildDtos.HealthSummary;
import nc.ticagou.common.ApiException;
import nc.ticagou.common.CounterService;
import nc.ticagou.document.EncryptedFileStore;
import nc.ticagou.document.StoredFileRepository;
import nc.ticagou.document.Uploads;
import nc.ticagou.family.FamilyService;
import nc.ticagou.structure.SectionRepository;

@Service
public class ChildService {

    private final ChildRepository children;
    private final SectionRepository sections;
    private final HealthInfoRepository healthInfos;
    private final AllergyRepository allergies;
    private final FamilyService families;
    private final ChildRecordAssembler assembler;
    private final EncryptedFileStore fileStore;
    private final StoredFileRepository files;
    private final CounterService counters;
    private final AuditService audit;
    private final ChildAccessLogger accessLog;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ChildService(ChildRepository children, SectionRepository sections, HealthInfoRepository healthInfos,
                        AllergyRepository allergies, FamilyService families, ChildRecordAssembler assembler,
                        EncryptedFileStore fileStore, StoredFileRepository files, CounterService counters,
                        AuditService audit, ChildAccessLogger accessLog, ApplicationEventPublisher events, Clock clock) {
        this.children = children;
        this.sections = sections;
        this.healthInfos = healthInfos;
        this.allergies = allergies;
        this.families = families;
        this.assembler = assembler;
        this.fileStore = fileStore;
        this.files = files;
        this.counters = counters;
        this.audit = audit;
        this.accessLog = accessLog;
        this.events = events;
        this.clock = clock;
    }

    /** Liste filtrée ; les informations de santé critiques accompagnent toujours chaque enfant. */
    @Transactional(readOnly = true)
    public List<ChildListItem> list(String query, UUID sectionId, boolean withAllergyOnly, boolean includeExited) {
        var structureId = CurrentUser.get().structureId();
        var today = LocalDate.now(clock);
        Map<UUID, List<AllergySummary>> allergiesByChild = allergies.findByStructureId(structureId).stream()
                .sorted(Comparator.comparingInt(Allergy::getDisplayOrder))
                .collect(Collectors.groupingBy(Allergy::getChildId,
                        Collectors.mapping(a -> new AllergySummary(a.getAllergen(), a.getSeverity()),
                                Collectors.toList())));
        Map<UUID, HealthInfo> health = healthInfos.findByStructureId(structureId).stream()
                .collect(Collectors.toMap(HealthInfo::getChildId, h -> h));
        var needle = normalize(query);

        return children.findByStructureId(structureId).stream()
                .filter(c -> includeExited || c.isEnrolledOn(today))
                .filter(c -> sectionId == null || sectionId.equals(c.getSectionId()))
                .filter(c -> needle.isEmpty() || normalize(c.getFirstName() + " " + c.getLastName()).contains(needle))
                .filter(c -> !withAllergyOnly || allergiesByChild.containsKey(c.getId()))
                .sorted(Comparator.comparing((Child c) -> normalize(c.getLastName()))
                        .thenComparing(c -> normalize(c.getFirstName())))
                .map(c -> {
                    var h = health.get(c.getId());
                    var summary = new HealthSummary(allergiesByChild.getOrDefault(c.getId(), List.of()),
                            h != null && h.isPaiValidOn(today), h == null ? null : h.getDiet());
                    return new ChildListItem(c.getId(), c.getFileNumber(), c.getFirstName(), c.getLastName(),
                            c.getBirthDate(), c.getSectionId(), c.getEntryDate(), c.getExitDate(),
                            c.getPhotoFileId() != null, c.getFamilyId(), summary);
                })
                .toList();
    }

    @Transactional
    public UUID create(CreateChildRequest request) {
        var structureId = CurrentUser.get().structureId();
        var identity = request.child();
        checkSection(structureId, identity.sectionId());
        var familyId = request.familyId() != null
                ? families.load(request.familyId()).getId()
                : families.create(request.family().family(), request.family().guardian()).getId();

        int year = (identity.entryDate() != null ? identity.entryDate() : LocalDate.now(clock)).getYear();
        var number = "ENF-%d-%03d".formatted(year, counters.next(structureId, "child-file", year));
        var child = new Child(structureId, familyId, number);
        apply(child, identity);
        children.saveAndFlush(child);
        audit.record(AuditAction.CHILD_CREATED, "child", child.getId(), Map.of("fileNumber", number));
        return child.getId();
    }

    /** Fiche complète ; chaque consultation est inscrite au journal des dossiers enfants. */
    @Transactional
    public ChildRecord record(UUID id) {
        var child = load(id);
        accessLog.log(child.getId(), ChildAccessContext.FICHE);
        return assembler.assemble(child);
    }

    @Transactional
    public void updateIdentity(UUID id, ChildIdentityRequest request) {
        var child = load(id);
        checkSection(child.getStructureId(), request.sectionId());
        apply(child, request);
        children.saveAndFlush(child);
        audit.record(AuditAction.CHILD_UPDATED, "child", id, Map.of());
        events.publishEvent(new PortalChange(child.getStructureId(), PortalItemType.CHILD, id));
    }

    @Transactional(readOnly = true)
    public Photo photo(UUID id) {
        var child = load(id);
        if (child.getPhotoFileId() == null) {
            throw ApiException.notFound("Aucune photo enregistrée.");
        }
        var file = files.findById(child.getPhotoFileId()).orElseThrow();
        return new Photo(fileStore.read(file), file.getContentType());
    }

    @Transactional
    public void replacePhoto(UUID id, byte[] content) {
        var child = load(id);
        var type = Uploads.checkPhoto(content);
        var previous = child.getPhotoFileId();
        child.setPhotoFileId(fileStore.store(child.getStructureId(), content, type).getId());
        children.saveAndFlush(child);
        deleteFile(previous);
        audit.record(AuditAction.CHILD_PHOTO_UPDATED, "child", id, Map.of());
        events.publishEvent(new PortalChange(child.getStructureId(), PortalItemType.CHILD, id));
    }

    @Transactional
    public void removePhoto(UUID id) {
        var child = load(id);
        var previous = child.getPhotoFileId();
        child.setPhotoFileId(null);
        children.saveAndFlush(child);
        deleteFile(previous);
        audit.record(AuditAction.CHILD_PHOTO_UPDATED, "child", id, Map.of("removed", true));
        events.publishEvent(new PortalChange(child.getStructureId(), PortalItemType.CHILD, id));
    }

    public Child load(UUID id) {
        return children.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Enfant introuvable."));
    }

    private void deleteFile(UUID fileId) {
        if (fileId != null) {
            files.findById(fileId).ifPresent(fileStore::delete);
        }
    }

    private void checkSection(UUID structureId, UUID sectionId) {
        if (sectionId != null && sections.findByIdAndStructureId(sectionId, structureId).isEmpty()) {
            throw ApiException.rule("unknown_section", "Cette section n’existe pas dans la structure.");
        }
    }

    private static void apply(Child child, ChildIdentityRequest r) {
        child.update(r.firstName().trim(), r.lastName().trim(), r.birthDate(), r.sectionId(), r.entryDate(),
                r.exitDate(), blankToNull(r.homeLanguages()));
    }

    /** Minuscules sans accents : « Kaïna » est trouvée en tapant « kaina ». */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record Photo(byte[] content, String contentType) {
    }
}
