package nc.ticagou.portal;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import nc.ticagou.license.LicenseModule;
import nc.ticagou.license.RequiresModule;
import nc.ticagou.portal.PortalDtos.ActivateRequest;
import nc.ticagou.portal.PortalDtos.ConfirmAbsence;
import nc.ticagou.portal.PortalDtos.DeactivateRequest;
import nc.ticagou.portal.PortalDtos.FamilyAccess;
import nc.ticagou.portal.PortalDtos.HealthShareDto;
import nc.ticagou.portal.PortalDtos.HealthShareRequest;
import nc.ticagou.portal.PortalDtos.MessageRequest;
import nc.ticagou.portal.PortalDtos.PortalDocumentDto;
import nc.ticagou.portal.PortalDtos.PortalStatus;
import nc.ticagou.portal.PortalDtos.ProposeConsent;
import nc.ticagou.portal.PortalDtos.ProposeContract;
import nc.ticagou.portal.PortalDtos.ReportedAbsenceDto;
import nc.ticagou.portal.PortalDtos.Shares;
import nc.ticagou.portal.PortalDtos.SyncNow;
import nc.ticagou.portal.PortalDtos.Thread;
import nc.ticagou.portal.PortalDtos.ThreadSummary;

/** Portail parents optionnel (ADR 0010) : réglages et échanges avec les familles, côté crèche. */
@RestController
@RequestMapping("/api/v1")
@RequiresModule(LicenseModule.PORTAIL_PARENTS)
@Tag(name = "Portail parents", description = "Activation, partage, accès des familles, messages, absences, signatures")
public class PortalController {

    static final String DIRECTION = "hasRole('DIRECTION')";
    static final String TEAM = "hasAnyRole('DIRECTION', 'EDUCATRICE')";

    private final PortalService portal;

    public PortalController(PortalService portal) {
        this.portal = portal;
    }

    // --- Réglages -----------------------------------------------------------------------------

    @GetMapping("/portal")
    @PreAuthorize(DIRECTION)
    @Operation(summary = "État du portail : activation, partage, dernière synchronisation")
    public PortalStatus status() {
        return portal.status();
    }

    @PostMapping("/portal/activate")
    @PreAuthorize(DIRECTION)
    @Operation(summary = "Activer le portail avec l'adresse et le code d'activation de l'hébergeur")
    public PortalStatus activate(@Valid @RequestBody ActivateRequest request) {
        return portal.activate(request);
    }

    @PutMapping("/portal/shares")
    @PreAuthorize(DIRECTION)
    @Operation(summary = "Choisir ce qui est partagé avec les familles")
    public PortalStatus shares(@Valid @RequestBody Shares shares) {
        return portal.updateShares(shares);
    }

    @PostMapping("/portal/deactivate")
    @PreAuthorize(DIRECTION)
    @Operation(summary = "Désactiver le portail et effacer tout ce qu'il détient pour la structure")
    public SyncNow deactivate(@Valid @RequestBody DeactivateRequest request) {
        portal.deactivate(request.confirmation());
        return portal.syncNow();
    }

    @PostMapping("/portal/sync")
    @PreAuthorize(DIRECTION)
    @Operation(summary = "Synchroniser maintenant")
    public SyncNow sync() {
        return portal.syncNow();
    }

    // --- Accès des familles -------------------------------------------------------------------

    @GetMapping("/families/{familyId}/portal")
    @PreAuthorize(DIRECTION)
    @Operation(summary = "Responsables de la famille et leur accès au portail")
    public FamilyAccess familyAccess(@PathVariable UUID familyId) {
        return portal.familyAccess(familyId);
    }

    @PostMapping("/guardians/{guardianId}/portal-access")
    @PreAuthorize(DIRECTION)
    @Operation(summary = "Inviter un responsable au portail (connexion par son e-mail)")
    public FamilyAccess invite(@PathVariable UUID guardianId) {
        return portal.invite(guardianId);
    }

    @DeleteMapping("/guardians/{guardianId}/portal-access")
    @PreAuthorize(DIRECTION)
    @Operation(summary = "Retirer l'accès d'un responsable")
    public FamilyAccess revoke(@PathVariable UUID guardianId) {
        return portal.revoke(guardianId);
    }

    // --- Santé partagée -----------------------------------------------------------------------

