package nc.ticagou.portal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import nc.ticagou.document.EncryptedFileStore;
import nc.ticagou.document.StoredFileRepository;
import nc.ticagou.journal.DailyEntryRepository;
import nc.ticagou.journal.JournalService;

/**
 * Transmissions, messages, absences signalées, documents signés et partages de santé d'une famille, pour l'export et
 * la purge de ses données ({@code family/FamilyDataService}). La purge envoie aussi l'ordre d'effacement au portail.
 */
@Component
public class PortalFamilyData {

    public record EntryExport(UUID id, LocalDate day, Instant at, String kind, String text, Map<String, String> details,
                              boolean photo, boolean shared, String author) {
    }

    public record MessageExport(Instant sentAt, String direction, String author, String body) {
    }

    public record AbsenceExport(LocalDate fromDay, LocalDate toDay, String reason, Instant reportedAt, String status) {
    }

    public record DocumentExport(String title, String status, String signerName, Instant signedAt, String signerIp,
                                 String sha256) {
    }

    public record HealthShareExport(UUID childId, String label, String text, String sharedBy, Instant sharedAt) {
    }

    public record Export(List<EntryExport> transmissions, List<MessageExport> messages, List<AbsenceExport> absences,
                         List<DocumentExport> documents, List<HealthShareExport> healthShares, boolean portalAccess) {
    }

    private final DailyEntryRepository entries;
    private final JournalService journal;
    private final FamilyMessageRepository messages;
    private final ReportedAbsenceRepository absences;
    private final PortalDocumentRepository documents;
    private final HealthShareRepository healthShares;
    private final GuardianPortalAccessRepository accesses;
    private final StoredFileRepository files;
    private final EncryptedFileStore fileStore;
    private final ApplicationEventPublisher events;

    public PortalFamilyData(DailyEntryRepository entries, JournalService journal, FamilyMessageRepository messages,
                            ReportedAbsenceRepository absences, PortalDocumentRepository documents,
                            HealthShareRepository healthShares, GuardianPortalAccessRepository accesses,
                            StoredFileRepository files, EncryptedFileStore fileStore, ApplicationEventPublisher events) {
        this.entries = entries;
        this.journal = journal;
        this.messages = messages;
        this.absences = absences;
        this.documents = documents;
        this.healthShares = healthShares;
        this.accesses = accesses;
        this.files = files;
        this.fileStore = fileStore;
        this.events = events;
    }

    public Export export(UUID structureId, UUID familyId, Collection<UUID> childIds) {
        var transmissions = childIds.isEmpty() ? List.<EntryExport>of() : entries.findByChildIdIn(childIds).stream()
                .sorted(java.util.Comparator.comparing(e -> e.getOccurredAt()))
                .map(e -> new EntryExport(e.getId(), e.getDay(), e.getOccurredAt(), e.getKind().name(), e.getText(),
                        e.getDetails(), e.getPhotoFileId() != null, e.isShared(), e.getAuthorName())).toList();
        var thread = messages.findByStructureIdAndFamilyIdOrderBySentAtAsc(structureId, familyId).stream()
                .map(m -> new MessageExport(m.getSentAt(), m.getDirection().name(), m.getAuthorName(), m.getBody()))
                .toList();
        var reported = absences.findByStructureIdAndStatusOrderByFromDayAsc(structureId, ReportedAbsence.Status.SIGNALEE)
                .stream().filter(a -> a.getFamilyId().equals(familyId)).toList();
        var handled = new ArrayList<>(reported);
        for (var status : List.of(ReportedAbsence.Status.CONFIRMEE, ReportedAbsence.Status.ECARTEE)) {
            absences.findByStructureIdAndStatusOrderByFromDayAsc(structureId, status).stream()
                    .filter(a -> a.getFamilyId().equals(familyId)).forEach(handled::add);
        }
        var docs = documents.findByFamilyIdOrderByProposedAtDesc(familyId).stream()
                .map(d -> new DocumentExport(d.getTitle(), d.getStatus().name(), d.getSignerName(), d.getSignedAt(),
                        d.getSignerIp(), d.getSignedSha256())).toList();
        var shares = childIds.stream().flatMap(id -> healthShares.findByChildIdOrderBySharedAtAsc(id).stream())
                .map(h -> new HealthShareExport(h.getChildId(), h.getLabel(), h.getText(), h.getSharedBy(), h.getSharedAt()))
                .toList();
        return new Export(transmissions, thread,
                handled.stream().map(a -> new AbsenceExport(a.getFromDay(), a.getToDay(), a.getReason(),
                        a.getReportedAt(), a.getStatus().name())).toList(),
                docs, shares, accesses.existsByFamilyId(familyId));
    }

    /** À appeler dans la transaction de la purge, avant la suppression des enfants et des responsables. */
    public void purge(UUID structureId, UUID familyId, Collection<UUID> childIds) {
        var documentFiles = documents.findByFamilyIdOrderByProposedAtDesc(familyId).stream()
                .map(PortalDocument::getFileId).toList();
        documents.deleteByFamilyId(familyId);
        fileStore.delete(files.findAllById(documentFiles));
        messages.deleteByFamilyId(familyId);
        absences.deleteByFamilyId(familyId);
        if (!childIds.isEmpty()) {
            healthShares.deleteByChildIdIn(childIds);
            journal.forget(childIds);
        }
        accesses.deleteAll(accesses.findByFamilyId(familyId));
        accesses.flush();
        events.publishEvent(new PortalChange(structureId, PortalItemType.PURGE_FAMILY, familyId));
    }
}
