package nc.ticagou.console.billing;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import nc.ticagou.console.audit.AuditService;
import nc.ticagou.console.auth.CurrentOperator;
import nc.ticagou.console.billing.CatalogueRepository.Issuer;
import nc.ticagou.console.billing.InvoiceRepository.Invoice;
import nc.ticagou.console.billing.OutboxRepository.Message;
import nc.ticagou.console.common.ApiException;
import nc.ticagou.console.parc.ParcRepository;

/**
 * Boîte d'envoi : les messages aux clients sont préparés (facture validée, avoir, plus tard relances) et ne partent
 * que sur un clic de l'éditeur, un par un ou par sélection. Un envoi refusé par le serveur de messagerie laisse le
 * message en attente avec l'erreur.
 */
@Service
public class OutboxService {

    public record View(UUID id, String kind, UUID invoiceId, String invoiceNumber, long invoiceTotal, String clientName, String crecheSlug,
                       String crecheName, String to, String cc, String subject, String body, String attachmentName, String status,
                       Instant createdAt, Instant sentAt, String sentBy, String lastError, Instant discardedAt, String discardReason) {
    }

    public record Result(UUID id, boolean sent, String error) {
    }

    private final OutboxRepository repository;
    private final InvoiceRepository invoices;
    private final ParcRepository parc;
    private final Mailer mailer;
    private final AuditService audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public OutboxService(OutboxRepository repository, InvoiceRepository invoices, ParcRepository parc, Mailer mailer, AuditService audit,
                         TransactionTemplate transactions, Clock clock) {
        this.repository = repository;
        this.invoices = invoices;
        this.parc = parc;
        this.mailer = mailer;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
    }

    // --- Préparation

    /** Message d'une facture ou d'un avoir qui vient d'être validé, adressé au contact de facturation. */
    public void prepare(Invoice invoice, ParcRepository.Client client, Issuer issuer, Invoice corrected) {
        var to = firstNonBlank(client.billingEmail(), client.directorEmail());
        var hello = client.directorName() == null || client.directorName().isBlank() ? "Bonjour," : "Bonjour " + client.directorName() + ",";
        var signature = issuer.name() == null ? "Ti'Cagou" : issuer.name();
        String subject;
        String text;
        if (invoice.credit()) {
            subject = "Avoir %s sur la facture %s".formatted(invoice.number(), corrected.number());
            text = """
                    %s

                    Veuillez trouver ci-joint l'avoir %s du %s sur la facture %s, d'un montant de %s TTC.
                    %s
                    Bien cordialement,
                    %s
                    """.formatted(hello, invoice.number(), Dates.format(invoice.issuedOn()), corrected.number(),
                    Xpf.format(invoice.totalTtc()), invoice.note() == null ? "" : "\nMotif : " + invoice.note() + "\n", signature);
        } else {
            subject = "Votre facture %s".formatted(invoice.number());
            var bank = issuer.iban() == null || issuer.iban().isBlank() ? ""
                    : "\nRèglement par virement : %s IBAN %s, référence %s.\n".formatted(
                            issuer.bank() == null ? "" : issuer.bank() + ",", issuer.iban(), invoice.number());
            text = """
                    %s

                    Veuillez trouver ci-joint la facture %s du %s (%s), d'un montant de %s TTC, à régler avant le %s.
                    %s
                    Bien cordialement,
                    %s
                    """.formatted(hello, invoice.number(), Dates.format(invoice.issuedOn()), invoice.subject(),
                    Xpf.format(invoice.totalTtc()), Dates.format(invoice.dueOn()), bank, signature);
        }
        var now = clock.instant();
        repository.insert(new Message(UUID.randomUUID(), invoice.credit() ? "AVOIR" : "FACTURE", invoice.id(), invoice.clientId(),
                invoice.crecheId(), to, blankToNull(issuer.copyEmail()), subject, text.replace("\n\n\n", "\n\n"), "PREPARE", now, now,
                null, null, null, null, null, null));
    }

    // --- Lecture

    public List<View> list(String status) {
        return repository.list(status, 300).stream().map(this::view).toList();
    }

    public int pending() {
        return repository.pending();
    }

