package nc.ticagou.console.billing;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import nc.ticagou.console.auth.SensitiveAction;
import nc.ticagou.console.billing.CatalogueRepository.Issuer;
import nc.ticagou.console.billing.Pricing.PriceItem;
import nc.ticagou.console.billing.Pricing.PriceList;
import nc.ticagou.console.common.ApiException;

/** Facturation de l'éditeur (plan console, phase 3) : factures, avoirs, paiements, contrats, barème, boîte d'envoi. */
@RestController
@RequestMapping("/api/v1")
class BillingController {

    record LineRequest(
            @NotBlank(message = "Chaque ligne a une désignation.") @Size(max = 200) String label,
            @Size(max = 300) String detail,
            @Min(value = 1, message = "Quantité : 1 au moins.") @Max(10_000) int quantity,
            @Min(value = -100_000_000) @Max(100_000_000) long unitPrice,
            @NotNull @Pattern(regexp = "ABONNEMENT|PRESTATION|MATERIEL") String tgcNature) {
    }

    record DraftRequest(
            @NotBlank String crecheSlug,
            @NotBlank(message = "L’objet de la facture est obligatoire.") @Size(max = 200) String subject,
            LocalDate periodFrom, LocalDate periodTo,
            @Size(max = 1000) String note,
            @NotEmpty(message = "Une facture a au moins une ligne.") List<@Valid LineRequest> lines) {

        InvoiceService.DraftInput input() {
            return new InvoiceService.DraftInput(crecheSlug, subject, periodFrom, periodTo, note,
                    lines.stream().map(l -> new InvoiceService.LineInput(l.label(), l.detail(), l.quantity(), l.unitPrice(), l.tgcNature())).toList());
        }
    }

    record CreditRequest(@NotEmpty List<@Valid CreditLineRequest> lines,
                         @NotBlank(message = "Indiquez le motif de l’avoir.") @Size(max = 1000) String reason) {
    }

    record CreditLineRequest(@NotNull UUID lineId, @Min(0) long amount) {
    }

    record IdsRequest(@NotEmpty List<UUID> ids) {
    }

    record DiscardRequest(@NotEmpty List<UUID> ids, @Size(max = 300) String reason) {
    }

    record PauseRequest(boolean paused) {
    }

    record PaymentRequest(
            @NotBlank String crecheSlug,
            @NotNull(message = "Date du paiement obligatoire.") LocalDate receivedOn,
            @Positive(message = "Le montant doit être positif.") long amount,
            @NotNull @Pattern(regexp = "VIREMENT|CHEQUE|ESPECES|AUTRE") String method,
            @Size(max = 100) String reference,
            @Size(max = 300) String note,
            List<@Valid AllocationRequest> allocations) {
    }

    record AllocationRequest(@NotNull UUID invoiceId, @Min(0) long amount) {
    }

    record ContractRequest(
            @NotNull @Pattern(regexp = "COMPLET", message = "Une seule formule : Ti’Cagou, tout compris.") String plan,
            @Min(value = 1, message = "Places : 1 au moins.") @Max(1000) int places,
            Set<String> modules,
            @Min(0) @Max(50) int extraDesktops,
            @Min(0) @Max(50) int extraTablets,
            boolean premiumSupport,
            @Min(1) @Max(3) int engagementYears,
            @NotNull @Pattern(regexp = "ANNUEL|MENSUEL") String rhythm,
            boolean extraStructure,
            @Min(0) @Max(1000) int freeDiscount,
            @Size(max = 200) String freeDiscountReason,
            @NotNull(message = "Date de début obligatoire.") LocalDate startsOn,
            @NotNull(message = "Date de signature obligatoire.") LocalDate signedOn,
            LocalDate changeOn) {

        ContractService.Input input() {
            if (engagementYears != 1 && engagementYears != 3) {
                throw ApiException.rule("engagement", "Engagement : 1 ou 3 ans.");
            }
            return new ContractService.Input(plan, places, modules == null ? Set.of() : new LinkedHashSet<>(modules), extraDesktops, extraTablets,
                    premiumSupport, engagementYears, rhythm, extraStructure, freeDiscount, freeDiscountReason, startsOn, signedOn, changeOn);
        }
    }

    record TerminateRequest(@NotNull LocalDate endsOn) {
    }

    record PriceItemRequest(
            @NotNull @Pattern(regexp = "FORMULE|MODULE|EQUIPEMENT|SUPPORT|PRESTATION") String kind,
            @NotBlank @Size(max = 30) String code,
            @Pattern(regexp = "MICRO|STRUCTURE|GRANDE") String tier,
            @NotBlank @Size(max = 120) String label,
            @Min(0) long price,
            @Size(max = 20) String unit,
            @Size(max = 200) String includes,
            @NotNull @Pattern(regexp = "ABONNEMENT|PRESTATION|MATERIEL") String tgcNature) {
    }

