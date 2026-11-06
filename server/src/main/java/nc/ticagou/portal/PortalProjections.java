package nc.ticagou.portal;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.attendance.ChildAttendanceRepository;
import nc.ticagou.billing.BillingDocuments;
import nc.ticagou.billing.CreditNoteLineRepository;
import nc.ticagou.billing.CreditNoteRepository;
import nc.ticagou.billing.InvoiceLineRepository;
import nc.ticagou.billing.InvoiceBalances;
import nc.ticagou.billing.InvoiceRepository;
import nc.ticagou.child.Child;
import nc.ticagou.child.ChildRepository;
import nc.ticagou.child.Consent;
import nc.ticagou.child.ConsentRepository;
import nc.ticagou.document.EncryptedFileStore;
import nc.ticagou.document.StoredFileRepository;
import nc.ticagou.document.Uploads;
import nc.ticagou.family.GuardianRepository;
import nc.ticagou.journal.DailyEntry;
import nc.ticagou.journal.DailyEntryRepository;
import nc.ticagou.portal.SyncDtos.AttendancePayload;
import nc.ticagou.portal.SyncDtos.ChildPayload;
import nc.ticagou.portal.SyncDtos.DocumentPayload;
import nc.ticagou.portal.SyncDtos.EntryPayload;
import nc.ticagou.portal.SyncDtos.GuardianPayload;
import nc.ticagou.portal.SyncDtos.InvoiceLinePayload;
import nc.ticagou.portal.SyncDtos.InvoicePayload;
import nc.ticagou.portal.SyncDtos.MessagePayload;
import nc.ticagou.portal.SyncDtos.SharedHealth;
import nc.ticagou.portal.SyncDtos.StructurePayload;
import nc.ticagou.structure.SectionRepository;
import nc.ticagou.structure.StructureRepository;

/**
 * Seule porte de sortie vers le portail (ADR 0010) : construit, depuis l'état courant, le contenu partagé d'un
 * élément de la file. Ce qui ne peut pas (ou plus) être partagé part comme une suppression : une photo dont un
 * enfant a perdu son autorisation, une transmission décochée, la famille dont l'accès a été retiré. Aucune donnée de
 * santé n'est lue ici, en dehors des informations que la direction a explicitement partagées ({@link HealthShare}).
 */
@Component
public class PortalProjections {

    /** Contenu à envoyer ; {@code payload == null} : suppression côté portail. */
    public record Projection(boolean deleted, Object payload) {

        static Projection delete() {
            return new Projection(true, null);
        }

        static Projection of(Object payload) {
            return new Projection(false, payload);
        }
    }

    private final StructureRepository structures;
    private final GuardianRepository guardians;
    private final GuardianPortalAccessRepository accesses;
    private final ChildRepository children;
    private final SectionRepository sections;
    private final ConsentRepository consents;
    private final HealthShareRepository healthShares;
    private final ChildAttendanceRepository attendances;
    private final DailyEntryRepository entries;
    private final InvoiceRepository invoices;
    private final CreditNoteRepository creditNotes;
    private final InvoiceBalances balances;
    private final InvoiceLineRepository invoiceLines;
    private final CreditNoteLineRepository creditNoteLines;
    private final BillingDocuments billingDocuments;
    private final PortalDocumentRepository documents;
    private final FamilyMessageRepository messages;
    private final EncryptedFileStore fileStore;
    private final StoredFileRepository files;

    public PortalProjections(StructureRepository structures, GuardianRepository guardians,
                             GuardianPortalAccessRepository accesses, ChildRepository children,
                             SectionRepository sections, ConsentRepository consents, HealthShareRepository healthShares,
                             ChildAttendanceRepository attendances, DailyEntryRepository entries,
                             InvoiceRepository invoices, CreditNoteRepository creditNotes, InvoiceBalances balances,
                             InvoiceLineRepository invoiceLines, CreditNoteLineRepository creditNoteLines,
                             BillingDocuments billingDocuments, PortalDocumentRepository documents,
                             FamilyMessageRepository messages, EncryptedFileStore fileStore, StoredFileRepository files) {
        this.structures = structures;
        this.guardians = guardians;
        this.accesses = accesses;
        this.children = children;
        this.sections = sections;
        this.consents = consents;
        this.healthShares = healthShares;
        this.attendances = attendances;
        this.entries = entries;
        this.invoices = invoices;
        this.creditNotes = creditNotes;
        this.balances = balances;
        this.invoiceLines = invoiceLines;
        this.creditNoteLines = creditNoteLines;
        this.billingDocuments = billingDocuments;
        this.documents = documents;
        this.messages = messages;
        this.fileStore = fileStore;
        this.files = files;
    }

