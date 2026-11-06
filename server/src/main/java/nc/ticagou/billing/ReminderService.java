package nc.ticagou.billing;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.billing.PaymentDtos.MarkReminderSentRequest;
import nc.ticagou.billing.PaymentDtos.ReminderDto;
import nc.ticagou.common.ApiException;
import nc.ticagou.common.Formats;

/** Relances des factures échues : trois niveaux, lettre imprimée, envoi noté à la main. */
@Service
public class ReminderService {

    static final List<String> LEVELS = List.of("Rappel de paiement", "Deuxième relance", "Dernière relance");

    private final ReminderRepository reminders;
    private final InvoiceService invoices;
    private final InvoiceEventRepository events;
    private final InvoiceBalances balances;
    private final AuditService audit;
    private final Clock clock;

    public ReminderService(ReminderRepository reminders, InvoiceService invoices, InvoiceEventRepository events,
                           InvoiceBalances balances, AuditService audit, Clock clock) {
        this.reminders = reminders;
        this.invoices = invoices;
        this.events = events;
        this.balances = balances;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ReminderDto> list(UUID invoiceId) {
        var invoice = invoices.load(invoiceId);
        return reminders.findByInvoiceIdOrderByLevelAsc(invoiceId).stream().map(r -> toDto(r, invoice)).toList();
    }

    /** Relance suivante (niveau 1, puis 2, puis 3) d'une facture échue et pas entièrement réglée. */
    @Transactional
    public ReminderDto create(UUID invoiceId) {
        var invoice = invoices.load(invoiceId);
        var today = LocalDate.now(clock);
        var balance = balances.of(invoiceId);
        if (balance.remaining(invoice.getFamilyTotal()) == 0) {
            throw ApiException.rule("reminder_paid", "Cette facture est réglée : rien à relancer.");
        }
        if (!invoice.getDueOn().isBefore(today)) {
            throw ApiException.rule("reminder_not_due",
                    "La facture arrive à échéance le %s : elle ne peut pas encore être relancée."
                            .formatted(Formats.date(invoice.getDueOn())));
        }
        int level = reminders.findByInvoiceIdOrderByLevelAsc(invoiceId).stream().mapToInt(Reminder::getLevel)
                .max().orElse(0) + 1;
        if (level > LEVELS.size()) {
            throw ApiException.rule("reminder_max",
                    "Les trois relances ont été faites : voyez la famille avec la direction.");
        }
        var user = CurrentUser.get();
        var reminder = reminders.save(new Reminder(invoice.getStructureId(), invoiceId, level, today, user.displayName()));
        events.save(new InvoiceEvent(invoice, InvoiceEvent.Kind.RELANCE, "%s préparée (reste dû %s)".formatted(
                LEVELS.get(level - 1), Formats.xpf(balance.remaining(invoice.getFamilyTotal()))), user.displayName(),
                Instant.now(clock)));
        audit.record(AuditAction.REMINDER_CREATED, "reminder", reminder.getId(),
                Map.of("invoice", invoice.getNumber(), "level", level));
        return toDto(reminder, invoice);
    }

    @Transactional
    public ReminderDto markSent(UUID id, MarkReminderSentRequest request) {
        var reminder = load(id);
        var invoice = invoices.load(reminder.getInvoiceId());
        if (request.sentOn().isAfter(LocalDate.now(clock)) || request.sentOn().isBefore(reminder.getCreatedOn())) {
            throw ApiException.rule("reminder_sent_on",
                    "La date d’envoi doit être comprise entre la préparation de la relance et aujourd’hui.");
        }
        reminder.markSent(request.sentOn());
        reminders.saveAndFlush(reminder);
        var user = CurrentUser.get();
        events.save(new InvoiceEvent(invoice, InvoiceEvent.Kind.RELANCE_ENVOYEE, "%s notée envoyée le %s".formatted(
                LEVELS.get(reminder.getLevel() - 1), Formats.date(request.sentOn())), user.displayName(),
                Instant.now(clock)));
        audit.record(AuditAction.REMINDER_SENT, "reminder", id,
                Map.of("invoice", invoice.getNumber(), "level", reminder.getLevel()));
        return toDto(reminder, invoice);
    }

    Reminder load(UUID id) {
        return reminders.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Relance introuvable."));
    }

    private static ReminderDto toDto(Reminder r, Invoice invoice) {
        return new ReminderDto(r.getId(), r.getInvoiceId(), invoice.getNumber(), r.getLevel(), r.getCreatedOn(),
                r.getSentOn(), r.getAuthor());
    }
}
