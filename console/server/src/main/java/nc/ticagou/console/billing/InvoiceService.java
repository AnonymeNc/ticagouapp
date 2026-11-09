package nc.ticagou.console.billing;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import nc.ticagou.console.audit.AuditService;
import nc.ticagou.console.auth.CurrentOperator;
import nc.ticagou.console.billing.InvoiceRepository.Invoice;
import nc.ticagou.console.billing.InvoiceRepository.Line;
import nc.ticagou.console.billing.InvoiceRepository.Settlement;
import nc.ticagou.console.common.ApiException;
import nc.ticagou.console.parc.ParcRepository;

/**
 * Factures de l'éditeur : brouillons (prestations, renouvellements, compléments), validation (numéro continu, mentions et
 * PDF figés, e-mail préparé dans la boîte d'envoi), avoirs, état de chaque facture (payée, en retard…). Une facture
 * validée ne change plus : on la corrige par un avoir.
 */
@Service
public class InvoiceService {

    public static final List<String> NATURES = List.of("ABONNEMENT", "PRESTATION", "MATERIEL");

    public record LineInput(String label, String detail, int quantity, long unitPrice, String tgcNature) {
    }

    public record DraftInput(String crecheSlug, String subject, LocalDate periodFrom, LocalDate periodTo, String note, List<LineInput> lines) {
    }

    public record CreditInput(List<CreditLine> lines, String reason) {
    }

    public record CreditLine(UUID lineId, long amount) {
    }

    /**
     * Une facture dans les listes. {@code state} : BROUILLON, A_ENVOYER, ENVOYEE, PARTIELLE, PAYEE, RETARD, ANNULEE (soldée
     * par avoir) ou AVOIR. {@code remaining} : reste dû (négatif : trop-perçu à rendre).
     */
    public record Summary(UUID id, String type, String number, String status, String kind, String state, long daysLate, UUID clientId,
                          String clientName, String crecheSlug, String crecheName, String subject, LocalDate periodFrom, LocalDate periodTo,
                          LocalDate issuedOn, LocalDate dueOn, long totalHt, long totalTgc, long totalTtc, long paid, long credited,
                          long remaining, Instant sentAt, boolean remindersPaused, UUID correctsId, String correctsNumber) {
    }

    public record CreditableLine(UUID lineId, String label, long amount, long alreadyCredited, long creditable, int tgcRate) {
    }

    public record Detail(Summary summary, List<Line> lines, List<Totals.RateTotal> rates, String note, JsonNode client, JsonNode issuer,
                         List<Summary> credits, List<PaymentRepository.Allocation> allocations, List<OutboxService.View> messages,
                         List<CreditableLine> creditable, String pdfSha256, Instant validatedAt, String validatedBy) {
    }