    record PriceListRequest(
            @NotBlank(message = "Nom de la version obligatoire.") @Size(max = 80) String label,
            @NotNull(message = "Date de début obligatoire.") LocalDate validFrom,
            @Min(1) int microMax, @Min(2) int structureMax, @Min(3) int grandeMax,
            @Min(0) int includedDesktops, @Min(0) int includedTablets,
            @Min(0) @Max(1000) int engagement3Discount, @Min(0) @Max(1000) int extraStructureDiscount,
            @NotEmpty List<@Valid PriceItemRequest> items) {
    }

    record RateRequest(@NotNull @Pattern(regexp = "ABONNEMENT|PRESTATION|MATERIEL") String nature,
                       @Min(0) @Max(1000) int rate, @NotNull LocalDate validFrom, @Size(max = 200) String note) {
    }

    record IssuerRequest(@Size(max = 150) String name, @Size(max = 80) String legalForm, @Size(max = 20) String ridet,
                         @Size(max = 40) String tgcNumber, @Size(max = 400) String address,
                         @Email(message = "E-mail de l’éditeur invalide.") @Size(max = 200) String email, @Size(max = 40) String phone,
                         @Size(max = 120) String bank, @Size(max = 60) String iban, @Size(max = 500) String paymentTerms,
                         @Size(max = 500) String latePenalties, @Size(max = 500) String footer,
                         @Email(message = "E-mail de copie invalide.") @Size(max = 200) String copyEmail) {
    }

    record MessageRequest(@Size(max = 400) String to, @Size(max = 400) String cc,
                          @NotBlank(message = "L’objet est obligatoire.") @Size(max = 200) String subject,
                          @NotBlank(message = "Le message est vide.") @Size(max = 8000) String body) {
    }

    record ValidationResult(UUID id, String number, String error) {
    }

    private final InvoiceService invoices;
    private final ContractService contracts;
    private final PaymentService payments;
    private final CatalogueService catalogue;
    private final OutboxService outbox;
    private final SensitiveAction sensitive;
    private final BillingStats stats;

    BillingController(InvoiceService invoices, ContractService contracts, PaymentService payments, CatalogueService catalogue,
                      OutboxService outbox, SensitiveAction sensitive, BillingStats stats) {
        this.stats = stats;
        this.invoices = invoices;
        this.contracts = contracts;
        this.payments = payments;
        this.catalogue = catalogue;
        this.outbox = outbox;
        this.sensitive = sensitive;
    }

    // --- Factures

    @GetMapping("/factures")
    List<InvoiceService.Summary> invoices() {
        return invoices.list();
    }

    @GetMapping("/factures/{id}")
    InvoiceService.Detail invoice(@PathVariable UUID id) {
        return invoices.detail(id);
    }

    @PostMapping("/factures")
    @ResponseStatus(HttpStatus.CREATED)
    InvoiceService.Detail createDraft(@Valid @RequestBody DraftRequest request) {
        return invoices.createDraft(request.input());
    }

    @PutMapping("/factures/{id}")
    InvoiceService.Detail updateDraft(@PathVariable UUID id, @Valid @RequestBody DraftRequest request) {
        return invoices.updateDraft(id, request.input());
    }

    @DeleteMapping("/factures/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteDraft(@PathVariable UUID id) {
        invoices.deleteDraft(id);
    }

    /** Valider une facture : action sensible (code redemandé après 5 minutes). */
    @PostMapping("/factures/{id}/valider")
    InvoiceService.Detail validate(@PathVariable UUID id, HttpSession session) {
        sensitive.require(session);
        return invoices.validate(id);
    }

    /** Valider plusieurs brouillons : chacun pour soi, une erreur n'empêche pas les autres. */
    @PostMapping("/factures/valider")
    List<ValidationResult> validateMany(@Valid @RequestBody IdsRequest request, HttpSession session) {
        sensitive.require(session);
        var results = new ArrayList<ValidationResult>();
        for (var id : request.ids()) {
            try {
                results.add(new ValidationResult(id, invoices.validate(id).summary().number(), null));
            } catch (ApiException e) {
                results.add(new ValidationResult(id, null, e.getMessage()));
            }
        }
        return results;
    }

    @PostMapping("/factures/{id}/avoir")
    @ResponseStatus(HttpStatus.CREATED)
    InvoiceService.Detail credit(@PathVariable UUID id, @Valid @RequestBody CreditRequest request, HttpSession session) {
        sensitive.require(session);
        return invoices.credit(id, new InvoiceService.CreditInput(request.lines().stream()
                .map(l -> new InvoiceService.CreditLine(l.lineId(), l.amount())).toList(), request.reason()));
    }

    @PostMapping("/factures/{id}/relances")
    InvoiceService.Detail pause(@PathVariable UUID id, @RequestBody PauseRequest request) {
        return invoices.pauseReminders(id, request.paused());
    }

