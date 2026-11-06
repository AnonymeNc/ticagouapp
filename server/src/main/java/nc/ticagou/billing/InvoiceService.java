package nc.ticagou.billing;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.portal.PortalChange;
import nc.ticagou.portal.PortalItemType;
import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.billing.BillingDtos.AllocationDto;
import nc.ticagou.billing.BillingDtos.CreditNoteDto;
import nc.ticagou.billing.BillingDtos.CreditNoteLineDto;
import nc.ticagou.billing.BillingDtos.CreditNoteRequest;
import nc.ticagou.billing.BillingDtos.EventDto;
import nc.ticagou.billing.BillingDtos.InvoiceDto;
import nc.ticagou.billing.BillingDtos.InvoiceStatus;
import nc.ticagou.billing.BillingDtos.InvoiceSummary;
import nc.ticagou.billing.BillingDtos.LineDto;
import nc.ticagou.billing.BillingDtos.MarkSentRequest;
import nc.ticagou.child.Child;
import nc.ticagou.child.ChildRepository;
import nc.ticagou.common.ApiException;
import nc.ticagou.common.CounterService;
import nc.ticagou.common.Formats;
import nc.ticagou.family.Family;
import nc.ticagou.family.FamilyRepository;

/**
 * Factures émises : liste, détail, envoi noté à la main (aucun e-mail), avoirs. Une facture ne se modifie ni ne se
 * supprime : l'avoir total l'annule et permet de refacturer le mois ; l'avoir partiel rembourse une partie.
 */
@Service
public class InvoiceService {

    static final String CREDIT_NOTE_SERIES = "credit_note";

    private final InvoiceRepository invoices;
    private final InvoiceLineRepository lines;
    private final InvoiceEventRepository events;
    private final CreditNoteRepository creditNotes;
    private final CreditNoteLineRepository creditNoteLines;
    private final FunderRequestRepository requests;
    private final FunderRequestLineRepository requestLines;
    private final FamilyRepository families;
    private final ChildRepository children;
    private final InvoiceBalances balances;
    private final CounterService counters;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public InvoiceService(InvoiceRepository invoices, InvoiceLineRepository lines, InvoiceEventRepository events,
                          CreditNoteRepository creditNotes, CreditNoteLineRepository creditNoteLines,
                          FunderRequestRepository requests, FunderRequestLineRepository requestLines,
                          FamilyRepository families, ChildRepository children, InvoiceBalances balances,
                          CounterService counters, JdbcTemplate jdbc, AuditService audit, ApplicationEventPublisher publisher, Clock clock) {
        this.invoices = invoices;
        this.lines = lines;
        this.events = events;
        this.creditNotes = creditNotes;
        this.creditNoteLines = creditNoteLines;
        this.requests = requests;
        this.requestLines = requestLines;
        this.families = families;
        this.children = children;
        this.balances = balances;
        this.counters = counters;
        this.jdbc = jdbc;
        this.audit = audit;
        this.publisher = publisher;
        this.clock = clock;
    }

    /** Factures filtrées par mois, famille ou état ; les plus récentes d'abord. */
    @Transactional(readOnly = true)
    public List<InvoiceSummary> list(YearMonth month, UUID familyId, InvoiceStatus status) {
        var structureId = CurrentUser.get().structureId();
        List<Invoice> found;
        if (familyId != null) {
            found = invoices.findByFamilyIdOrderByMonthDescNumberDesc(familyId).stream()
                    .filter(i -> i.getStructureId().equals(structureId)).toList();
        } else if (month != null) {
            found = invoices.findByStructureIdAndMonthOrderByNumberAsc(structureId, month.atDay(1));
        } else {
            found = invoices.findByStructureIdOrderByIssuedOnDescNumberDesc(structureId);
        }
        if (month != null && familyId != null) {
            found = found.stream().filter(i -> i.getMonth().equals(month)).toList();
        }
        var result = summaries(found);
        return status == null ? result : result.stream().filter(s -> s.status() == status).toList();
    }

    @Transactional(readOnly = true)
    public List<InvoiceSummary> summaries(Collection<Invoice> found) {
        var today = LocalDate.now(clock);
        var byId = balances.of(found.stream().map(Invoice::getId).toList());
        var labels = families.findAllById(found.stream().map(Invoice::getFamilyId).distinct().toList()).stream()
                .collect(Collectors.toMap(Family::getId, Family::getLabel));
        return found.stream().map(i -> summary(i, byId.getOrDefault(i.getId(),
                new InvoiceBalances.Balance(0, false, 0, null, 0)), labels.get(i.getFamilyId()), today)).toList();
    }

