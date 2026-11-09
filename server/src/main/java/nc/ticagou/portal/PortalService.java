package nc.ticagou.portal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.billing.AbsenceDtos.AbsenceRequest;
import nc.ticagou.billing.AbsenceService;
import nc.ticagou.billing.InvoiceRepository;
import nc.ticagou.child.Child;
import nc.ticagou.child.ChildRepository;
import nc.ticagou.child.Consent;
import nc.ticagou.child.ConsentRepository;
import nc.ticagou.common.ApiException;
import nc.ticagou.contract.ContractDtos.VersionDto;
import nc.ticagou.contract.ContractPdfService;
import nc.ticagou.contract.ContractService;
import nc.ticagou.document.EncryptedFileStore;
import nc.ticagou.document.StoredFileRepository;
import nc.ticagou.family.Family;
import nc.ticagou.family.FamilyRepository;
import nc.ticagou.family.GuardianRepository;
import nc.ticagou.license.LicenseModule;
import nc.ticagou.license.LicenseService;
import nc.ticagou.portal.PortalDtos.ActivateRequest;
import nc.ticagou.portal.PortalDtos.FamilyAccess;
import nc.ticagou.portal.PortalDtos.GuardianAccess;
import nc.ticagou.portal.PortalDtos.HealthShareDto;
import nc.ticagou.portal.PortalDtos.HealthShareRequest;
import nc.ticagou.portal.PortalDtos.MessageDto;
import nc.ticagou.portal.PortalDtos.PortalDocumentDto;
import nc.ticagou.portal.PortalDtos.PortalStatus;
import nc.ticagou.portal.PortalDtos.ReportedAbsenceDto;
import nc.ticagou.portal.PortalDtos.Shares;
import nc.ticagou.portal.PortalDtos.Thread;
import nc.ticagou.portal.PortalDtos.ThreadSummary;
import nc.ticagou.structure.StructureRepository;

/**
 * Portail parents côté direction (ADR 0010) : activation et désactivation, ce qui est partagé, accès des familles,
 * informations de santé partagées, documents à signer, messages et absences signalées.
 */
@Service
public class PortalService {

    private static final ZoneId NOUMEA = ZoneId.of("Pacific/Noumea");
    /** Factures déjà émises envoyées à l'invitation d'une famille. */
    private static final int INVOICE_HISTORY_MONTHS = 12;

    private final PortalSettingsRepository settingsRepository;
    private final PortalOutboxRepository outbox;
    private final PortalPublisher publisher;
    private final PortalClient client;
    private final PortalKeys keys;
    private final PortalSyncService sync;
    private final GuardianPortalAccessRepository accesses;
    private final HealthShareRepository healthShares;
    private final PortalDocumentRepository documents;
    private final FamilyMessageRepository messages;
    private final ReportedAbsenceRepository reportedAbsences;
    private final GuardianRepository guardians;
    private final FamilyRepository families;
    private final ChildRepository children;
    private final ConsentRepository consents;
    private final InvoiceRepository invoices;
    private final StructureRepository structures;
    private final ContractService contracts;
    private final ContractPdfService contractPdfs;
    private final ConsentPdf consentPdf;
    private final AbsenceService absences;
    private final EncryptedFileStore fileStore;
    private final StoredFileRepository files;
    private final LicenseService licenses;
    private final AuditService audit;
    private final Clock clock;

    public PortalService(PortalSettingsRepository settingsRepository, PortalOutboxRepository outbox,
                         PortalPublisher publisher, PortalClient client, PortalKeys keys, PortalSyncService sync,
                         GuardianPortalAccessRepository accesses, HealthShareRepository healthShares,
                         PortalDocumentRepository documents, FamilyMessageRepository messages,
                         ReportedAbsenceRepository reportedAbsences, GuardianRepository guardians,
                         FamilyRepository families, ChildRepository children, ConsentRepository consents,
                         InvoiceRepository invoices, StructureRepository structures, ContractService contracts,
                         ContractPdfService contractPdfs, ConsentPdf consentPdf, AbsenceService absences,
                         EncryptedFileStore fileStore, StoredFileRepository files, LicenseService licenses,
                         AuditService audit, Clock clock) {
        this.settingsRepository = settingsRepository;
        this.outbox = outbox;
        this.publisher = publisher;
        this.client = client;
        this.keys = keys;
        this.sync = sync;
        this.accesses = accesses;
        this.healthShares = healthShares;
        this.documents = documents;
        this.messages = messages;
        this.reportedAbsences = reportedAbsences;
        this.guardians = guardians;
        this.families = families;
        this.children = children;
        this.consents = consents;
        this.invoices = invoices;
        this.structures = structures;
        this.contracts = contracts;
        this.contractPdfs = contractPdfs;
        this.consentPdf = consentPdf;
        this.absences = absences;
        this.fileStore = fileStore;
        this.files = files;
        this.licenses = licenses;
        this.audit = audit;
        this.clock = clock;
    }