    @Transactional(readOnly = true)
    public Projection build(PortalSettings settings, PortalItemType type, UUID id) {
        var structureId = settings.getStructureId();
        return switch (type) {
            case STRUCTURE -> structure(settings);
            case GUARDIAN -> guardian(structureId, id);
            case CHILD -> child(structureId, id);
            case ATTENDANCE -> settings.isShareEntries() ? attendance(structureId, id) : Projection.delete();
            case ENTRY -> settings.isShareEntries() ? entry(settings, id) : Projection.delete();
            case INVOICE -> settings.isShareInvoices() ? invoice(structureId, id) : Projection.delete();
            case DOCUMENT -> settings.isShareDocuments() ? document(structureId, id) : Projection.delete();
            case MESSAGE -> settings.isShareMessages() ? message(structureId, id) : Projection.delete();
            case PURGE_FAMILY, PURGE_STRUCTURE -> Projection.delete();
        };
    }

    private Projection structure(PortalSettings settings) {
        return structures.findById(settings.getStructureId())
                .map(s -> Projection.of(new StructurePayload(s.getName(), s.getPhone(),
                        settings.getPaymentInfo() != null ? settings.getPaymentInfo() : transferInfo(s.getBankName(),
                                s.getIban(), s.getBankHolder()), settings.getMealAbsenceCutoff())))
                .orElseGet(Projection::delete);
    }

    private static String transferInfo(String bank, String iban, String holder) {
        if (iban == null || iban.isBlank()) {
            return null;
        }
        return "Virement%s : IBAN %s%s".formatted(bank == null ? "" : " (" + bank + ")", iban,
                holder == null ? "" : ", au nom de " + holder);
    }

    private Projection guardian(UUID structureId, UUID id) {
        var access = accesses.findById(id).filter(a -> a.getStructureId().equals(structureId));
        var guardian = guardians.findById(id);
        if (access.isEmpty() || guardian.isEmpty()) {
            return Projection.delete();
        }
        var g = guardian.get();
        return Projection.of(new GuardianPayload(g.getFamilyId(), g.getFirstName(), g.getLastName(),
                g.getEmail() == null || g.getEmail().isBlank() ? access.get().getEmail() : g.getEmail().strip().toLowerCase()));
    }

    private Projection child(UUID structureId, UUID id) {
        var found = children.findByIdAndStructureId(id, structureId).filter(c -> hasAccess(c.getFamilyId()));
        if (found.isEmpty()) {
            return Projection.delete();
        }
        var child = found.get();
        byte[] photo = null;
        String photoType = null;
        if (child.getPhotoFileId() != null && photoAllowed(structureId, List.of(child.getId()))) {
            var file = files.findById(child.getPhotoFileId());
            if (file.isPresent()) {
                photo = fileStore.read(file.get());
                photoType = file.get().getContentType();
            }
        }
        var section = child.getSectionId() == null ? null
                : sections.findById(child.getSectionId()).map(s -> s.getName()).orElse(null);
        var shared = healthShares.findByChildIdOrderBySharedAtAsc(id).stream()
                .map(h -> new SharedHealth(h.getLabel(), h.getText())).toList();
        return Projection.of(new ChildPayload(child.getFamilyId(), child.getFirstName(), initial(child.getLastName()),
                section, photo, photoType, shared));
    }

    private Projection attendance(UUID structureId, UUID id) {
        var found = attendances.findById(id).filter(a -> a.getStructureId().equals(structureId));
        if (found.isEmpty()) {
            return Projection.delete();
        }
        var a = found.get();
        var child = children.findById(a.getChildId()).filter(c -> hasAccess(c.getFamilyId()));
        return child.map(c -> Projection.of(new AttendancePayload(c.getFamilyId(), c.getId(), a.getDay(),
                a.getArrivedAt(), a.getDepartedAt()))).orElseGet(Projection::delete);
    }