    public List<View> forInvoice(UUID invoiceId) {
        return repository.forInvoice(invoiceId).stream().map(this::view).toList();
    }

    private View view(Message m) {
        var invoice = m.invoiceId() == null ? null : invoices.find(m.invoiceId()).orElse(null);
        var client = parc.client(m.clientId());
        var creche = m.crecheId() == null ? null : parc.findById(m.crecheId()).orElse(null);
        return new View(m.id(), m.kind(), m.invoiceId(), invoice == null ? null : invoice.number(), invoice == null ? 0 : invoice.totalTtc(),
                client.name(), creche == null ? null : creche.slug(), creche == null ? null : creche.name(), m.to(), m.cc(), m.subject(),
                m.body(), invoice == null ? null : invoice.number() + ".pdf", m.status(), m.createdAt(), m.sentAt(), m.sentBy(),
                m.lastError(), m.discardedAt(), m.discardReason());
    }

    // --- Actions

    public View edit(UUID id, String to, String cc, String subject, String body) {
        return transactions.execute(s -> {
            var message = pendingMessage(id);
            repository.edit(id, to, blankToNull(cc), subject, body, clock.instant());
            audit.record("ENVOI_MODIFIE", target(message), Map.of("objet", subject));
            return view(repository.lock(id).orElseThrow());
        });
    }

    /** Envoie chaque message de la sélection ; ceux qui échouent restent en attente avec leur erreur. */
    public List<Result> send(List<UUID> ids) {
        var operator = CurrentOperator.get().login();
        var results = new ArrayList<Result>();
        for (var id : ids) {
            results.add(transactions.execute(s -> sendOne(id, operator)));
        }
        return results;
    }

    private Result sendOne(UUID id, String operator) {
        var message = repository.lock(id).orElseThrow(() -> ApiException.notFound("message_inconnu", "Message introuvable."));
        if (!"PREPARE".equals(message.status())) {
            return new Result(id, false, "Ce message n’est plus en attente.");
        }
        var now = clock.instant();
        byte[] pdf = null;
        String name = null;
        if (message.invoiceId() != null) {
            var invoice = invoices.find(message.invoiceId()).orElse(null);
            if (invoice == null || invoice.draft()) {
                repository.failed(id, "La facture n’est pas validée.", now);
                return new Result(id, false, "La facture n’est pas validée.");
            }
            pdf = invoices.pdf(invoice.id());
            name = invoice.number() + ".pdf";
        }
        try {
            mailer.send(message.to(), message.cc(), message.subject(), message.body(), name, pdf);
        } catch (Mailer.MailFailure e) {
            repository.failed(id, e.getMessage(), now);
            audit.record("ENVOI_ECHEC", target(message), Map.of("erreur", e.getMessage()));
            return new Result(id, false, e.getMessage());
        }
        repository.sent(id, operator, now);
        if (message.invoiceId() != null) {
            invoices.markSent(message.invoiceId(), now);
        }
        audit.record("ENVOI", target(message), Map.of("a", message.to(), "objet", message.subject()));
        return new Result(id, true, null);
    }

    public void discard(List<UUID> ids, String reason) {
        var operator = CurrentOperator.get().login();
        transactions.executeWithoutResult(s -> {
            for (var id : ids) {
                var message = pendingMessage(id);
                repository.discard(id, operator, blankToNull(reason), clock.instant());
                audit.record("ENVOI_ECARTE", target(message), reason == null || reason.isBlank() ? Map.of() : Map.of("motif", reason));
            }
        });
    }

    private Message pendingMessage(UUID id) {
        var message = repository.lock(id).orElseThrow(() -> ApiException.notFound("message_inconnu", "Message introuvable."));
        if (!"PREPARE".equals(message.status())) {
            throw ApiException.rule("message_traite", "Ce message a déjà été envoyé ou écarté.");
        }
        return message;
    }

    private String target(Message m) {
        if (m.invoiceId() != null) {
            var number = invoices.find(m.invoiceId()).map(Invoice::number).orElse(null);
            if (number != null) {
                return "facture:" + number;
            }
        }
        return "envoi:" + m.id();
    }

    private static String firstNonBlank(String... values) {
        for (var v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
