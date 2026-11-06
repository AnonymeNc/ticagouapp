package nc.ticagou.billing;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.portal.PortalChange;
import nc.ticagou.portal.PortalItemType;
import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.billing.BillingDtos.InvoiceStatus;
import nc.ticagou.billing.BillingDtos.InvoiceSummary;
import nc.ticagou.billing.Payment.Method;
import nc.ticagou.billing.PaymentDtos.AllocationLine;
import nc.ticagou.billing.PaymentDtos.CancelPaymentRequest;
import nc.ticagou.billing.PaymentDtos.CollectionsOverview;
import nc.ticagou.billing.PaymentDtos.PaymentDto;
import nc.ticagou.billing.PaymentDtos.PaymentRequest;
import nc.ticagou.common.ApiException;
import nc.ticagou.common.CounterService;
import nc.ticagou.common.Formats;
import nc.ticagou.family.FamilyRepository;

/**
 * Encaissements et lettrage. La somme lettrée ne dépasse jamais le paiement ni le reste dû : un paiement plus
 * élevé que ce que la famille doit est refusé. Les espèces reçoivent un reçu numéroté (R-AAAA-NNNN).
 */
@Service
public class PaymentService {

    static final String RECEIPT_SERIES = "receipt";

    private final PaymentRepository payments;
    private final PaymentAllocationRepository allocations;
    private final InvoiceRepository invoices;
    private final InvoiceEventRepository events;
    private final InvoiceService invoiceService;
    private final FunderRequestService funderRequests;
    private final FunderRepository funders;
    private final FamilyRepository families;
    private final InvoiceBalances balances;
    private final CounterService counters;
    private final AuditService audit;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, PaymentAllocationRepository allocations,
                          InvoiceRepository invoices, InvoiceEventRepository events, InvoiceService invoiceService,
                          FunderRequestService funderRequests, FunderRepository funders, FamilyRepository families,
                          InvoiceBalances balances, CounterService counters, AuditService audit, ApplicationEventPublisher publisher, Clock clock) {
        this.payments = payments;
        this.allocations = allocations;
        this.invoices = invoices;
        this.events = events;
        this.invoiceService = invoiceService;
        this.funderRequests = funderRequests;
        this.funders = funders;
        this.families = families;
        this.balances = balances;
        this.counters = counters;
        this.audit = audit;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Transactional
    public PaymentDto record(PaymentRequest request) {
        var user = CurrentUser.get();
        var structureId = user.structureId();
        var today = LocalDate.now(clock);
        if (request.paidOn().isAfter(today)) {
            throw ApiException.rule("payment_future", "La date du paiement ne peut pas être dans le futur.");
        }
        var now = Instant.now(clock);
        var reference = blankToNull(request.reference());
        var bank = request.method() == Method.CHEQUE ? blankToNull(request.bank()) : null;
        Payment payment;
        var plan = new LinkedHashMap<UUID, Long>();
        if (request.funderRequestId() != null) {
            var funderRequest = funderRequests.load(request.funderRequestId());
            long received = balances.received(List.of(funderRequest.getId())).getOrDefault(funderRequest.getId(), 0L);
            long remaining = Math.max(funderRequest.getTotal() - received, 0);
            if (request.amount() > remaining) {
                throw tooMuch(request.amount(), remaining);
            }
            var funder = funders.findById(funderRequest.getFunderId()).orElseThrow();
            payment = payments.save(new Payment(structureId, null, funder.getId(), funder.getName(),
                    request.paidOn(), request.amount(), request.method(), reference, bank,
                    receipt(request, structureId, today), user.displayName(), now));
            allocations.save(new PaymentAllocation(payment, null, funderRequest.getId(), request.amount()));
        } else {
            var target = request.invoiceId() == null ? null : invoiceService.load(request.invoiceId());
            var familyId = target != null ? target.getFamilyId() : request.familyId();
            var family = families.findByIdAndStructureId(familyId, structureId)
                    .orElseThrow(() -> ApiException.notFound("Famille introuvable."));
            var open = openInvoices(familyId);
            if (target != null) {
                // La facture choisie d'abord, puis les autres impayés du plus ancien au plus récent.
                open.sort(Comparator.comparing((Map.Entry<Invoice, Long> e) -> !e.getKey().getId().equals(target.getId()))
                        .thenComparing(e -> e.getKey().getDueOn()));
            }
            long left = request.amount();
            for (var entry : open) {
                if (left == 0) {
                    break;
                }
                long part = Math.min(left, entry.getValue());
                plan.put(entry.getKey().getId(), part);
                left -= part;
            }
            if (left > 0) {
                throw tooMuch(request.amount(), request.amount() - left);
            }
            var payer = target != null ? target.getPayerName()
                    : open.isEmpty() ? family.getLabel() : open.getFirst().getKey().getPayerName();
            payment = payments.save(new Payment(structureId, familyId, null, payer, request.paidOn(),
                    request.amount(), request.method(), reference, bank, receipt(request, structureId, today),
                    user.displayName(), now));
            var byId = invoices.findAllById(plan.keySet()).stream()
                    .collect(Collectors.toMap(Invoice::getId, Function.identity()));
            for (var entry : plan.entrySet()) {
                allocations.save(new PaymentAllocation(payment, entry.getKey(), null, entry.getValue()));
                events.save(new InvoiceEvent(byId.get(entry.getKey()), InvoiceEvent.Kind.PAIEMENT,
                        "Paiement de %s par %s le %s".formatted(Formats.xpf(entry.getValue()),
                                methodLabel(payment), Formats.date(payment.getPaidOn())), user.displayName(), now));
            }
        }
        var details = new LinkedHashMap<String, Object>();
        details.put("payer", payment.getPayerName());
        details.put("amount", payment.getAmount());
        details.put("method", payment.getMethod().name());
        details.put("paidOn", payment.getPaidOn().toString());
        if (payment.getReceiptNumber() != null) {
            details.put("receipt", payment.getReceiptNumber());
        }
        audit.record(AuditAction.PAYMENT_RECORDED, "payment", payment.getId(), details);
        plan.keySet().forEach(invoiceId -> publisher.publishEvent(new PortalChange(structureId, PortalItemType.INVOICE, invoiceId)));
        return toDtos(List.of(payment)).getFirst();
    }

    @Transactional
    public PaymentDto cancel(UUID id, CancelPaymentRequest request) {
        var payment = load(id);
        if (payment.isCancelled()) {
            throw ApiException.conflict("payment_cancelled", "Ce paiement est déjà annulé.");
        }
        var user = CurrentUser.get();
        var now = Instant.now(clock);
        payment.cancel(now, user.displayName(), request.reason().trim());
        payments.saveAndFlush(payment);
        var linked = allocations.findByPaymentIdIn(List.of(id)).stream()
                .filter(a -> a.getInvoiceId() != null).toList();
        var byId = invoices.findAllById(linked.stream().map(PaymentAllocation::getInvoiceId).toList()).stream()
                .collect(Collectors.toMap(Invoice::getId, Function.identity()));
        for (var a : linked) {
            events.save(new InvoiceEvent(byId.get(a.getInvoiceId()), InvoiceEvent.Kind.PAIEMENT_ANNULE,
                    "Paiement de %s du %s annulé : %s".formatted(Formats.xpf(a.getAmount()),
                            Formats.date(payment.getPaidOn()), payment.getCancelReason()), user.displayName(), now));
        }
        linked.forEach(a -> publisher.publishEvent(new PortalChange(payment.getStructureId(), PortalItemType.INVOICE, a.getInvoiceId())));
        audit.record(AuditAction.PAYMENT_CANCELLED, "payment", id, Map.of("payer", payment.getPayerName(),
                "amount", payment.getAmount(), "reason", payment.getCancelReason()));
        return toDtos(List.of(payment)).getFirst();
    }

    @Transactional(readOnly = true)
    public PaymentDto get(UUID id) {
        return toDtos(List.of(load(id))).getFirst();
    }

    /** Encaissé du mois, à venir, impayés échus, liste des impayés et derniers paiements. */
    @Transactional(readOnly = true)
    public CollectionsOverview collections() {
        var structureId = CurrentUser.get().structureId();
        var today = LocalDate.now(clock);
        var month = YearMonth.from(today);
        var collected = payments.findByStructureIdAndPaidOnBetween(structureId, month.atDay(1), month.atEndOfMonth())
                .stream().filter(p -> !p.isCancelled()).toList();
        var all = invoiceService.summaries(invoices.findByStructureIdOrderByIssuedOnDescNumberDesc(structureId));
        var unpaid = all.stream().filter(s -> s.remaining() > 0)
                .sorted(Comparator.comparing(InvoiceSummary::dueOn).thenComparing(InvoiceSummary::number)).toList();
        var overdue = unpaid.stream().filter(s -> s.status() == InvoiceStatus.EN_RETARD).toList();
        var upcoming = unpaid.stream().filter(s -> s.status() != InvoiceStatus.EN_RETARD).toList();
        return new CollectionsOverview(collected.stream().mapToLong(Payment::getAmount).sum(), collected.size(),
                upcoming.stream().mapToLong(InvoiceSummary::remaining).sum(), upcoming.size(),
                upcoming.stream().map(InvoiceSummary::dueOn).min(Comparator.naturalOrder()).orElse(null),
                overdue.stream().mapToLong(InvoiceSummary::remaining).sum(), overdue.size(),
                (int) overdue.stream().filter(s -> s.daysLate() > 30).count(), unpaid,
                toDtos(payments.findTop50ByStructureIdOrderByPaidOnDescCreatedAtDesc(structureId).stream()
                        .limit(20).toList()));
    }

    /** Impayés de la famille avec leur reste dû, du plus ancien au plus récent. */
    private List<Map.Entry<Invoice, Long>> openInvoices(UUID familyId) {
        var found = invoices.findByFamilyIdOrderByMonthDescNumberDesc(familyId);
        var byId = balances.of(found.stream().map(Invoice::getId).toList());
        var result = new ArrayList<Map.Entry<Invoice, Long>>();
        found.stream().sorted(Comparator.comparing(Invoice::getDueOn).thenComparing(Invoice::getNumber))
                .forEach(i -> {
                    var b = byId.get(i.getId());
                    long remaining = b == null ? i.getFamilyTotal() : b.remaining(i.getFamilyTotal());
                    if (remaining > 0) {
                        result.add(Map.entry(i, remaining));
                    }
                });
        return result;
    }

    private String receipt(PaymentRequest request, UUID structureId, LocalDate today) {
        if (request.method() != Method.ESPECES) {
            return null;
        }
        return "R-%d-%04d".formatted(today.getYear(), counters.next(structureId, RECEIPT_SERIES, today.getYear()));
    }

    List<PaymentDto> toDtos(List<Payment> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        var byPayment = allocations.findByPaymentIdIn(found.stream().map(Payment::getId).toList()).stream()
                .collect(Collectors.groupingBy(PaymentAllocation::getPaymentId));
        var all = byPayment.values().stream().flatMap(List::stream).toList();
        var invoiceNumbers = invoices.findAllById(all.stream().map(PaymentAllocation::getInvoiceId)
                        .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Invoice::getId, Invoice::getNumber));
        var requestNumbers = new LinkedHashMap<UUID, String>();
        all.stream().map(PaymentAllocation::getFunderRequestId).filter(Objects::nonNull).distinct()
                .forEach(id -> requestNumbers.put(id, funderRequests.load(id).getNumber()));
        return found.stream().map(p -> new PaymentDto(p.getId(), p.getPayerName(), p.getFamilyId(), p.getFunderId(),
                p.getPaidOn(), p.getAmount(), p.getMethod(), p.getReference(), p.getBank(), p.getReceiptNumber(),
                p.getRecordedBy(), p.isCancelled(), p.getCancelReason(),
                byPayment.getOrDefault(p.getId(), List.of()).stream()
                        .map(a -> new AllocationLine(a.getInvoiceId(), a.getFunderRequestId(),
                                a.getInvoiceId() != null ? invoiceNumbers.get(a.getInvoiceId())
                                        : requestNumbers.get(a.getFunderRequestId()), a.getAmount()))
                        .toList()))
                .toList();
    }

    static String methodLabel(Payment p) {
        return switch (p.getMethod()) {
            case VIREMENT -> "virement";
            case CHEQUE -> "chèque" + (p.getReference() == null ? "" : " n° " + p.getReference());
            case ESPECES -> "espèces" + (p.getReceiptNumber() == null ? "" : " (reçu " + p.getReceiptNumber() + ")");
            case CARTE -> "carte";
        };
    }

    Payment load(UUID id) {
        return payments.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Paiement introuvable."));
    }

    private static ApiException tooMuch(long amount, long remaining) {
        return ApiException.rule("payment_too_high",
                "Le paiement (%s) dépasse le reste dû (%s). Vérifiez le montant.".formatted(Formats.xpf(amount),
                        Formats.xpf(remaining)));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
