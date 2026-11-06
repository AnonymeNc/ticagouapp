package nc.ticagou.billing;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import nc.ticagou.billing.BillingDtos.CreditNoteDto;
import nc.ticagou.billing.BillingDtos.CreditNoteRequest;
import nc.ticagou.billing.BillingDtos.FamilyPreview;
import nc.ticagou.billing.BillingDtos.FunderRequestDto;
import nc.ticagou.billing.BillingDtos.GenerateRequest;
import nc.ticagou.billing.BillingDtos.GenerationResult;
import nc.ticagou.billing.BillingDtos.InvoiceDto;
import nc.ticagou.billing.BillingDtos.InvoiceStatus;
import nc.ticagou.billing.BillingDtos.InvoiceSummary;
import nc.ticagou.billing.BillingDtos.MarkRequestSentRequest;
import nc.ticagou.billing.BillingDtos.MarkSentRequest;
import nc.ticagou.billing.BillingDtos.MonthPreview;
import nc.ticagou.license.LicenseModule;
import nc.ticagou.license.RequiresModule;

/** Facturation : direction et comptabilité (maquette « Facturation et paiements »). */
@RestController
@RequiresModule(LicenseModule.FACTURATION)
@RequestMapping("/api/v1/billing")
@Tag(name = "Facturation", description = "Facturer le mois, factures, avoirs, demandes aux financeurs")
@PreAuthorize(BillingSettingsController.MANAGERS)
public class BillingController {

    private final BillingService billing;
    private final InvoiceService invoices;
    private final FunderRequestService funderRequests;

    public BillingController(BillingService billing, InvoiceService invoices, FunderRequestService funderRequests) {
        this.billing = billing;
        this.invoices = invoices;
        this.funderRequests = funderRequests;
    }

    @GetMapping("/months/{month}/preview")
    @Operation(summary = "Prévisualiser la facturation du mois (AAAA-MM) : familles, anomalies, totaux")
    public MonthPreview preview(@PathVariable YearMonth month) {
        return billing.preview(month);
    }

    @GetMapping("/months/{month}/preview/{familyId}")
    @Operation(summary = "Détail ligne à ligne de la facture à venir d'une famille")
    public FamilyPreview previewFamily(@PathVariable YearMonth month, @PathVariable UUID familyId) {
        return billing.previewFamily(month, familyId);
    }

    @PostMapping("/months/{month}/generate")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Générer en lot les factures des familles prêtes et les demandes aux financeurs")
    public GenerationResult generate(@PathVariable YearMonth month,
                                     @RequestBody(required = false) GenerateRequest request) {
        return billing.generate(month, request);
    }

    @GetMapping("/invoices")
    @Operation(summary = "Factures, filtrées par mois, famille ou état")
    public List<InvoiceSummary> invoices(@RequestParam(required = false) YearMonth month,
                                         @RequestParam(required = false) UUID familyId,
                                         @RequestParam(required = false) InvoiceStatus status) {
        return invoices.list(month, familyId, status);
    }

    @GetMapping("/invoices/{id}")
    @Operation(summary = "Détail d'une facture : lignes, avoirs, paiements, historique")
    public InvoiceDto invoice(@PathVariable UUID id) {
        return invoices.get(id);
    }

    @PostMapping("/invoices/{id}/sent")
    @Operation(summary = "Noter la facture comme envoyée (aucun e-mail n'est envoyé)")
    public InvoiceDto markSent(@PathVariable UUID id, @Valid @RequestBody MarkSentRequest request) {
        return invoices.markSent(id, request);
    }

    @PostMapping("/invoices/{id}/credit-notes")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Faire un avoir total ou partiel")
    public CreditNoteDto creditNote(@PathVariable UUID id, @Valid @RequestBody CreditNoteRequest request) {
        return invoices.createCreditNote(id, request);
    }

    @GetMapping("/credit-notes/{id}")
    @Operation(summary = "Détail d'un avoir")
    public CreditNoteDto getCreditNote(@PathVariable UUID id) {
        return invoices.getCreditNote(id);
    }

    @GetMapping("/funder-requests")
    @Operation(summary = "Demandes aux financeurs d'un mois, ou historique complet")
    public List<FunderRequestDto> funderRequests(@RequestParam(required = false) YearMonth month) {
        return funderRequests.list(month);
    }

    @GetMapping("/funder-requests/{id}")
    @Operation(summary = "Détail d'une demande au financeur")
    public FunderRequestDto funderRequest(@PathVariable UUID id) {
        return funderRequests.get(id);
    }

    @PostMapping("/funder-requests/{id}/sent")
    @Operation(summary = "Noter la demande comme envoyée")
    public FunderRequestDto markRequestSent(@PathVariable UUID id,
                                            @Valid @RequestBody MarkRequestSentRequest request) {
        return funderRequests.markSent(id, request);
    }
}