    @GetMapping("/factures/{id}/pdf")
    ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        var pdf = invoices.pdf(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(pdf.name()).build().toString())
                .header("Cache-Control", "no-store")
                .body(pdf.bytes());
    }

    /** Indicateurs du tableau de bord : revenu récurrent, encours, retards, délai de paiement, 6 mois facturé / encaissé. */
    @GetMapping("/tableau")
    BillingStats.Board board() {
        return stats.board();
    }

    // --- Paiements

    @GetMapping("/paiements")
    List<PaymentService.View> payments() {
        return payments.list();
    }

    @PostMapping("/paiements")
    @ResponseStatus(HttpStatus.CREATED)
    PaymentService.View pay(@Valid @RequestBody PaymentRequest r) {
        return payments.record(new PaymentService.Input(r.crecheSlug(), r.receivedOn(), r.amount(), r.method(), r.reference(), r.note(),
                r.allocations() == null ? List.of() : r.allocations().stream()
                        .map(a -> new PaymentService.AllocationInput(a.invoiceId(), a.amount())).toList()));
    }

    @DeleteMapping("/paiements/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deletePayment(@PathVariable UUID id) {
        payments.delete(id);
    }

    // --- Contrats

    @GetMapping("/parc/{slug}/contrat")
    ContractService.View contract(@PathVariable String slug) {
        return contracts.view(slug);
    }

    @PostMapping("/parc/{slug}/contrat/devis")
    List<Pricing.Quote> quote(@PathVariable String slug, @Valid @RequestBody ContractRequest request) {
        return contracts.quote(slug, request.input());
    }

    @PutMapping("/parc/{slug}/contrat")
    ContractService.Saved saveContract(@PathVariable String slug, @Valid @RequestBody ContractRequest request) {
        return contracts.save(slug, request.input());
    }

    @PostMapping("/parc/{slug}/contrat/resiliation")
    ContractService.View terminate(@PathVariable String slug, @Valid @RequestBody TerminateRequest request) {
        return contracts.terminate(slug, request.endsOn());
    }

    @PostMapping("/parc/{slug}/contrat/prochaine-facture")
    @ResponseStatus(HttpStatus.CREATED)
    InvoiceService.Detail prepareNext(@PathVariable String slug) {
        return invoices.detail(contracts.prepareNext(slug));
    }

    // --- Paramètres

    @GetMapping("/bareme")
    CatalogueService.Settings settings() {
        return catalogue.settings();
    }

    @PostMapping("/bareme")
    @ResponseStatus(HttpStatus.CREATED)
    CatalogueService.Settings newPriceList(@Valid @RequestBody PriceListRequest r) {
        if (!(r.microMax() < r.structureMax() && r.structureMax() < r.grandeMax())) {
            throw ApiException.rule("tranches", "Les tranches de places doivent se suivre : Micro < Structure < Grande.");
        }
        return catalogue.newVersion(new PriceList(null, r.label().strip(), r.validFrom(), null, r.microMax(), r.structureMax(), r.grandeMax(),
                r.includedDesktops(), r.includedTablets(), r.engagement3Discount(), r.extraStructureDiscount()),
                r.items().stream().map(i -> new PriceItem(i.kind(), i.code().strip(), i.tier(), i.label().strip(), i.price(), i.unit(),
                        i.includes(), i.tgcNature())).toList());
    }

    @PostMapping("/tgc")
    @ResponseStatus(HttpStatus.CREATED)
    CatalogueService.Settings newRate(@Valid @RequestBody RateRequest r) {
        return catalogue.newRate(r.nature(), r.rate(), r.validFrom(), r.note());
    }

    @PutMapping("/editeur")
    CatalogueService.Settings issuer(@Valid @RequestBody IssuerRequest r) {
        return catalogue.updateIssuer(new Issuer(t(r.name()), t(r.legalForm()), t(r.ridet()), t(r.tgcNumber()), t(r.address()), t(r.email()),
                t(r.phone()), t(r.bank()), t(r.iban()), t(r.paymentTerms()), t(r.latePenalties()), t(r.footer()), t(r.copyEmail())));
    }

    // --- Boîte d'envoi

    record OutboxView(List<OutboxService.View> messages, int pending) {
    }

    @GetMapping("/envoi")
    OutboxView messages(@RequestParam(name = "statut", required = false) String status) {
        return new OutboxView(outbox.list(status == null || status.isBlank() ? null : status), outbox.pending());
    }

    @PutMapping("/envoi/{id}")
    OutboxService.View editMessage(@PathVariable UUID id, @Valid @RequestBody MessageRequest r) {
        return outbox.edit(id, t(r.to()), t(r.cc()), r.subject().strip(), r.body());
    }

    @PostMapping("/envoi/envoyer")
    List<OutboxService.Result> send(@Valid @RequestBody IdsRequest request) {
        return outbox.send(request.ids());
    }

    @PostMapping("/envoi/ecarter")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void discard(@Valid @RequestBody DiscardRequest request) {
        outbox.discard(request.ids(), request.reason());
    }

    private static String t(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