    @GetMapping("/children/{childId}/health-shares")
    @PreAuthorize(DIRECTION)
    @Operation(summary = "Informations de santé partagées avec la famille")
    public List<HealthShareDto> healthShares(@PathVariable UUID childId) {
        return portal.healthShares(childId);
    }

    @PostMapping("/children/{childId}/health-shares")
    @PreAuthorize(DIRECTION)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Partager une information de santé précise avec la famille (texte figé)")
    public HealthShareDto shareHealth(@PathVariable UUID childId, @Valid @RequestBody HealthShareRequest request) {
        return portal.shareHealth(childId, request);
    }

    @DeleteMapping("/health-shares/{id}")
    @PreAuthorize(DIRECTION)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Ne plus partager cette information")
    public void unshareHealth(@PathVariable UUID id) {
        portal.unshareHealth(id);
    }

    // --- Documents à signer -------------------------------------------------------------------

    @GetMapping("/portal/documents")
    @PreAuthorize(DIRECTION)
    @Operation(summary = "Documents d'une famille, ou signatures reçues à enregistrer")
    public List<PortalDocumentDto> documents(@RequestParam(required = false) UUID familyId) {
        return portal.documents(familyId);
    }

    @PostMapping("/portal/documents/contract")
    @PreAuthorize(DIRECTION)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Proposer un contrat ou un avenant à la signature")
    public PortalDocumentDto proposeContract(@Valid @RequestBody ProposeContract request) {
        return portal.proposeContract(request.contractId(), request.versionNumber());
    }

    @PostMapping("/portal/documents/consent")
    @PreAuthorize(DIRECTION)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Proposer une autorisation parentale à la signature")
    public PortalDocumentDto proposeConsent(@Valid @RequestBody ProposeConsent request) {
        return portal.proposeConsent(request.childId(), request.type());
    }

    @PostMapping("/portal/documents/{id}/withdraw")
    @PreAuthorize(DIRECTION)
    @Operation(summary = "Retirer un document du portail")
    public PortalDocumentDto withdraw(@PathVariable UUID id) {
        return portal.withdraw(id);
    }

    @PostMapping("/portal/documents/{id}/record")
    @PreAuthorize(DIRECTION)
    @Operation(summary = "Enregistrer la signature reçue : signe le contrat ou accorde l'autorisation")
    public PortalDocumentDto record(@PathVariable UUID id) {
        return portal.record(id);
    }

    // --- Messages -----------------------------------------------------------------------------

    @GetMapping("/messages")
    @PreAuthorize(TEAM)
    @Operation(summary = "Fils de messages des familles")
    public List<ThreadSummary> threads() {
        return portal.threads();
    }

    @GetMapping("/messages/unread")
    @PreAuthorize(TEAM)
    @Operation(summary = "Nombre de messages des familles non lus")
    public long unread() {
        return portal.unread();
    }

    @GetMapping("/families/{familyId}/messages")
    @PreAuthorize(TEAM)
    @Operation(summary = "Fil d'une famille (marque les messages reçus comme lus)")
    public Thread thread(@PathVariable UUID familyId) {
        return portal.thread(familyId);
    }

    @PostMapping("/families/{familyId}/messages")
    @PreAuthorize(TEAM)
    @Operation(summary = "Écrire à une famille")
    public Thread send(@PathVariable UUID familyId, @Valid @RequestBody MessageRequest request) {
        return portal.send(familyId, request.body());
    }

    // --- Absences signalées -------------------------------------------------------------------

    @GetMapping("/portal/reported-absences")
    @PreAuthorize(TEAM)
    @Operation(summary = "Absences signalées par les parents, à confirmer")
    public List<ReportedAbsenceDto> reportedAbsences() {
        return portal.pendingAbsences();
    }

    @PostMapping("/portal/reported-absences/{id}/confirm")
    @PreAuthorize(DIRECTION)
    @Operation(summary = "Confirmer : l'absence entre dans la facturation avec ce motif")
    public ReportedAbsenceDto confirm(@PathVariable UUID id, @Valid @RequestBody ConfirmAbsence request) {
        return portal.confirm(id, request.reasonId());
    }

    @PostMapping("/portal/reported-absences/{id}/dismiss")
    @PreAuthorize(DIRECTION)
    @Operation(summary = "Écarter une absence signalée")
    public ReportedAbsenceDto dismiss(@PathVariable UUID id) {
        return portal.dismiss(id);
    }
}