    private final InvoiceRepository invoices;
    private final CatalogueRepository catalogue;
    private final PaymentRepository payments;
    private final ParcRepository parc;
    private final OutboxService outbox;
    private final InvoicePdf pdf;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    public InvoiceService(InvoiceRepository invoices, CatalogueRepository catalogue, PaymentRepository payments, ParcRepository parc,
                          OutboxService outbox, InvoicePdf pdf, AuditService audit, ObjectMapper json, Clock clock) {
        this.invoices = invoices;
        this.catalogue = catalogue;
        this.payments = payments;
        this.parc = parc;
        this.outbox = outbox;
        this.pdf = pdf;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    // --- Lecture

    @Transactional(readOnly = true)
    public List<Summary> list() {
        var settlements = invoices.settlements();
        var all = invoices.all();
        var numbers = new LinkedHashMap<UUID, String>();
        all.forEach(i -> numbers.put(i.id(), i.number()));
        var names = parc.clientNames();
        var creches = new LinkedHashMap<UUID, ParcRepository.Creche>();
        parc.all().forEach(c -> creches.put(c.id(), c));
        var today = LocalDate.now(clock);
        return all.stream().map(i -> summary(i, settlements.getOrDefault(i.id(), new Settlement(0, 0)), names.get(i.clientId()),
                i.crecheId() == null ? null : creches.get(i.crecheId()), numbers.get(i.correctsId()), today)).toList();
    }

    @Transactional(readOnly = true)
    public Detail detail(UUID id) {
        var invoice = invoices.find(id).orElseThrow(InvoiceService::unknown);
        return detail(invoice);
    }

    private Detail detail(Invoice invoice) {
        var today = LocalDate.now(clock);
        var settlement = invoice.credit() || invoice.draft() ? new Settlement(0, 0) : invoices.settlement(invoice.id());
        var corrected = invoice.correctsId() == null ? null : invoices.find(invoice.correctsId()).orElse(null);
        var summary = summary(invoice, settlement, parc.client(invoice.clientId()).name(),
                invoice.crecheId() == null ? null : parc.findById(invoice.crecheId()).orElse(null),
                corrected == null ? null : corrected.number(), today);
        var lines = invoices.lines(invoice.id());
        var credits = invoices.creditsOf(invoice.id()).stream().map(c -> summary(c, new Settlement(0, 0), summary.clientName(), null,
                invoice.number(), today)).toList();
        var creditable = new ArrayList<CreditableLine>();
        if (!invoice.credit() && !invoice.draft()) {
            var done = invoices.creditedByLine(invoice.id());
            for (var l : lines) {
                long already = done.getOrDefault(l.id(), 0L);
                creditable.add(new CreditableLine(l.id(), l.label(), l.amount(), already, Math.max(0, l.amount() - already), l.tgcRate()));
            }
        }
        var totals = Totals.of(lines.stream().map(l -> new Totals.Line(l.amount(), l.tgcRate())).toList());
        return new Detail(summary, lines, totals.rates(), invoice.note(),
                invoice.clientSnapshot() != null ? invoice.clientSnapshot() : clientSnapshot(invoice),
                invoice.issuerSnapshot() != null ? invoice.issuerSnapshot() : json.valueToTree(catalogue.issuer()),
                credits, invoice.credit() ? List.of() : payments.allocationsOf(invoice.id()), outbox.forInvoice(invoice.id()), creditable,
                invoice.pdfSha256(), invoice.validatedAt(), invoice.validatedBy());
    }

    static Summary summary(Invoice i, Settlement s, String clientName, ParcRepository.Creche creche, String correctsNumber, LocalDate today) {
        long remaining = i.draft() || i.credit() ? 0 : i.totalTtc() - s.paid() - s.credited();
        String state;
        long late = 0;
        if (i.draft()) {
            state = "BROUILLON";
        } else if (i.credit()) {
            state = "AVOIR";
        } else if (s.credited() >= i.totalTtc()) {
            state = "ANNULEE";
        } else if (remaining <= 0) {
            state = "PAYEE";
        } else if (i.dueOn() != null && today.isAfter(i.dueOn())) {
            state = "RETARD";
            late = ChronoUnit.DAYS.between(i.dueOn(), today);
        } else if (i.sentAt() == null) {
            state = "A_ENVOYER";
        } else {
            state = s.paid() > 0 ? "PARTIELLE" : "ENVOYEE";
        }
        return new Summary(i.id(), i.type(), i.number(), i.status(), i.kind(), state, late, i.clientId(), clientName,
                creche == null ? null : creche.slug(), creche == null ? null : creche.name(), i.subject(), i.periodFrom(), i.periodTo(),
                i.issuedOn(), i.dueOn(), i.totalHt(), i.totalTgc(), i.totalTtc(), s.paid(), s.credited(), remaining, i.sentAt(),
                i.remindersPaused(), i.correctsId(), correctsNumber);
    }

    // --- Brouillons

    @Transactional
    public Detail createDraft(DraftInput input) {
        var creche = parc.find(input.crecheSlug()).orElseThrow(() -> ApiException.rule("creche_inconnue", "Crèche inconnue."));
        var id = UUID.randomUUID();
        invoices.insert(new Invoice(id, "FACTURE", null, "BROUILLON", "PRESTATION", creche.clientId(), creche.id(), null, null,
                input.subject().strip(), input.periodFrom(), input.periodTo(), null, null, 0, 0, 0, null, null, null, blank(input.note()), null,
                null, false, null, null, null), clock.instant());
        writeLines(id, input.lines());
        audit.record("FACTURE_BROUILLON", "creche:" + creche.slug(), Map.of("objet", input.subject().strip()));
        return detail(id);
    }

    @Transactional
    public Detail updateDraft(UUID id, DraftInput input) {
        var invoice = draft(id);
        if (invoice.credit()) {
            throw ApiException.rule("avoir", "Un avoir ne se modifie pas.");
        }
        invoices.updateDraft(id, input.subject().strip(), input.periodFrom(), input.periodTo(), blank(input.note()), clock.instant());
        writeLines(id, input.lines());
        return detail(id);
    }

    @Transactional
    public void deleteDraft(UUID id) {
        var invoice = draft(id);
        invoices.delete(id);
        audit.record("FACTURE_BROUILLON_SUPPRIME", invoice.crecheId() == null ? null
                : "creche:" + parc.findById(invoice.crecheId()).map(ParcRepository.Creche::slug).orElse("?"), Map.of("objet", invoice.subject()));
    }

    /** Lignes d'un brouillon, avec le taux de TGC du jour (recalculé à la validation, au jour d'émission). */
    void writeLines(UUID invoiceId, List<LineInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw ApiException.rule("lignes", "Une facture a au moins une ligne.");
        }
        var today = LocalDate.now(clock);
        var lines = new ArrayList<Line>();
        for (var in : inputs) {
            if (!NATURES.contains(in.tgcNature())) {
                throw ApiException.rule("nature", "Nature de TGC inconnue : " + in.tgcNature());
            }
            lines.add(new Line(null, 0, in.label().strip(), blank(in.detail()), in.quantity(), in.unitPrice(),
                    Math.multiplyExact(in.unitPrice(), (long) in.quantity()), in.tgcNature(), rate(in.tgcNature(), today), null));
        }
        invoices.replaceLines(invoiceId, lines);
        refreshTotals(invoiceId);
    }

