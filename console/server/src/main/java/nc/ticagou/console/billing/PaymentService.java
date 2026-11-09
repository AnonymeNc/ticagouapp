package nc.ticagou.console.billing;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.console.audit.AuditService;
import nc.ticagou.console.auth.CurrentOperator;
import nc.ticagou.console.billing.PaymentRepository.Payment;
import nc.ticagou.console.common.ApiException;
import nc.ticagou.console.parc.ParcRepository;

/**
 * Paiements reçus (virement, chèque, espèces), affectés à une ou plusieurs factures du client ; sans affectation
 * indiquée, aux plus anciennes d'abord. Ce qui reste est un trop-perçu, visible sur la fiche du client.
 */
@Service
public class PaymentService {

    public static final List<String> METHODS = List.of("VIREMENT", "CHEQUE", "ESPECES", "AUTRE");

    public record AllocationInput(UUID invoiceId, long amount) {
    }

    public record Input(String crecheSlug, LocalDate receivedOn, long amount, String method, String reference, String note,
                        List<AllocationInput> allocations) {
    }

    public record View(Payment payment, String clientName, List<PaymentRepository.Allocation> allocations) {
    }

    private final PaymentRepository payments;
    private final InvoiceRepository invoices;
    private final ParcRepository parc;
    private final AuditService audit;
    private final nc.ticagou.console.orders.OrderService orders;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, InvoiceRepository invoices, ParcRepository parc, AuditService audit,
                          @org.springframework.context.annotation.Lazy nc.ticagou.console.orders.OrderService orders, Clock clock) {
        this.orders = orders;
        this.payments = payments;
        this.invoices = invoices;
        this.parc = parc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<View> list() {
        var names = parc.clientNames();
        return payments.all().stream().map(p -> new View(p, names.get(p.clientId()), payments.allocationsFrom(p.id()))).toList();
    }

    @Transactional
    public View record(Input input) {
        if (!METHODS.contains(input.method())) {
            throw ApiException.rule("moyen", "Moyen de paiement inconnu.");
        }
        if (input.amount() <= 0) {
            throw ApiException.rule("montant", "Le montant doit être positif.");
        }
        if (input.receivedOn().isAfter(LocalDate.now(clock))) {
            throw ApiException.rule("date", "Un paiement ne peut pas être reçu dans le futur.");
        }
        var creche = parc.find(input.crecheSlug()).orElseThrow(() -> ApiException.rule("creche_inconnue", "Crèche inconnue."));
        var clientId = creche.clientId();
        var allocations = input.allocations() == null || input.allocations().isEmpty() ? automatic(clientId, input.amount())
                : input.allocations().stream().filter(a -> a.amount() != 0).toList();
        long total = 0;
        var seen = new HashSet<UUID>();
        for (var a : allocations) {
            if (!seen.add(a.invoiceId())) {
                throw ApiException.rule("affectation", "Une facture apparaît deux fois.");
            }
            var invoice = invoices.lock(a.invoiceId()).orElseThrow(InvoiceService::unknown);
            if (!invoice.clientId().equals(clientId) || invoice.draft() || invoice.credit()) {
                throw ApiException.rule("affectation", "Un paiement s’affecte à une facture validée du même client.");
            }
            var s = invoices.settlement(invoice.id());
            long remaining = invoice.totalTtc() - s.paid() - s.credited();
            if (a.amount() < 0 || a.amount() > remaining) {
                throw ApiException.rule("affectation", "Facture %s : %s restent dus.".formatted(invoice.number(), Xpf.format(Math.max(remaining, 0))));
            }
            total += a.amount();
        }
        if (total > input.amount()) {
            throw ApiException.rule("affectation", "Les affectations dépassent le montant reçu.");
        }
        var id = UUID.randomUUID();
        var operator = CurrentOperator.get().login();
        payments.insert(id, clientId, input.receivedOn(), input.amount(), input.method(), blank(input.reference()), blank(input.note()),
                operator, clock.instant());
        allocations.forEach(a -> payments.allocate(id, a.invoiceId(), a.amount()));
        audit.record("PAIEMENT", "creche:" + creche.slug(), Map.of("montant", input.amount(), "moyen", input.method(),
                "affecte", total, "tropPercu", input.amount() - total));
        // Abonnement payé : la prolongation de l'instance est proposée, à confirmer (D6).
        for (var a : allocations) {
            var invoice = invoices.find(a.invoiceId()).orElseThrow();
            var s = invoices.settlement(invoice.id());
            if ("ABONNEMENT".equals(invoice.kind()) && invoice.totalTtc() - s.paid() - s.credited() <= 0) {
                orders.proposeAfterPayment(creche.slug(), invoice.number());
            }
        }
        var payment = payments.find(id).orElseThrow();
        return new View(payment, parc.client(clientId).name(), payments.allocationsFrom(id));
    }

    /** Aux factures validées les plus anciennes du client, jusqu'à épuisement du montant. */
    private List<AllocationInput> automatic(UUID clientId, long amount) {
        var result = new ArrayList<AllocationInput>();
        long left = amount;
        var open = invoices.forClient(clientId).stream().filter(i -> !i.draft() && !i.credit())
                .sorted(Comparator.comparing(InvoiceRepository.Invoice::issuedOn).thenComparing(InvoiceRepository.Invoice::number)).toList();
        for (var invoice : open) {
            if (left <= 0) {
                break;
            }
            var s = invoices.settlement(invoice.id());
            long remaining = invoice.totalTtc() - s.paid() - s.credited();
            if (remaining > 0) {
                long part = Math.min(left, remaining);
                result.add(new AllocationInput(invoice.id(), part));
                left -= part;
            }
        }
        return result;
    }

    /** Saisie erronée : le paiement et ses affectations disparaissent ; le journal en garde la trace. */
    @Transactional
    public void delete(UUID id) {
        var payment = payments.find(id).orElseThrow(() -> ApiException.notFound("paiement_inconnu", "Paiement introuvable."));
        var allocations = payments.allocationsFrom(id);
        payments.delete(id);
        audit.record("PAIEMENT_SUPPRIME", "client:" + payment.clientId(), Map.of("montant", payment.amount(),
                "date", payment.receivedOn().toString(), "factures", allocations.stream().map(PaymentRepository.Allocation::invoiceNumber).toList()));
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
