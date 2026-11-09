package nc.ticagou.journal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.attendance.AttendanceService.Author;
import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.audit.ChildAccessContext;
import nc.ticagou.audit.ChildAccessLogger;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.child.Child;
import nc.ticagou.child.ChildRepository;
import nc.ticagou.child.Consent;
import nc.ticagou.child.ConsentRepository;
import nc.ticagou.common.ApiException;
import nc.ticagou.document.EncryptedFileStore;
import nc.ticagou.document.StoredFileRepository;
import nc.ticagou.document.Uploads;
import nc.ticagou.journal.DailyEntry.Kind;
import nc.ticagou.journal.JournalDtos.ChildRef;
import nc.ticagou.journal.JournalDtos.EntryCorrection;
import nc.ticagou.journal.JournalDtos.EntryDto;
import nc.ticagou.journal.JournalDtos.EntryInput;
import nc.ticagou.journal.JournalDtos.KioskJournal;
import nc.ticagou.journal.JournalDtos.ReportedAbsenceRef;
import nc.ticagou.license.LicenseModule;
import nc.ticagou.license.LicenseService;
import nc.ticagou.portal.PortalChange;
import nc.ticagou.portal.PortalItemType;
import nc.ticagou.portal.ReportedAbsenceRepository;

/**
 * Transmissions du jour : saisies sur la tablette (file d'opérations, ADR 0005), relues et corrigées au bureau.
 * Chaque modification prévient le portail parents ({@link PortalChange}), qui décide de ce qui sort.
 */
@Service
public class JournalService {

    private final LicenseService licenses;

    private static final ZoneId NOUMEA = ZoneId.of("Pacific/Noumea");

    private final DailyEntryRepository entries;
    private final ChildRepository children;
    private final EncryptedFileStore fileStore;
    private final StoredFileRepository files;
    private final AuditService audit;
    private final ChildAccessLogger accessLogger;
    private final ApplicationEventPublisher events;
    private final ConsentRepository consents;
    private final ReportedAbsenceRepository reportedAbsences;
    private final Clock clock;

    public JournalService(DailyEntryRepository entries, ChildRepository children, EncryptedFileStore fileStore,
                          StoredFileRepository files, AuditService audit, ChildAccessLogger accessLogger,
                          ApplicationEventPublisher events, ConsentRepository consents,
                          ReportedAbsenceRepository reportedAbsences, Clock clock, LicenseService licenses) {
        this.licenses = licenses;
        this.entries = entries;
        this.children = children;
        this.fileStore = fileStore;
        this.files = files;
        this.audit = audit;
        this.accessLogger = accessLogger;
        this.events = events;
        this.consents = consents;
        this.reportedAbsences = reportedAbsences;
        this.clock = clock;
    }

    /**
     * Enregistre une transmission de la tablette ; {@code id} est celui de l'opération de la file. Une photo exige
     * son contenu et n'est partagée que si l'éducatrice l'a demandé.
     */
    @Transactional
    public DailyEntry record(UUID structureId, UUID id, EntryInput input, Instant at, byte[] photo, Author author) {
        if (!licenses.allows(LicenseModule.PORTAIL_PARENTS)) {
            throw ApiException.forbidden("module_absent", "Les transmissions font partie du module Portail parents.");
        }
        var day = at.atZone(NOUMEA).toLocalDate();
        JournalRules.checkChildren(input.kind(), input.childIds().size());
        var occurred = JournalRules.occurredAt(input.time(), at, NOUMEA);
        for (var child : children(structureId, input.childIds())) {
            if (!child.isEnrolledOn(day)) {
                throw ApiException.rule("child_not_enrolled",
                        "%s n’est pas inscrit(e) ce jour-là.".formatted(child.getFirstName()));
            }
        }
        var text = JournalRules.text(input.kind(), input.text());
        var details = JournalRules.details(input.kind(), input.details());
        UUID photoId = null;
        if (input.kind().isMedia()) {
            if (photo == null || photo.length == 0) {
                throw ApiException.rule("photo_required", input.kind() == Kind.VIDEO ? "La vidéo manque." : "La photo manque.");
            }
            var type = input.kind() == Kind.VIDEO ? Uploads.checkVideo(photo) : Uploads.checkPhoto(photo);
            photoId = fileStore.store(structureId, photo, type).getId();
        } else if (photo != null) {
            throw ApiException.rule("invalid_entry", "Seule une transmission « Photo » ou « Vidéo » porte un fichier.");
        }
        var now = clock.instant();
        var entry = new DailyEntry(id, structureId, day, occurred, input.kind(), input.childIds(), photoId, author.userId(),
                author.name(), now);
        entry.update(day, occurred, text, details, input.shared(), now);
        entries.save(entry);
        changed(entry);
        return entry;
    }

