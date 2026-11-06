package nc.ticagou.child;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.audit.ChildAccessContext;
import nc.ticagou.audit.ChildAccessLogger;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.child.ChildDtos.DocumentDto;
import nc.ticagou.common.ApiException;
import nc.ticagou.contract.ContractVersionRepository;
import nc.ticagou.document.EncryptedFileStore;
import nc.ticagou.document.StoredFileRepository;
import nc.ticagou.document.Uploads;

/** Documents joints au dossier d'un enfant (chiffrés sur disque). */
@Service
public class ChildDocumentService {

    private final ChildService children;
    private final ChildDocumentRepository documents;
    private final HealthInfoRepository healthInfos;
    private final EncryptedFileStore fileStore;
    private final StoredFileRepository files;
    private final ChildRecordAssembler assembler;
    private final AuditService audit;
    private final ChildAccessLogger accessLog;
    private final ContractVersionRepository contractVersions;
    private final Clock clock;

    public ChildDocumentService(ChildService children, ChildDocumentRepository documents,
                                HealthInfoRepository healthInfos, EncryptedFileStore fileStore,
                                StoredFileRepository files, ChildRecordAssembler assembler, AuditService audit,
                                ChildAccessLogger accessLog, ContractVersionRepository contractVersions, Clock clock) {
        this.children = children;
        this.documents = documents;
        this.healthInfos = healthInfos;
        this.fileStore = fileStore;
        this.files = files;
        this.assembler = assembler;
        this.audit = audit;
        this.accessLog = accessLog;
        this.contractVersions = contractVersions;
        this.clock = clock;
    }

    @Transactional
    public DocumentDto add(UUID childId, byte[] content, String originalName, String name,
                           ChildDocument.Category category, LocalDate validUntil) {
        var child = children.load(childId);
        var type = Uploads.checkDocument(content);
        if (name == null || name.isBlank() || name.length() > 150) {
            throw ApiException.rule("document_name", "Donnez un nom au document (150 caractères au maximum).");
        }
        var stored = fileStore.store(child.getStructureId(), content, type);
        var fileName = Uploads.safeFileName(originalName, "document." + Uploads.extension(type));
        var document = documents.saveAndFlush(new ChildDocument(child.getStructureId(), childId, stored.getId(),
                name.trim(), fileName, category, validUntil, clock.instant(), CurrentUser.get().displayName()));
        audit.record(AuditAction.DOCUMENT_ADDED, "child_document", document.getId(),
                Map.of("child", childId, "category", category.name()));
        return assembler.documents(childId).stream().filter(d -> d.id().equals(document.getId())).findFirst()
                .orElseThrow();
    }

    /** Contenu déchiffré ; le téléchargement est inscrit au journal des dossiers enfants. */
    @Transactional
    public Download content(UUID documentId) {
        var document = load(documentId);
        var file = files.findById(document.getFileId()).orElseThrow();
        accessLog.log(document.getChildId(), ChildAccessContext.DOCUMENTS);
        return new Download(fileStore.read(file), file.getContentType(), document.getFileName());
    }

    @Transactional
    public void delete(UUID documentId) {
        var document = load(documentId);
        if (contractVersions.existsBySignedDocumentId(documentId)) {
            throw ApiException.conflict("document_in_use",
                    "Ce document est l’exemplaire signé d’un contrat : il doit rester au dossier.");
        }
        healthInfos.clearPaiDocument(documentId);
        documents.deleteById(documentId);
        documents.flush();
        files.findById(document.getFileId()).ifPresent(fileStore::delete);
        audit.record(AuditAction.DOCUMENT_DELETED, "child_document", documentId,
                Map.of("child", document.getChildId(), "category", document.getCategory().name()));
    }

    private ChildDocument load(UUID id) {
        return documents.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Document introuvable."));
    }

    public record Download(byte[] content, String contentType, String fileName) {
    }
}
