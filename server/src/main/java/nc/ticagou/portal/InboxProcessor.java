package nc.ticagou.portal;

import java.security.PrivateKey;
import java.time.Clock;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import nc.ticagou.child.ChildRepository;
import nc.ticagou.family.GuardianRepository;
import nc.ticagou.portal.SyncDtos.AbsenceIn;
import nc.ticagou.portal.SyncDtos.BookingIn;
import nc.ticagou.portal.SyncDtos.InboxItem;
import nc.ticagou.portal.SyncDtos.MessageIn;
import nc.ticagou.portal.SyncDtos.SignatureIn;

/**
 * Ce que les parents ont envoyé depuis le portail : absences signalées, messages, signatures. Chaque élément est
 * appliqué une seule fois (référence du portail unique) et ne modifie jamais directement une facture, un contrat ou
 * une autorisation : la direction valide. Un élément incohérent (enfant d'une autre famille, document inconnu) est
 * ignoré et acquitté, pour ne pas bloquer la file.
 */
@Component
public class InboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(InboxProcessor.class);
    private static final int MAX_BODY = 2000;

    private final PortalClient client;
    private final PortalSettingsRepository settingsRepository;
    private final ReportedAbsenceRepository absences;
    private final FamilyMessageRepository messages;
    private final PortalDocumentRepository documents;
    private final ChildRepository children;
    private final GuardianRepository guardians;
    private final GuardianPortalAccessRepository accesses;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final BookingService bookings;

    public InboxProcessor(PortalClient client, PortalSettingsRepository settingsRepository,
                          ReportedAbsenceRepository absences, FamilyMessageRepository messages,
                          PortalDocumentRepository documents, ChildRepository children, GuardianRepository guardians,
                          GuardianPortalAccessRepository accesses, ObjectMapper json,
                          PlatformTransactionManager transactionManager, Clock clock, BookingService bookings) {
        this.client = client;
        this.settingsRepository = settingsRepository;
        this.absences = absences;
        this.messages = messages;
        this.documents = documents;
        this.children = children;
        this.guardians = guardians;
        this.accesses = accesses;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.bookings = bookings;
    }

    /** Lit, applique et acquitte la boîte de réception ; renvoie le nombre d'éléments appliqués. */
    public int pull(PortalSettings settings, PrivateKey key) {
        var response = client.inbox(settings.getPortalUrl(), settings.getPortalStructureId(), key,
                settings.getInboxCursor());
        if (response == null || response.items() == null || response.items().isEmpty()) {
            return 0;
        }
        int applied = 0;
        long cursor = settings.getInboxCursor();
        for (var item : response.items()) {
            if (item.seq() <= cursor) {
                continue;
            }
            if (Boolean.TRUE.equals(transactions.execute(s -> apply(settings, item)))) {
                applied++;
            }
            cursor = item.seq();
            long reached = cursor;
            transactions.executeWithoutResult(s -> settingsRepository.findById(settings.getStructureId())
                    .ifPresent(current -> current.inboxCursor(reached)));
        }
        client.ack(settings.getPortalUrl(), settings.getPortalStructureId(), key, cursor);
        return applied;
    }

    boolean apply(PortalSettings settings, InboxItem item) {
        try {
            return switch (item.type()) {
                case ABSENCE -> absence(settings, item, json.treeToValue(item.payload(), AbsenceIn.class));
                case MESSAGE -> message(settings, item, json.treeToValue(item.payload(), MessageIn.class));
                case SIGNATURE -> signature(settings, item, json.treeToValue(item.payload(), SignatureIn.class));
                case BOOKING -> {
                    var in = json.treeToValue(item.payload(), BookingIn.class);
                    yield bookings.received(settings.getStructureId(), item.id(), item.familyId(), in,
                            guardianName(settings, item, in.guardianId()));
                }
            };
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("Élément du portail illisible ignoré : {} {}", item.type(), item.id());
            return false;
        }
    }

    private boolean absence(PortalSettings settings, InboxItem item, AbsenceIn in) {
        if (!settings.isAcceptAbsences() || absences.existsByPortalRef(item.id())) {
            return false;
        }
        var child = children.findByIdAndStructureId(in.childId(), settings.getStructureId())
                .filter(c -> c.getFamilyId().equals(item.familyId()));
        if (child.isEmpty() || in.fromDay() == null || in.toDay() == null || in.toDay().isBefore(in.fromDay())
                || in.fromDay().plusDays(92).isBefore(in.toDay())) {
            log.warn("Absence du portail ignorée (enfant ou dates incohérents) : {}", item.id());
            return false;
        }
        absences.save(new ReportedAbsence(settings.getStructureId(), item.id(), item.familyId(), in.childId(),
                guardianName(settings, item, in.guardianId()), in.fromDay(), in.toDay(), clip(in.reason(), 300),
                in.reportedAt() == null ? clock.instant() : in.reportedAt()));
        return true;
    }

    private boolean message(PortalSettings settings, InboxItem item, MessageIn in) {
        if (!settings.isShareMessages() || messages.existsByPortalRef(item.id()) || in.body() == null
                || in.body().isBlank() || !accesses.existsByFamilyId(item.familyId())) {
            return false;
        }
        var name = guardianName(settings, item, in.guardianId());
        messages.save(FamilyMessage.fromFamily(settings.getStructureId(), item.familyId(), item.id(),
                name == null ? "Famille" : name, clip(in.body().strip(), MAX_BODY),
                in.sentAt() == null ? clock.instant() : in.sentAt()));
        return true;
    }

    private boolean signature(PortalSettings settings, InboxItem item, SignatureIn in) {
        var found = documents.findByIdAndStructureId(in.documentId(), settings.getStructureId())
                .filter(d -> d.getFamilyId().equals(item.familyId()) && d.getStatus() == PortalDocument.Status.A_SIGNER);
        if (found.isEmpty() || in.signerName() == null || in.signerName().isBlank()) {
            return false;
        }
        var document = found.get();
        if (!document.getSha256().equals(in.sha256())) {
            // Le parent a signé un autre contenu que celui proposé : on ne l'enregistre pas.
            log.warn("Signature ignorée : empreinte différente du document proposé {}", document.getId());
            return false;
        }
        if (in.refused() && document.getKind() == PortalDocument.Kind.CONTRAT) {
            log.warn("Refus de contrat ignoré : un contrat se signe sur place en cas de désaccord ({})", document.getId());
            return false;
        }
        document.signed(item.id(), in.guardianId(), clip(in.signerName().strip(), 120), in.refused(),
                in.signedAt() == null ? clock.instant() : in.signedAt().truncatedTo(ChronoUnit.SECONDS),
                clip(in.ip(), 45), in.sha256());
        return true;
    }

    private String guardianName(PortalSettings settings, InboxItem item, java.util.UUID guardianId) {
        if (guardianId == null) {
            return null;
        }
        return guardians.findById(guardianId)
                .filter(g -> g.getFamilyId().equals(item.familyId()) && g.getStructureId().equals(settings.getStructureId()))
                .map(g -> g.getFirstName() + " " + g.getLastName()).orElse(null);
    }

    private static String clip(String value, int max) {
        return value == null ? null : value.length() > max ? value.substring(0, max) : value;
    }
}