    private Projection entry(PortalSettings settings, UUID id) {
        var found = entries.findById(id).filter(e -> e.getStructureId().equals(settings.getStructureId()));
        if (found.isEmpty() || !found.get().isShared()) {
            return Projection.delete();
        }
        var entry = found.get();
        var concerned = children.findAllById(entry.getChildIds());
        byte[] photo = null;
        String photoType = null;
        if (entry.getKind() == DailyEntry.Kind.PHOTO) {
            // Une photo ne sort que si chaque enfant photographié est autorisé.
            if (!settings.isSharePhotos() || !photoAllowed(settings.getStructureId(), entry.getChildIds())) {
                return Projection.delete();
            }
            photo = fileStore.read(files.findById(entry.getPhotoFileId()).orElseThrow());
            photoType = Uploads.detectType(photo);
        }
        var visible = concerned.stream().filter(c -> hasAccess(c.getFamilyId()))
                .sorted(Comparator.comparing(Child::getFirstName)).toList();
        if (visible.isEmpty()) {
            return Projection.delete();
        }
        return Projection.of(new EntryPayload(visible.stream().map(Child::getFamilyId).distinct().toList(),
                visible.stream().map(Child::getId).toList(), entry.getDay(), entry.getOccurredAt(),
                entry.getKind().name(), entry.getText(), entry.getDetails(), photo, photoType));
    }

    private Projection invoice(UUID structureId, UUID id) {
        var invoice = invoices.findByIdAndStructureId(id, structureId);
        if (invoice.isPresent()) {
            var i = invoice.get();
            if (!hasAccess(i.getFamilyId())) {
                return Projection.delete();
            }
            var remaining = balances.of(i.getId()).remaining(i.getFamilyTotal());
            return Projection.of(new InvoicePayload(i.getFamilyId(), i.getNumber(), "FACTURE", i.getMonth(),
                    i.getIssuedOn(), i.getDueOn(), i.getFamilyTotal(), remaining,
                    invoiceLines.findByInvoiceIdOrderByPositionAsc(i.getId()).stream()
                            .map(l -> new InvoiceLinePayload(l.getLabel(), l.total())).toList(),
                    billingDocuments.invoice(structureId, i.getId()).content()));
        }
        var note = creditNotes.findByIdAndStructureId(id, structureId);
        if (note.isEmpty()) {
            return Projection.delete();
        }
        var n = note.get();
        var origin = invoices.findById(n.getInvoiceId()).orElseThrow();
        if (!hasAccess(origin.getFamilyId())) {
            return Projection.delete();
        }
        return Projection.of(new InvoicePayload(origin.getFamilyId(), n.getNumber(), "AVOIR", origin.getMonth(),
                n.getIssuedOn(), null, n.getFamilyTotal(), 0, creditNoteLines.findByCreditNoteIdOrderByPositionAsc(n.getId()).stream()
                        .map(l -> new InvoiceLinePayload(l.getLabel(), l.getAmount())).toList(),
                billingDocuments.creditNote(structureId, n.getId()).content()));
    }

    private Projection document(UUID structureId, UUID id) {
        var found = documents.findByIdAndStructureId(id, structureId)
                .filter(d -> d.getStatus() != PortalDocument.Status.RETIRE && hasAccess(d.getFamilyId()));
        if (found.isEmpty()) {
            return Projection.delete();
        }
        var d = found.get();
        var pdf = fileStore.read(files.findById(d.getFileId()).orElseThrow());
        var status = d.getStatus() == PortalDocument.Status.A_SIGNER ? "A_SIGNER" : d.isRefused() ? "REFUSE" : "SIGNE";
        return Projection.of(new DocumentPayload(d.getFamilyId(), d.getChildId(), d.getKind().name(), d.getTitle(), pdf,
                d.getSha256(), status));
    }

    private Projection message(UUID structureId, UUID id) {
        return messages.findById(id)
                .filter(m -> m.getStructureId().equals(structureId) && m.getDirection() == FamilyMessage.Direction.EQUIPE
                        && hasAccess(m.getFamilyId()))
                .map(m -> Projection.of(new MessagePayload(m.getFamilyId(), firstName(m.getAuthorName()), m.getBody(),
                        m.getSentAt())))
                .orElseGet(Projection::delete);
    }

    /** Autorisation PHOTOS accordée pour chacun des enfants. */
    boolean photoAllowed(UUID structureId, java.util.Collection<UUID> childIds) {
        var granted = new HashSet<>(consents.grantedChildIds(structureId, Consent.Type.PHOTOS));
        return !childIds.isEmpty() && granted.containsAll(childIds);
    }

    private boolean hasAccess(UUID familyId) {
        return familyId != null && accesses.existsByFamilyId(familyId);
    }

    private static String initial(String lastName) {
        return lastName == null || lastName.isBlank() ? null : lastName.strip().substring(0, 1).toUpperCase();
    }

    /** L'équipe signe de son prénom : le nom complet du personnel ne sort pas. */
    private static String firstName(String displayName) {
        return Optional.ofNullable(displayName).map(String::strip).filter(s -> !s.isEmpty())
                .map(s -> s.split("\\s+")[0]).filter(Objects::nonNull).orElse("L’équipe");
    }
}