    // --- Réglages -----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PortalStatus status() {
        var structureId = CurrentUser.get().structureId();
        var settings = settingsRepository.findById(structureId).orElseGet(() -> new PortalSettings(structureId, clock.instant()));
        return status(settings);
    }

    PortalStatus status(PortalSettings s) {
        return new PortalStatus(licenses.allows(LicenseModule.PORTAIL_PARENTS), s.getState(), s.getPortalUrl(),
                s.getActivatedAt(), new Shares(s.isShareEntries(), s.isSharePhotos(), s.isShareInvoices(),
                s.isShareDocuments(), s.isShareMessages(), s.isAcceptAbsences(), s.getPaymentInfo(), s.getMealAbsenceCutoff()),
                s.getLastSuccessAt(), s.getLastAttemptAt(), s.getLastError(),
                outbox.countByStructureIdAndSentAtIsNull(s.getStructureId()),
                accesses.findByStructureId(s.getStructureId()).size());
    }

    /**
     * Active le portail : nouvelle paire de clés, enregistrement de la clé publique auprès du portail avec le code à
     * usage unique de l'hébergeur, puis envoi de la structure et des familles déjà invitées.
     */
    @Transactional
    public PortalStatus activate(ActivateRequest request) {
        var user = CurrentUser.get();
        if (!licenses.allows(LicenseModule.PORTAIL_PARENTS)) {
            throw ApiException.forbidden("module_absent", "Le module Portail parents n’est pas compris dans votre licence.");
        }
        var url = request.portalUrl().strip();
        if (!url.startsWith("https://") && !isLocal(url)) {
            throw ApiException.rule("portal_https", "Le portail doit être joint en HTTPS.");
        }
        var settings = settingsRepository.findById(user.structureId())
                .orElseGet(() -> new PortalSettings(user.structureId(), clock.instant()));
        if (settings.getState() != PortalSettings.State.DESACTIVE) {
            throw ApiException.conflict("portal_active", settings.getState() == PortalSettings.State.ACTIF
                    ? "Le portail est déjà activé." : "L’effacement du portail précédent est en cours : réessayez dans un instant.");
        }
        var structure = structures.findById(user.structureId()).orElseThrow();
        var publicKey = keys.rotate(user.structureId());
        SyncDtos.ActivateResponse response;
        try {
            response = client.activate(url, new SyncDtos.ActivateRequest(request.activationCode().strip(), publicKey,
                    structure.getName()));
        } catch (PortalClient.PortalException e) {
            throw ApiException.rule("portal_activation", "Activation refusée. " + e.getMessage());
        }
        if (response == null || response.structureId() == null) {
            throw ApiException.rule("portal_activation", "Le portail n’a pas confirmé l’activation.");
        }
        settings.activate(url, response.structureId(), clock.instant());
        settingsRepository.save(settings);
        outbox.deleteByStructureId(user.structureId());
        publisher.enqueue(new PortalChange(user.structureId(), PortalItemType.STRUCTURE, user.structureId()));
        for (var access : accesses.findByStructureId(user.structureId())) {
            enqueueFamily(user.structureId(), access.getFamilyId(), access.getGuardianId());
        }
        audit.record(AuditAction.PORTAL_ACTIVATED, "portal_settings", user.structureId(), Map.of("url", url));
        return status(settings);
    }

    private static boolean isLocal(String url) {
        return url.startsWith("http://localhost") || url.startsWith("http://127.0.0.1");
    }

    @Transactional
    public PortalStatus updateShares(Shares shares) {
        var user = CurrentUser.get();
        var settings = settingsRepository.findById(user.structureId())
                .orElseGet(() -> settingsRepository.save(new PortalSettings(user.structureId(), clock.instant())));
        settings.share(shares.entries(), shares.photos(), shares.invoices(), shares.documents(), shares.messages(),
                shares.absences(), blankToNull(shares.paymentInfo()), blankToNull(shares.mealAbsenceCutoff()), clock.instant());
        publisher.on(new PortalChange(user.structureId(), PortalItemType.STRUCTURE, user.structureId()));
        var details = new LinkedHashMap<String, Object>();
        details.put("transmissions", shares.entries());
        details.put("photos", shares.photos());
        details.put("factures", shares.invoices());
        details.put("documents", shares.documents());
        details.put("messages", shares.messages());
        details.put("absences", shares.absences());
        audit.record(AuditAction.PORTAL_SETTINGS_UPDATED, "portal_settings", user.structureId(), details);
        return status(settings);
    }

    /**
     * Désactive le portail : plus rien ne sort, et l'ordre d'effacement de tout ce que le portail détient part tout
     * de suite (puis à chaque synchronisation jusqu'à son acquittement).
     */
    @Transactional
    public void deactivate(String confirmation) {
        var user = CurrentUser.get();
        if (!"DÉSACTIVER".equalsIgnoreCase(confirmation.strip()) && !"DESACTIVER".equalsIgnoreCase(confirmation.strip())) {
            throw ApiException.rule("confirmation_mismatch", "Pour confirmer, saisissez DÉSACTIVER.");
        }
        var settings = settingsRepository.findById(user.structureId())
                .filter(s -> s.getState() == PortalSettings.State.ACTIF)
                .orElseThrow(() -> ApiException.rule("portal_inactive", "Le portail n’est pas activé."));
        outbox.deleteByStructureId(user.structureId());
        settings.startPurge(clock.instant());
        settingsRepository.save(settings);
        audit.record(AuditAction.PORTAL_DEACTIVATED, "portal_settings", user.structureId(), Map.of());
    }

    public PortalDtos.SyncNow syncNow() {
        var structureId = CurrentUser.get().structureId();
        var result = sync.sync(structureId);
        var settings = settingsRepository.findById(structureId).orElseGet(() -> new PortalSettings(structureId, clock.instant()));
        return new PortalDtos.SyncNow(result.sent(), result.received(), result.error(), status(settings));
    }

    // --- Accès des familles -------------------------------------------------------------------

    @Transactional(readOnly = true)
    public FamilyAccess familyAccess(UUID familyId) {
        var family = family(familyId);
        var invited = accesses.findByFamilyId(familyId).stream()
                .collect(Collectors.toMap(GuardianPortalAccess::getGuardianId, a -> a));
        var list = guardians.findByFamilyIdOrderByDisplayOrderAscLastNameAsc(family.getId()).stream()
                .map(g -> {
                    var a = invited.get(g.getId());
                    return new GuardianAccess(g.getId(), g.getFirstName(), g.getLastName(), g.getEmail(), a != null,
                            a == null ? null : a.getInvitedAt(), a == null ? null : a.getInvitedBy());
                }).toList();
        return new FamilyAccess(familyId, active(family.getStructureId()), list);
    }

    /** Invite un responsable : sa famille, ses enfants et ses factures récentes partent au portail. */
    @Transactional
    public FamilyAccess invite(UUID guardianId) {
        var user = CurrentUser.get();
        requireActive(user.structureId());
        var guardian = guardians.findByIdAndStructureId(guardianId, user.structureId())
                .orElseThrow(() -> ApiException.notFound("Responsable introuvable."));
        if (guardian.getEmail() == null || guardian.getEmail().isBlank()) {
            throw ApiException.rule("guardian_email", "Ajoutez d’abord l’adresse e-mail de %s : elle sert à se connecter au portail."
                    .formatted(guardian.getFirstName()));
        }
        var access = accesses.findById(guardianId).orElse(null);
        if (access == null) {
            accesses.save(new GuardianPortalAccess(guardianId, user.structureId(), guardian.getFamilyId(),
                    guardian.getEmail().strip().toLowerCase(), clock.instant(), user.displayName()));
        } else {
            access.email(guardian.getEmail().strip().toLowerCase());
        }
        accesses.flush();
        enqueueFamily(user.structureId(), guardian.getFamilyId(), guardianId);
        audit.record(AuditAction.PORTAL_GUARDIAN_INVITED, "guardian", guardianId, Map.of("familyId", guardian.getFamilyId().toString()));
        return familyAccess(guardian.getFamilyId());
    }

    /** Retire l'accès ; sans plus aucun responsable invité, tout ce qui concerne la famille est effacé du portail. */
    @Transactional
    public FamilyAccess revoke(UUID guardianId) {
        var user = CurrentUser.get();
        var access = accesses.findById(guardianId).filter(a -> a.getStructureId().equals(user.structureId()))
                .orElseThrow(() -> ApiException.notFound("Ce responsable n’a pas accès au portail."));
        var familyId = access.getFamilyId();
        accesses.delete(access);
        accesses.flush();
        publisher.on(new PortalChange(user.structureId(), PortalItemType.GUARDIAN, guardianId));
        if (!accesses.existsByFamilyId(familyId)) {
            publisher.on(new PortalChange(user.structureId(), PortalItemType.PURGE_FAMILY, familyId));
        }
        audit.record(AuditAction.PORTAL_GUARDIAN_REVOKED, "guardian", guardianId, Map.of("familyId", familyId.toString()));
        return familyAccess(familyId);
    }

    private void enqueueFamily(UUID structureId, UUID familyId, UUID guardianId) {
        publisher.enqueue(new PortalChange(structureId, PortalItemType.GUARDIAN, guardianId));
        for (var child : children.findByFamilyIdOrderByBirthDateAsc(familyId)) {
            publisher.enqueue(new PortalChange(structureId, PortalItemType.CHILD, child.getId()));
            publisher.enqueueRecent(structureId, child.getId());
        }
        var since = LocalDate.now(clock.withZone(NOUMEA)).minusMonths(INVOICE_HISTORY_MONTHS);
        invoices.findByFamilyIdOrderByMonthDescNumberDesc(familyId).stream()
                .filter(i -> !i.getIssuedOn().isBefore(since))
                .forEach(i -> publisher.enqueue(new PortalChange(structureId, PortalItemType.INVOICE, i.getId())));
        documents.findByFamilyIdOrderByProposedAtDesc(familyId).stream()
                .filter(d -> d.getStatus() != PortalDocument.Status.RETIRE)
                .forEach(d -> publisher.enqueue(new PortalChange(structureId, PortalItemType.DOCUMENT, d.getId())));
        publisher.enqueueVisibleNews(structureId);
    }

    // --- Santé partagée -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<HealthShareDto> healthShares(UUID childId) {
        child(childId);
        return healthShares.findByChildIdOrderBySharedAtAsc(childId).stream().map(PortalService::toDto).toList();
    }

    @Transactional
    public HealthShareDto shareHealth(UUID childId, HealthShareRequest request) {
        var user = CurrentUser.get();
        var child = child(childId);
        var share = healthShares.save(new HealthShare(UUID.randomUUID(), user.structureId(), child.getId(),
                request.label().strip(), request.text().strip(), user.displayName(), clock.instant()));
        publisher.on(new PortalChange(user.structureId(), PortalItemType.CHILD, childId));
        audit.record(AuditAction.HEALTH_SHARED, "child", childId, Map.of("label", share.getLabel()));
        return toDto(share);
    }

    @Transactional
    public void unshareHealth(UUID shareId) {
        var user = CurrentUser.get();
        var share = healthShares.findById(shareId).filter(s -> s.getStructureId().equals(user.structureId()))
                .orElseThrow(() -> ApiException.notFound("Partage introuvable."));
        healthShares.delete(share);
        publisher.on(new PortalChange(user.structureId(), PortalItemType.CHILD, share.getChildId()));
        audit.record(AuditAction.HEALTH_UNSHARED, "child", share.getChildId(), Map.of("label", share.getLabel()));
    }

    private static HealthShareDto toDto(HealthShare s) {
        return new HealthShareDto(s.getId(), s.getLabel(), s.getText(), s.getSharedBy(), s.getSharedAt());
    }

    // --- Documents à signer -------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<PortalDocumentDto> documents(UUID familyId) {
        var structureId = CurrentUser.get().structureId();
        var found = familyId == null
                ? documents.findByStructureIdAndStatusOrderByProposedAtDesc(structureId, PortalDocument.Status.SIGNE)
                : documents.findByFamilyIdOrderByProposedAtDesc(family(familyId).getId());
        return toDtos(found);
    }

    @Transactional
    public PortalDocumentDto proposeContract(UUID contractId, int number) {
        var user = CurrentUser.get();
        requireActive(user.structureId());
        var contract = contracts.detail(contractId);
        VersionDto version = contract.versions().stream().filter(v -> v.number() == number).findFirst()
                .orElseThrow(() -> ApiException.notFound("Version du contrat introuvable."));
        if (version.signedOn() != null) {
            throw ApiException.rule("version_signed", "Cette version est déjà signée.");
        }
        if (documents.existsByContractIdAndVersionNumberAndStatusIn(contractId, number,
                List.of(PortalDocument.Status.A_SIGNER, PortalDocument.Status.SIGNE))) {
            throw ApiException.conflict("already_proposed", "Ce document est déjà proposé à la signature.");
        }
        var child = child(contract.childId());
        requireAccess(child.getFamilyId());
        var pdf = contractPdfs.render(contractId, number, true).content();
        var file = fileStore.store(user.structureId(), pdf, "application/pdf");
        var title = number == 0 ? "Contrat d’accueil n° %s".formatted(contract.number())
                : "Avenant %d au contrat n° %s".formatted(number, contract.number());
        var document = documents.save(PortalDocument.contract(user.structureId(), child.getFamilyId(), child.getId(),
                contractId, number, title, file.getId(), file.getSha256(), user.displayName(), clock.instant()));
        return proposed(document);
    }

    @Transactional
    public PortalDocumentDto proposeConsent(UUID childId, Consent.Type type) {
        var user = CurrentUser.get();
        requireActive(user.structureId());
        var child = child(childId);
        requireAccess(child.getFamilyId());
        boolean pending = documents.findByFamilyIdOrderByProposedAtDesc(child.getFamilyId()).stream()
                .anyMatch(d -> d.getChildId().equals(childId) && d.getConsentType() == type
                        && (d.getStatus() == PortalDocument.Status.A_SIGNER || d.getStatus() == PortalDocument.Status.SIGNE));
        if (pending) {
            throw ApiException.conflict("already_proposed", "Cette autorisation est déjà proposée à la signature.");
        }
        var structure = structures.findById(user.structureId()).orElseThrow();
        var pdf = consentPdf.render(structure, child, type, user.displayName());
        var file = fileStore.store(user.structureId(), pdf, "application/pdf");
        var document = documents.save(PortalDocument.consent(user.structureId(), child.getFamilyId(), child.getId(), type,
                ConsentPdf.title(type) + " — " + child.getFirstName(), file.getId(), file.getSha256(),
                user.displayName(), clock.instant()));
        return proposed(document);
    }

    private PortalDocumentDto proposed(PortalDocument document) {
        publisher.on(new PortalChange(document.getStructureId(), PortalItemType.DOCUMENT, document.getId()));
        audit.record(AuditAction.PORTAL_DOCUMENT_PROPOSED, "portal_document", document.getId(),
                Map.of("kind", document.getKind().name(), "childId", document.getChildId().toString()));
        return toDtos(List.of(document)).getFirst();
    }

    @Transactional
    public PortalDocumentDto withdraw(UUID id) {
        var document = document(id);
        if (document.getStatus() == PortalDocument.Status.ENREGISTRE) {
            throw ApiException.rule("document_recorded", "La signature est déjà enregistrée.");
        }
        document.withdrawn();
        publisher.on(new PortalChange(document.getStructureId(), PortalItemType.DOCUMENT, id));
        audit.record(AuditAction.PORTAL_DOCUMENT_WITHDRAWN, "portal_document", id, Map.of());
        return toDtos(List.of(document)).getFirst();
    }

    /**
     * Enregistre la signature électronique reçue du portail : la version du contrat est signée (le PDF signé est
     * joint), ou l'autorisation est accordée. La preuve (nom, date, IP, empreinte) reste sur le document.
     */
    @Transactional
    public PortalDocumentDto record(UUID id) {
        var user = CurrentUser.get();
        var document = document(id);
        if (document.getStatus() != PortalDocument.Status.SIGNE) {
            throw ApiException.rule("document_not_signed", "Ce document n’a pas été signé sur le portail.");
        }
        var signedOn = document.getSignedAt().atZone(NOUMEA).toLocalDate();
        if (document.getKind() == PortalDocument.Kind.CONTRAT) {
            var pdf = fileStore.read(files.findById(document.getFileId()).orElseThrow());
            contracts.sign(document.getContractId(), document.getVersionNumber(), signedOn, pdf,
                    "signature-portail-%s.pdf".formatted(signedOn));
        } else {
            var consent = consents.findByChildId(document.getChildId()).stream()
                    .filter(c -> c.getType() == document.getConsentType()).findFirst()
                    .orElseGet(() -> new Consent(document.getStructureId(), document.getChildId(), document.getConsentType()));
            // La famille peut aussi refuser une autorisation : elle est alors enregistrée comme refusée.
            consent.update(!document.isRefused(), "%s sur le portail parents (empreinte %s)".formatted(
                    document.isRefused() ? "Refusée" : "Signée", document.getSha256().substring(0, 12)),
                    signedOn, document.getSignerName());
            consents.save(consent);
            publisher.on(new PortalChange(document.getStructureId(), PortalItemType.CHILD, document.getChildId()));
        }
        document.recorded(user.displayName(), clock.instant());
        publisher.on(new PortalChange(document.getStructureId(), PortalItemType.DOCUMENT, id));
        var proof = new LinkedHashMap<String, Object>();
        proof.put("signataire", document.getSignerName());
        proof.put("decision", document.isRefused() ? "REFUSE" : "ACCEPTE");
        proof.put("signeLe", document.getSignedAt().toString());
        proof.put("ip", String.valueOf(document.getSignerIp()));
        proof.put("sha256", document.getSignedSha256());
        audit.record(AuditAction.PORTAL_SIGNATURE_RECORDED, "portal_document", id, proof);
        return toDtos(List.of(document)).getFirst();
    }

    private PortalDocument document(UUID id) {
        return documents.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Document introuvable."));
    }

    private List<PortalDocumentDto> toDtos(List<PortalDocument> found) {
        var names = new HashMap<UUID, String>();
        children.findAllById(found.stream().map(PortalDocument::getChildId).distinct().toList())
                .forEach(c -> names.put(c.getId(), c.getFirstName() + " " + c.getLastName()));
        return found.stream().map(d -> new PortalDocumentDto(d.getId(), d.getFamilyId(), d.getChildId(),
                names.get(d.getChildId()), d.getKind(), d.getTitle(), d.getStatus(), d.getProposedBy(),
                d.getProposedAt(), d.getSignerName(), d.isRefused(), d.getSignedAt(), d.getSignerIp(), d.getSignedSha256(),
                d.getRecordedBy(), d.getRecordedAt())).toList();
    }

    // --- Messages -----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ThreadSummary> threads() {
        var structureId = CurrentUser.get().structureId();
        var byFamily = messages.findByStructureIdOrderBySentAtDesc(structureId).stream()
                .collect(Collectors.groupingBy(FamilyMessage::getFamilyId, LinkedHashMap::new, Collectors.toList()));
        var invited = accesses.findByStructureId(structureId).stream().map(GuardianPortalAccess::getFamilyId)
                .collect(Collectors.toSet());
        var familyIds = new java.util.LinkedHashSet<>(byFamily.keySet());
        familyIds.addAll(invited);
        var labels = families.findAllById(familyIds).stream()
                .filter(f -> f.getStructureId().equals(structureId))
                .collect(Collectors.toMap(Family::getId, Family::getLabel));
        return familyIds.stream().filter(labels::containsKey).map(id -> {
            var list = byFamily.getOrDefault(id, List.of());
            var last = list.isEmpty() ? null : list.getFirst();
            return new ThreadSummary(id, labels.get(id), last == null ? null : last.getBody(),
                    last == null ? null : last.getSentAt(),
                    list.stream().filter(m -> m.getReadAt() == null).count(), invited.contains(id));
        }).sorted(Comparator.comparing(ThreadSummary::lastAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ThreadSummary::familyLabel)).toList();
    }

    @Transactional(readOnly = true)
    public long unread() {
        return messages.countByStructureIdAndReadAtIsNull(CurrentUser.get().structureId());
    }

    /** Fil d'une famille ; les messages des parents sont marqués lus. */
    @Transactional
    public Thread thread(UUID familyId) {
        var family = family(familyId);
        var list = messages.findByStructureIdAndFamilyIdOrderBySentAtAsc(family.getStructureId(), familyId);
        var now = clock.instant();
        list.forEach(m -> m.read(now));
        return new Thread(familyId, family.getLabel(), accesses.existsByFamilyId(familyId),
                list.stream().map(m -> new MessageDto(m.getId(), m.getDirection(), m.getAuthorName(), m.getBody(),
                        m.getSentAt(), m.getReadAt())).toList());
    }

    @Transactional
    public Thread send(UUID familyId, String body) {
        var user = CurrentUser.get();
        requireActive(user.structureId());
        var family = family(familyId);
        requireAccess(familyId);
        var message = messages.save(FamilyMessage.fromTeam(user.structureId(), family.getId(), user.userId(),
                user.displayName(), body.strip(), clock.instant()));
        publisher.on(new PortalChange(user.structureId(), PortalItemType.MESSAGE, message.getId()));
        audit.record(AuditAction.FAMILY_MESSAGE_SENT, "family", familyId, Map.of());
        return thread(familyId);
    }

    // --- Absences signalées -------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ReportedAbsenceDto> pendingAbsences() {
        var structureId = CurrentUser.get().structureId();
        var found = reportedAbsences.findByStructureIdAndStatusOrderByFromDayAsc(structureId, ReportedAbsence.Status.SIGNALEE);
        var names = new HashMap<UUID, String>();
        children.findAllById(found.stream().map(ReportedAbsence::getChildId).distinct().toList())
                .forEach(c -> names.put(c.getId(), c.getFirstName() + " " + c.getLastName()));
        return found.stream().map(r -> toDto(r, names.get(r.getChildId()))).toList();
    }

    /** La direction confirme : l'absence entre dans la facturation avec le motif choisi (déductible ou non). */
    @Transactional
    public ReportedAbsenceDto confirm(UUID id, UUID reasonId) {
        var user = CurrentUser.get();
        var reported = reported(id);
        var note = "Signalée sur le portail" + (reported.getGuardianName() == null ? "" : " par " + reported.getGuardianName())
                + (reported.getReason() == null ? "" : " : " + reported.getReason());
        var created = absences.create(reported.getChildId(), new AbsenceRequest(reported.getFromDay(), reported.getToDay(),
                reasonId, reported.getReportedAt().atZone(NOUMEA).toLocalDate(), null,
                note.length() > 300 ? note.substring(0, 300) : note));
        reported.confirmed(created.id(), user.displayName(), clock.instant());
        audit.record(AuditAction.REPORTED_ABSENCE_CONFIRMED, "reported_absence", id, Map.of("absenceId", created.id().toString()));
        return toDto(reported, childName(reported.getChildId()));
    }

    @Transactional
    public ReportedAbsenceDto dismiss(UUID id) {
        var user = CurrentUser.get();
        var reported = reported(id);
        reported.dismissed(user.displayName(), clock.instant());
        audit.record(AuditAction.REPORTED_ABSENCE_DISMISSED, "reported_absence", id, Map.of());
        return toDto(reported, childName(reported.getChildId()));
    }

    private ReportedAbsence reported(UUID id) {
        var reported = reportedAbsences.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Absence signalée introuvable."));
        if (reported.getStatus() != ReportedAbsence.Status.SIGNALEE) {
            throw ApiException.conflict("absence_handled", "Cette absence a déjà été traitée.");
        }
        return reported;
    }

    private String childName(UUID childId) {
        return children.findById(childId).map(c -> c.getFirstName() + " " + c.getLastName()).orElse(null);
    }

    private static ReportedAbsenceDto toDto(ReportedAbsence r, String childName) {
        return new ReportedAbsenceDto(r.getId(), r.getChildId(), childName, r.getGuardianName(), r.getFromDay(),
                r.getToDay(), r.getReason(), r.getReportedAt(), r.getStatus());
    }

    // --- Outils -------------------------------------------------------------------------------

    private boolean active(UUID structureId) {
        return settingsRepository.findById(structureId).map(PortalSettings::isActive).orElse(false);
    }

    private void requireActive(UUID structureId) {
        if (!active(structureId)) {
            throw ApiException.rule("portal_inactive", "Activez d’abord le portail parents (Paramètres › Portail parents).");
        }
    }

    private void requireAccess(UUID familyId) {
        if (!accesses.existsByFamilyId(familyId)) {
            throw ApiException.rule("family_not_invited", "Aucun responsable de cette famille n’est invité au portail.");
        }
    }

    private Family family(UUID familyId) {
        return families.findById(familyId).filter(f -> f.getStructureId().equals(CurrentUser.get().structureId()))
                .orElseThrow(() -> ApiException.notFound("Famille introuvable."));
    }

    private Child child(UUID childId) {
        return children.findByIdAndStructureId(childId, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Enfant introuvable."));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

}
