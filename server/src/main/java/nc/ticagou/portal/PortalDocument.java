package nc.ticagou.portal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import nc.ticagou.child.Consent;

/**
 * Document proposé à la signature sur le portail : un contrat (ou avenant) ou une autorisation. La signature
 * électronique simple remonte du portail (nom saisi, date, IP, empreinte du PDF) ; la direction l'enregistre, ce qui
 * signe la version du contrat ou accorde l'autorisation.
 */
@Entity
@Table(name = "portal_document")
public class PortalDocument {

    public enum Kind { CONTRAT, AUTORISATION }

    /** À signer, signé sur le portail (en attente de la direction), enregistré, ou retiré par la direction. */
    public enum Status { A_SIGNER, SIGNE, ENREGISTRE, RETIRE }

    @Id
    private UUID id;

    @Column(name = "structure_id", nullable = false, updatable = false)
    private UUID structureId;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Column(name = "child_id", nullable = false, updatable = false)
    private UUID childId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Kind kind;

    @Column(name = "contract_id", updatable = false)
    private UUID contractId;

    @Column(name = "version_number", updatable = false)
    private Integer versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", updatable = false)
    private Consent.Type consentType;

    @Column(nullable = false, updatable = false)
    private String title;

    @Column(name = "file_id", nullable = false, updatable = false)
    private UUID fileId;

    @Column(nullable = false, updatable = false)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "proposed_by", nullable = false, updatable = false)
    private String proposedBy;

    @Column(name = "proposed_at", nullable = false, updatable = false)
    private Instant proposedAt;

    @Column(name = "signer_name")
    private String signerName;

    @Column(name = "signer_guardian_id")
    private UUID signerGuardianId;

    @Column(name = "signed_at")
    private Instant signedAt;

    @Column(name = "signer_ip")
    private String signerIp;

    /** ACCEPTE ou REFUSE : une autorisation peut être refusée par la famille. */
    @Column(name = "signed_decision")
    private String signedDecision;

    @Column(name = "signed_sha256")
    private String signedSha256;

    @Column(name = "recorded_by")
    private String recordedBy;

    @Column(name = "recorded_at")
    private Instant recordedAt;

    @Column(name = "portal_ref")
    private UUID portalRef;

    protected PortalDocument() {
    }

    private PortalDocument(UUID structureId, UUID familyId, UUID childId, Kind kind, String title, UUID fileId,
                           String sha256, String proposedBy, Instant proposedAt) {
        this.id = UUID.randomUUID();
        this.structureId = structureId;
        this.familyId = familyId;
        this.childId = childId;
        this.kind = kind;
        this.title = title;
        this.fileId = fileId;
        this.sha256 = sha256;
        this.status = Status.A_SIGNER;
        this.proposedBy = proposedBy;
        this.proposedAt = proposedAt;
    }

    public static PortalDocument contract(UUID structureId, UUID familyId, UUID childId, UUID contractId, int number,
                                          String title, UUID fileId, String sha256, String by, Instant at) {
        var document = new PortalDocument(structureId, familyId, childId, Kind.CONTRAT, title, fileId, sha256, by, at);
        document.contractId = contractId;
        document.versionNumber = number;
        return document;
    }

    public static PortalDocument consent(UUID structureId, UUID familyId, UUID childId, Consent.Type type,
                                         String title, UUID fileId, String sha256, String by, Instant at) {
        var document = new PortalDocument(structureId, familyId, childId, Kind.AUTORISATION, title, fileId, sha256, by, at);
        document.consentType = type;
        return document;
    }

    /** Signature reçue du portail ; l'empreinte signée doit être celle du document proposé. */
    public void signed(UUID portalRef, UUID guardianId, String signerName, boolean refused, Instant at, String ip,
                       String signedSha256) {
        this.signedDecision = refused ? "REFUSE" : "ACCEPTE";
        this.portalRef = portalRef;
        this.signerGuardianId = guardianId;
        this.signerName = signerName;
        this.signedAt = at;
        this.signerIp = ip;
        this.signedSha256 = signedSha256;
        this.status = Status.SIGNE;
    }

    public void recorded(String by, Instant at) {
        this.recordedBy = by;
        this.recordedAt = at;
        this.status = Status.ENREGISTRE;
    }

    public void withdrawn() {
        this.status = Status.RETIRE;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public UUID getChildId() {
        return childId;
    }

    public Kind getKind() {
        return kind;
    }

    public UUID getContractId() {
        return contractId;
    }

    public Integer getVersionNumber() {
        return versionNumber;
    }

    public Consent.Type getConsentType() {
        return consentType;
    }

    public String getTitle() {
        return title;
    }

    public UUID getFileId() {
        return fileId;
    }

    public String getSha256() {
        return sha256;
    }

    public Status getStatus() {
        return status;
    }

    public String getProposedBy() {
        return proposedBy;
    }

    public Instant getProposedAt() {
        return proposedAt;
    }

    public String getSignerName() {
        return signerName;
    }

    public Instant getSignedAt() {
        return signedAt;
    }

    public String getSignerIp() {
        return signerIp;
    }

    public boolean isRefused() {
        return "REFUSE".equals(signedDecision);
    }

    public String getSignedSha256() {
        return signedSha256;
    }

    public String getRecordedBy() {
        return recordedBy;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