    @Transactional(readOnly = true)
    public InvoiceDto get(UUID id) {
        var invoice = load(id);
        var balance = balances.of(id);
        var label = families.findById(invoice.getFamilyId()).map(Family::getLabel).orElse("");
        var invoiceLines = lines.findByInvoiceIdOrderByPositionAsc(id);
        var names = children.findAllById(invoiceLines.stream().map(InvoiceLine::getChildId)
                        .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Child::getId, Child::getFirstName));
        var lineDtos = invoiceLines.stream().map(l -> new LineDto(l.getChildId(), names.get(l.getChildId()),
                l.getKind(), l.getLabel(), l.getDetail(), l.getQuantity(), l.getUnitPrice(), l.getAmount(),
                l.getTgcRate(), l.getTgcAmount(), l.total(), l.getFunderId())).toList();
        var notes = creditNotes.findByInvoiceIdOrderByCreatedAtAsc(id).stream()
                .map(n -> creditNoteDto(n, invoice)).toList();
        var history = events.findByInvoiceIdOrderByCreatedAtAsc(id).stream()
                .map(e -> new EventDto(e.getKind().name(), e.getLabel(), e.getAuthor(), e.getCreatedAt())).toList();
        return new InvoiceDto(summary(invoice, balance, label, LocalDate.now(clock)), invoice.getPayerAddress(),
                invoice.getCareTotal(), invoice.getTgcTotal(), invoice.getRounding(), invoice.getContractText(),
                invoice.getIssuedBy(), lineDtos, notes, allocations(id), history);
    }

    /** Aucun e-mail n'est envoyé : la facture est imprimée, remise ou postée, et l'envoi est noté ici. */
    @Transactional
    public InvoiceDto markSent(UUID id, MarkSentRequest request) {
        var invoice = load(id);
        var today = LocalDate.now(clock);
        if (request.sentOn().isAfter(today) || request.sentOn().isBefore(invoice.getIssuedOn())) {
            throw ApiException.rule("invoice_sent_on",
                    "La date d’envoi doit être comprise entre l’émission de la facture et aujourd’hui.");
        }
        var user = CurrentUser.get();
        var channel = request.channel() == null || request.channel().isBlank() ? "" : " (" + request.channel().trim() + ")";
        events.save(new InvoiceEvent(invoice, InvoiceEvent.Kind.ENVOYEE,
                "Notée envoyée le %s%s".formatted(Formats.date(request.sentOn()), channel), user.displayName(),
                at(request.sentOn())));
        audit.record(AuditAction.INVOICE_SENT, "invoice", id,
                Map.of("number", invoice.getNumber(), "sentOn", request.sentOn().toString()));
        return get(id);
    }

    @Transactional
    public CreditNoteDto createCreditNote(UUID invoiceId, CreditNoteRequest request) {
        var invoice = load(invoiceId);
        var balance = balances.of(invoiceId);
        if (balance.cancelled()) {
            throw ApiException.conflict("invoice_cancelled", "Cette facture est déjà annulée par un avoir total.");
        }
        long remaining = balance.remaining(invoice.getFamilyTotal());
        var user = CurrentUser.get();
        var today = LocalDate.now(clock);
        var creditLines = new ArrayList<Map.Entry<String, Long>>();
        long familyTotal;
        long aidTotal = 0;
        if (request.full()) {
            if (balance.paid() > 0) {
                throw ApiException.rule("invoice_paid",
                        "Des paiements sont lettrés sur cette facture : annulez-les avant l’avoir total, puis "
                                + "enregistrez-les sur la nouvelle facture.");
            }
            for (var line : lines.findByInvoiceIdOrderByPositionAsc(invoiceId)) {
                creditLines.add(Map.entry(line.getLabel(), line.total()));
            }
            if (invoice.getRounding() != 0) {
                creditLines.add(Map.entry("Arrondi", invoice.getRounding()));
            }
            if (balance.credited() > 0) {
                creditLines.add(Map.entry("Avoirs déjà émis", -balance.credited()));
            }
            familyTotal = remaining;
            aidTotal = invoice.getAidTotal();
        } else {
            request.lines().forEach(l -> creditLines.add(Map.entry(l.label().trim(), l.amount())));
            familyTotal = request.lines().stream().mapToLong(BillingDtos.CreditLineRequest::amount).sum();
            if (familyTotal > remaining) {
                throw ApiException.rule("credit_note_too_high",
                        "L’avoir (%s) dépasse le reste dû sur la facture (%s).".formatted(Formats.xpf(familyTotal),
                                Formats.xpf(remaining)));
            }
        }
        int seq = counters.next(invoice.getStructureId(), CREDIT_NOTE_SERIES, today.getYear());
        var note = creditNotes.save(new CreditNote(invoice.getStructureId(), "AV-%d-%04d".formatted(today.getYear(), seq),
                invoiceId, today, request.reason().trim(), request.full(), familyTotal, aidTotal, user.displayName(),
                Instant.now(clock)));
        for (int i = 0; i < creditLines.size(); i++) {
            creditNoteLines.save(new CreditNoteLine(note, i, creditLines.get(i).getKey(), creditLines.get(i).getValue()));
        }
        if (request.full()) {
            withdrawUnsentAids(invoiceId);
        }
        events.save(new InvoiceEvent(invoice, InvoiceEvent.Kind.AVOIR, "%s %s de %s : %s".formatted(
                request.full() ? "Avoir total" : "Avoir", note.getNumber(), Formats.xpf(familyTotal),
                note.getReason()), user.displayName(), note.getCreatedAt()));
        var details = new LinkedHashMap<String, Object>();
        details.put("number", note.getNumber());
        details.put("invoice", invoice.getNumber());
        details.put("full", request.full());
        details.put("familyTotal", familyTotal);
        details.put("reason", note.getReason());
        audit.record(AuditAction.CREDIT_NOTE_ISSUED, "credit_note", note.getId(), details);
        publisher.publishEvent(new PortalChange(invoice.getStructureId(), PortalItemType.INVOICE, note.getId()));
        publisher.publishEvent(new PortalChange(invoice.getStructureId(), PortalItemType.INVOICE, invoiceId));
        return creditNoteDto(note, invoice);
    }

    @Transactional(readOnly = true)
    public CreditNoteDto getCreditNote(UUID id) {
        var note = creditNotes.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Avoir introuvable."));
        return creditNoteDto(note, invoices.findById(note.getInvoiceId()).orElseThrow());
    }

    /**
     * Facture annulée : ses aides sont retirées des demandes aux financeurs pas encore envoyées. Une demande déjà
     * envoyée reste telle quelle ; la nouvelle facture ne réclamera que l'écart.
     */
    private void withdrawUnsentAids(UUID invoiceId) {
        var byRequest = requestLines.findByInvoiceId(invoiceId).stream()
                .collect(Collectors.groupingBy(FunderRequestLine::getRequestId));
        for (var entry : byRequest.entrySet()) {
            var request = requests.findById(entry.getKey()).orElseThrow();
            if (request.isSent()) {
                continue;
            }
            requestLines.deleteAll(entry.getValue());
            requestLines.flush();
            var rest = requestLines.findByRequestId(request.getId());
            if (rest.isEmpty()) {
                requests.delete(request);
            } else {
                request.setTotal(rest.stream().mapToLong(FunderRequestLine::getAmount).sum());
            }
        }
    }

    private List<AllocationDto> allocations(UUID invoiceId) {
        return jdbc.query("""
                        SELECT p.id, p.paid_on, p.method, p.reference, a.amount, p.cancelled_at IS NOT NULL AS cancelled
                        FROM payment_allocation a JOIN payment p ON p.id = a.payment_id
                        WHERE a.invoice_id = ? ORDER BY p.paid_on, p.created_at""",
                (rs, n) -> new AllocationDto(rs.getObject("id", UUID.class), rs.getObject("paid_on", LocalDate.class),
                        rs.getString("method"), rs.getString("reference"), rs.getLong("amount"),
                        rs.getBoolean("cancelled")),
                invoiceId);
    }

    private CreditNoteDto creditNoteDto(CreditNote note, Invoice invoice) {
        var noteLines = creditNoteLines.findByCreditNoteIdOrderByPositionAsc(note.getId()).stream()
                .map(l -> new CreditNoteLineDto(l.getLabel(), l.getAmount())).toList();
        return new CreditNoteDto(note.getId(), note.getNumber(), invoice.getId(), invoice.getNumber(),
                note.getIssuedOn(), note.getReason(), note.isFullCancel(), note.getFamilyTotal(), note.getAidTotal(),
                noteLines, note.getIssuedBy());
    }

    static InvoiceSummary summary(Invoice i, InvoiceBalances.Balance b, String familyLabel, LocalDate today) {
        return new InvoiceSummary(i.getId(), i.getNumber(), i.getFamilyId(), familyLabel, i.getPayerName(),
                i.getMonth(), i.getIssuedOn(), i.getDueOn(), i.getFamilyTotal(), i.getAidTotal(), b.credited(),
                b.paid(), b.remaining(i.getFamilyTotal()), b.status(i, today), b.sentOn(), b.reminderLevel(),
                b.daysLate(i, today));
    }

    /** Horodatage d'un événement daté par l'utilisateur : ce jour-là, à l'heure courante. */
    private Instant at(LocalDate day) {
        var now = Instant.now(clock);
        return day.equals(LocalDate.now(clock)) ? now
                : day.atTime(LocalTime.now(clock)).atZone(clock.getZone()).toInstant();
    }

    Invoice load(UUID id) {
        return invoices.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Facture introuvable."));
    }
}