    void refreshTotals(UUID invoiceId) {
        var lines = invoices.lines(invoiceId);
        var totals = Totals.of(lines.stream().map(l -> new Totals.Line(l.amount(), l.tgcRate())).toList());
        invoices.totals(invoiceId, totals, json.valueToTree(totals.rates()));
    }

    // --- Validation

    /** Validation : le brouillon reçoit son numéro, ses mentions et son PDF, puis ne change plus. */
    @Transactional
    public Detail validate(UUID id) {
        var invoice = invoices.lock(id).orElseThrow(InvoiceService::unknown);
        if (!invoice.draft()) {
            throw ApiException.rule("deja_validee", "Cette facture est déjà validée.");
        }
        var issuer = catalogue.issuer();
        var missing = CatalogueService.missingMentions(issuer);
        if (!missing.isEmpty()) {
            throw ApiException.rule("mentions", "Mentions de l’éditeur à compléter avant de valider (Paramètres › Éditeur et TGC) : "
                    + String.join(", ", missing) + ".");
        }
        var today = LocalDate.now(clock);
        // Taux du jour d'émission : la TGC est celle en vigueur quand la facture est émise.
        var lines = invoices.lines(id).stream().map(l -> new Line(null, l.position(), l.label(), l.detail(), l.quantity(), l.unitPrice(),
                l.amount(), l.tgcNature(), rate(l.tgcNature(), today), l.creditsLineId())).toList();
        if (lines.isEmpty()) {
            throw ApiException.rule("lignes", "Une facture a au moins une ligne.");
        }
        invoices.replaceLines(id, lines);
        refreshTotals(id);
        invoice = invoices.find(id).orElseThrow();
        if (invoice.totalTtc() <= 0) {
            throw ApiException.rule("montant", "Le total de la facture doit être positif ; pour rendre de l’argent, émettez un avoir.");
        }
        var client = parc.client(invoice.clientId());
        var number = number("F", today);
        var due = today.plusDays(client.paymentDays());
        return seal(invoice, number, today, due, client, null);
    }

    /** Avoir sur une facture validée, partiel ou total, validé tout de suite (numéro AV, PDF, e-mail préparé). */
    @Transactional
    public Detail credit(UUID invoiceId, CreditInput input) {
        var invoice = invoices.lock(invoiceId).orElseThrow(InvoiceService::unknown);
        if (invoice.draft() || invoice.credit()) {
            throw ApiException.rule("avoir_impossible", "Un avoir corrige une facture validée.");
        }
        if (input.reason() == null || input.reason().isBlank()) {
            throw ApiException.rule("motif", "Indiquez le motif de l’avoir.");
        }
        var originals = new LinkedHashMap<UUID, Line>();
        invoices.lines(invoiceId).forEach(l -> originals.put(l.id(), l));
        var done = invoices.creditedByLine(invoiceId);
        var lines = new ArrayList<Line>();
        for (var c : input.lines()) {
            if (c.amount() == 0) {
                continue;
            }
            var original = originals.get(c.lineId());
            if (original == null) {
                throw ApiException.rule("ligne", "Ligne inconnue sur cette facture.");
            }
            long creditable = original.amount() - done.getOrDefault(original.id(), 0L);
            if (c.amount() < 0 || c.amount() > creditable) {
                throw ApiException.rule("montant_avoir", "« %s » : %s au plus peuvent encore être rendus.".formatted(original.label(),
                        Xpf.format(Math.max(creditable, 0))));
            }
            lines.add(new Line(null, 0, original.label(), original.detail(), 1, c.amount(), c.amount(), original.tgcNature(),
                    original.tgcRate(), original.id()));
        }
        if (lines.isEmpty()) {
            throw ApiException.rule("lignes", "Indiquez au moins un montant à rendre.");
        }
        var today = LocalDate.now(clock);
        var id = UUID.randomUUID();
        invoices.insert(new Invoice(id, "AVOIR", null, "BROUILLON", "AVOIR", invoice.clientId(), invoice.crecheId(), invoice.contractId(),
                invoice.id(), "Avoir sur la facture " + invoice.number(), invoice.periodFrom(), invoice.periodTo(), null, null, 0, 0, 0, null,
                null, null, input.reason().strip(), null, null, false, null, null, null), clock.instant());
        invoices.replaceLines(id, lines);
        refreshTotals(id);
        var credit = invoices.find(id).orElseThrow();
        return seal(credit, number("AV", today), today, null, parc.client(invoice.clientId()), invoice);
    }