    @Transactional
    public List<EntryDto> list(LocalDate day, UUID childId) {
        var user = CurrentUser.get();
        var found = childId == null ? entries.findByStructureIdAndDayOrderByOccurredAtAsc(user.structureId(), day)
                : entries.findForChild(user.structureId(), day, childId);
        if (childId != null) {
            accessLogger.log(childId, ChildAccessContext.JOURNAL);
        }
        return toDtos(user.structureId(), found);
    }

    /**
     * Onglet « Journée » de la tablette : transmissions du jour, enfants autorisés en photo (seuls eux peuvent être
     * sur une photo partagée) et absences signalées par les parents.
     */
    @Transactional(readOnly = true)
    public KioskJournal kioskDay(UUID structureId) {
        var today = LocalDate.now(clock.withZone(NOUMEA));
        var found = toDtos(structureId, entries.findByStructureIdAndDayOrderByOccurredAtAsc(structureId, today));
        var absences = reportedAbsences.findCovering(structureId, today).stream()
                .map(r -> new ReportedAbsenceRef(r.getChildId(), r.getReason(), r.getGuardianName())).toList();
        return new KioskJournal(found, consents.grantedChildIds(structureId, Consent.Type.PHOTOS), absences);
    }

    @Transactional
    public EntryDto correct(UUID id, EntryCorrection request) {
        var user = CurrentUser.get();
        var entry = load(user.structureId(), id);
        var day = request.occurredAt().atZone(NOUMEA).toLocalDate();
        entry.update(day, request.occurredAt(), JournalRules.text(entry.getKind(), request.text()),
                JournalRules.details(entry.getKind(), request.details()), request.shared(), clock.instant());
        audit.record(AuditAction.JOURNAL_ENTRY_CORRECTED, "daily_entry", id, Map.of("kind", entry.getKind().name()));
        changed(entry);
        return toDtos(user.structureId(), List.of(entry)).getFirst();
    }

    @Transactional
    public void delete(UUID id) {
        var user = CurrentUser.get();
        var entry = load(user.structureId(), id);
        remove(entry);
        audit.record(AuditAction.JOURNAL_ENTRY_DELETED, "daily_entry", id, Map.of("kind", entry.getKind().name()));
        events.publishEvent(new PortalChange(user.structureId(), PortalItemType.ENTRY, id));
    }

    @Transactional(readOnly = true)
    public byte[] photo(UUID structureId, UUID id) {
        var entry = load(structureId, id);
        if (entry.getPhotoFileId() == null) {
            throw ApiException.notFound("Pas de photo.");
        }
        return fileStore.read(files.findById(entry.getPhotoFileId()).orElseThrow());
    }

    /**
     * Retire des transmissions les enfants d'une famille supprimée ; une transmission qui ne concerne plus personne
     * est supprimée avec sa photo.
     */
    @Transactional
    public void forget(Collection<UUID> childIds) {
        if (childIds.isEmpty()) {
            return;
        }
        for (var entry : entries.findByChildIdIn(childIds)) {
            entry.getChildIds().removeAll(childIds);
            if (entry.getChildIds().isEmpty()) {
                remove(entry);
            }
        }
        entries.flush();
    }

    DailyEntry load(UUID structureId, UUID id) {
        return entries.findByIdAndStructureId(id, structureId)
                .orElseThrow(() -> ApiException.notFound("Transmission introuvable."));
    }

    private void remove(DailyEntry entry) {
        var photoId = entry.getPhotoFileId();
        entries.delete(entry);
        entries.flush();
        if (photoId != null) {
            files.findById(photoId).ifPresent(fileStore::delete);
        }
    }

    private List<Child> children(UUID structureId, Collection<UUID> ids) {
        var found = children.findAllById(ids);
        if (found.size() != ids.size() || found.stream().anyMatch(c -> !c.getStructureId().equals(structureId))) {
            throw ApiException.rule("child_unknown", "Enfant inconnu.");
        }
        return found;
    }

    private void changed(DailyEntry entry) {
        events.publishEvent(new PortalChange(entry.getStructureId(), PortalItemType.ENTRY, entry.getId()));
    }

    private List<EntryDto> toDtos(UUID structureId, List<DailyEntry> found) {
        var ids = found.stream().flatMap(e -> e.getChildIds().stream()).distinct().toList();
        var byId = new HashMap<UUID, Child>();
        children.findAllById(ids).stream().filter(c -> c.getStructureId().equals(structureId))
                .forEach(c -> byId.put(c.getId(), c));
        return found.stream().map(e -> new EntryDto(e.getId(), e.getDay(), e.getOccurredAt(), e.getKind(), e.getText(),
                e.getDetails(), e.getPhotoFileId() != null, e.isShared(),
                e.getChildIds().stream().map(byId::get).filter(Objects::nonNull)
                        .map(c -> new ChildRef(c.getId(), c.getFirstName(), c.getLastName())).toList(),
                e.getAuthorName(), e.getUpdatedAt())).toList();
    }
}