    private Detail seal(Invoice invoice, String number, LocalDate issuedOn, LocalDate dueOn, ParcRepository.Client client, Invoice corrected) {
        var issuer = catalogue.issuer();
        JsonNode issuerSnapshot = json.valueToTree(issuer);
        var clientSnapshot = clientSnapshot(invoice);
        var lines = invoices.lines(invoice.id());
        var totals = Totals.of(lines.stream().map(l -> new Totals.Line(l.amount(), l.tgcRate())).toList());
        var bytes = pdf.render(new InvoicePdf.Document(invoice.credit() ? "Avoir" : "Facture", number, invoice.credit(),
                Dates.format(issuedOn), Dates.format(dueOn), invoice.subject(),
                invoice.periodFrom() == null || invoice.periodTo() == null ? null : Dates.period(invoice.periodFrom(), invoice.periodTo()),
                corrected == null ? null : "%s du %s".formatted(corrected.number(), Dates.format(corrected.issuedOn())),
                issuerSnapshot, clientSnapshot, lines, totals, invoice.note()));
        var now = clock.instant();
        var operator = CurrentOperator.find().map(nc.ticagou.console.auth.CurrentOperator.Principal::login).orElse(null);
        invoices.validate(invoice.id(), number, issuedOn, dueOn, clientSnapshot, issuerSnapshot, bytes, InvoicePdf.sha256(bytes), operator, now);
        var sealed = invoices.find(invoice.id()).orElseThrow();
        outbox.prepare(sealed, client, issuer, corrected);
        audit.record(invoice.credit() ? "AVOIR_EMIS" : "FACTURE_VALIDEE", "facture:" + number,
                corrected == null ? Map.of("ttc", sealed.totalTtc(), "objet", sealed.subject())
                        : Map.of("ttc", sealed.totalTtc(), "facture", corrected.number(), "motif", sealed.note()));
        return detail(sealed);
    }

    /** F2026-0047 : série, exercice (année civile d'émission), rang sur 4 chiffres. */
    private String number(String series, LocalDate issuedOn) {
        return "%s%d-%04d".formatted(series, issuedOn.getYear(), invoices.next(series, issuedOn.getYear()));
    }

    private JsonNode clientSnapshot(Invoice invoice) {
        var client = parc.client(invoice.clientId());
        var creche = invoice.crecheId() == null ? null : parc.findById(invoice.crecheId()).orElse(null);
        var node = json.createObjectNode();
        node.put("name", client.name());
        node.put("ridet", client.ridet());
        node.put("address", client.address());
        node.put("crecheName", creche == null ? null : creche.name());
        node.put("crecheSlug", creche == null ? null : creche.slug());
        return node;
    }

    // --- Divers

    @Transactional
    public Detail pauseReminders(UUID id, boolean paused) {
        var invoice = invoices.lock(id).orElseThrow(InvoiceService::unknown);
        if (invoice.draft() || invoice.credit()) {
            throw ApiException.rule("relances", "Seule une facture validée a des relances.");
        }
        invoices.pauseReminders(id, paused, clock.instant());
        audit.record(paused ? "RELANCES_SUSPENDUES" : "RELANCES_REPRISES", "facture:" + invoice.number(), Map.of());
        return detail(id);
    }

    public record Pdf(String name, byte[] bytes) {
    }

    public Pdf pdf(UUID id) {
        var invoice = invoices.find(id).orElseThrow(InvoiceService::unknown);
        if (invoice.draft()) {
            throw ApiException.rule("brouillon", "Un brouillon n’a pas encore de PDF : il est produit à la validation.");
        }
        return new Pdf(invoice.number() + ".pdf", invoices.pdf(id));
    }

    int rate(String nature, LocalDate day) {
        return catalogue.rateAt(nature, day).orElseThrow(() -> ApiException.rule("tgc_absente",
                "Aucun taux de TGC « %s » au %s (Paramètres › Éditeur et TGC).".formatted(nature.toLowerCase(), Dates.format(day))));
    }

    private Invoice draft(UUID id) {
        var invoice = invoices.lock(id).orElseThrow(InvoiceService::unknown);
        if (!invoice.draft()) {
            throw ApiException.rule("facture_validee", "Facture validée : elle ne se modifie plus, on la corrige par un avoir.");
        }
        return invoice;
    }

    static ApiException unknown() {
        return ApiException.notFound("facture_inconnue", "Facture introuvable.");
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
