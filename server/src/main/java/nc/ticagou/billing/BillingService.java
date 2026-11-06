package nc.ticagou.billing;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.portal.PortalChange;
import nc.ticagou.portal.PortalItemType;
import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.billing.BillingDtos.AnomalyDto;
import nc.ticagou.billing.BillingDtos.FamilyPreview;
import nc.ticagou.billing.BillingDtos.FamilyRow;
import nc.ticagou.billing.BillingDtos.FamilyStatus;
import nc.ticagou.billing.BillingDtos.FunderColumn;
import nc.ticagou.billing.BillingDtos.FunderRequestSummary;
import nc.ticagou.billing.BillingDtos.GenerateRequest;
import nc.ticagou.billing.BillingDtos.GenerationResult;
import nc.ticagou.billing.BillingDtos.LineDto;
import nc.ticagou.billing.BillingDtos.MonthPreview;
import nc.ticagou.billing.BillingDtos.PreviewTotals;
import nc.ticagou.billing.BillingMonthAssembler.FamilyMonth;
import nc.ticagou.billing.InvoiceCalculator.Kind;
import nc.ticagou.billing.InvoiceCalculator.Result;
import nc.ticagou.child.Child;
import nc.ticagou.common.ApiException;
import nc.ticagou.common.CounterService;
import nc.ticagou.common.Formats;

/**
 * « Facturer le mois » : prévisualisation de toutes les familles avec leurs anomalies, puis génération en lot.
 * La génération se fait dans une seule transaction : les numéros (FA-AAAA-NNNN, DF-AAAA-NNNN) viennent d'un
 * compteur en base incrémenté dans cette transaction, si bien qu'un échec annule tout sans laisser de trou.
 */
@Service
public class BillingService {

    static final String INVOICE_SERIES = "invoice";
    static final String FUNDER_REQUEST_SERIES = "funder_request";

    private final BillingMonthAssembler assembler;
    private final BillingSettingsService settings;
    private final InvoiceRepository invoices;
    private final InvoiceLineRepository lines;
    private final InvoiceEventRepository events;
    private final FunderRepository funders;
    private final FunderRequestRepository requests;
    private final FunderRequestLineRepository requestLines;
    private final CounterService counters;
    private final AuditService audit;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public BillingService(BillingMonthAssembler assembler, BillingSettingsService settings, InvoiceRepository invoices,
                          InvoiceLineRepository lines, InvoiceEventRepository events, FunderRepository funders,
                          FunderRequestRepository requests, FunderRequestLineRepository requestLines,
                          CounterService counters, AuditService audit, ApplicationEventPublisher publisher, Clock clock) {
        this.assembler = assembler;
        this.settings = settings;
        this.invoices = invoices;
        this.lines = lines;
        this.events = events;
        this.funders = funders;
        this.requests = requests;
        this.requestLines = requestLines;
        this.counters = counters;
        this.audit = audit;
        this.publisher = publisher;
        this.clock = clock;
    }

    /** Aide déduite d'une facture, à réclamer au financeur. */
    private record Aid(UUID childId, UUID invoiceId, long amount, int attendanceDays, String beneficiary) {
    }

    /** Famille du mois et son calcul. */
    private record Computed(FamilyMonth month, Result result, FamilyStatus status, List<AnomalyDto> anomalies) {
    }

    @Transactional(readOnly = true)
    public MonthPreview preview(YearMonth month) {
        var structureId = CurrentUser.get().structureId();
        var computed = compute(structureId, month);
        var byPriority = funders.findByStructureIdOrderByPriorityAscNameAsc(structureId);
        var usedFunders = new LinkedHashMap<UUID, Long>();
        var rows = new ArrayList<FamilyRow>();
        var anomalies = new ArrayList<AnomalyDto>();
        long care = 0;
        long family = 0;
        int ready = 0;
        int withAnomalies = 0;
        int invoiced = 0;
        for (var c : computed) {
            if (c.status() == FamilyStatus.RIEN) {
                continue;
            }
            var row = row(c);
            rows.add(row);
            anomalies.addAll(c.anomalies());
            care += row.careTotal();
            family += row.familyTotal();
            row.aids().forEach((id, amount) -> usedFunders.merge(id, amount, Long::sum));
            switch (c.status()) {
                case PRETE -> ready++;
                case ANOMALIE -> withAnomalies++;
                case FACTUREE -> invoiced++;
                default -> { }
            }
        }
        var columns = byPriority.stream().filter(f -> usedFunders.containsKey(f.getId()))
                .map(f -> new FunderColumn(f.getId(), f.getName())).toList();
        long aids = usedFunders.values().stream().mapToLong(Long::longValue).sum();
        return new MonthPreview(month, !month.isAfter(YearMonth.now(clock)), columns, rows, anomalies,
                new PreviewTotals(care, family, aids, usedFunders, rows.size(), ready, withAnomalies, invoiced));
    }

    @Transactional(readOnly = true)
    public FamilyPreview previewFamily(YearMonth month, UUID familyId) {
        var structureId = CurrentUser.get().structureId();
        var c = compute(structureId, month).stream().filter(x -> x.month().family().getId().equals(familyId))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Rien à facturer à cette famille pour ce mois."));
        var r = c.result();
        var rule = settings.requireRule(structureId, month.atDay(1));
        return new FamilyPreview(row(c), c.month().payerAddress(), contractText(c.month()),
                LocalDate.now(clock).plusDays(rule.getPaymentDays()), lineDtos(c.month(), r.lines()), r.careTotal(),
                r.tgcTotal(), r.aidTotal(), r.rounding(), r.familyTotal(), r.notes(), c.anomalies());
    }

    /**
     * Génère les factures des familles prêtes (ou de celles demandées), puis les demandes aux financeurs.
     * Les familles en anomalie, déjà facturées ou sans rien à facturer sont écartées.
     */
    @Transactional
    public GenerationResult generate(YearMonth month, GenerateRequest request) {
        var user = CurrentUser.get();
        var structureId = user.structureId();
        var today = LocalDate.now(clock);
        if (month.isAfter(YearMonth.from(today))) {
            throw ApiException.rule("billing_future_month", "On ne facture pas un mois qui n’a pas commencé.");
        }
        Set<UUID> wanted = request == null || request.familyIds() == null || request.familyIds().isEmpty()
                ? null : new HashSet<>(request.familyIds());
        var rule = settings.requireRule(structureId, month.atDay(1));
        var now = Instant.now(clock);
        var issued = new ArrayList<Invoice>();
        var skipped = new ArrayList<String>();
        // Aides déduites, par financeur.
        var aidsByFunder = new LinkedHashMap<UUID, List<Aid>>();
        for (var c : compute(structureId, month)) {
            var family = c.month().family();
            if (wanted != null && !wanted.contains(family.getId())) {
                continue;
            }
            if (c.status() != FamilyStatus.PRETE) {
                if (wanted != null || c.status() == FamilyStatus.ANOMALIE) {
                    skipped.add(family.getLabel() + " — " + switch (c.status()) {
                        case ANOMALIE -> "anomalie à corriger";
                        case FACTUREE -> "déjà facturée";
                        default -> "rien à facturer";
                    });
                }
                continue;
            }
            var r = c.result();
            int seq = counters.next(structureId, INVOICE_SERIES, today.getYear());
            var invoice = invoices.save(new Invoice(structureId, "FA-%d-%04d".formatted(today.getYear(), seq),
                    family.getId(), c.month().payer().getFirstName() + " " + c.month().payer().getLastName(),
                    c.month().payerAddress(), month, today, today.plusDays(rule.getPaymentDays()), r.careTotal(),
                    r.tgcTotal(), r.aidTotal(), r.familyTotal(), r.rounding(), contractText(c.month()),
                    user.displayName(), now));
            int position = 0;
            for (var line : r.lines()) {
                lines.save(new InvoiceLine(invoice, position++, line));
                if (line.kind() == Kind.AIDE) {
                    var beneficiary = c.month().beneficiaries().getOrDefault(line.childId(), Map.of())
                            .get(line.funderId());
                    aidsByFunder.computeIfAbsent(line.funderId(), k -> new ArrayList<>()).add(new Aid(
                            line.childId(), invoice.getId(), -line.amount(),
                            c.month().attendanceDays().getOrDefault(line.childId(), 0), beneficiary));
                }
            }
            events.save(new InvoiceEvent(invoice, InvoiceEvent.Kind.GENEREE,
                    "Facture générée (%s)".formatted(Formats.monthYear(month)), user.displayName(), now));
            issued.add(invoice);
            publisher.publishEvent(new PortalChange(structureId, PortalItemType.INVOICE, invoice.getId()));
        }
        if (issued.isEmpty()) {
            throw ApiException.rule("billing_nothing_ready",
                    "Aucune famille n’est prête à facturer pour %s.".formatted(Formats.monthYear(month)));
        }

        var summaries = new ArrayList<FunderRequestSummary>();
        for (var entry : aidsByFunder.entrySet()) {
            var funder = funders.findById(entry.getKey()).orElseThrow();
            // Déjà réclamé pour ce mois (factures annulées par un avoir puis refaites) : on ne demande que l'écart.
            var previous = requests.findByFunderIdAndMonth(funder.getId(), month.atDay(1));
            var claimed = new HashMap<UUID, Long>();
            requestLines.findByRequestIdIn(previous.stream().map(FunderRequest::getId).toList())
                    .forEach(l -> claimed.merge(l.getChildId(), l.getAmount(), Long::sum));
            var newLines = new ArrayList<Aid>();
            for (var aid : entry.getValue()) {
                long delta = aid.amount() - claimed.getOrDefault(aid.childId(), 0L);
                claimed.merge(aid.childId(), delta, Long::sum);
                if (delta != 0) {
                    newLines.add(new Aid(aid.childId(), aid.invoiceId(), delta, aid.attendanceDays(), aid.beneficiary()));
                }
            }
            if (newLines.isEmpty()) {
                continue;
            }
            int seq = counters.next(structureId, FUNDER_REQUEST_SERIES, today.getYear());
            var funderRequest = new FunderRequest(structureId, "DF-%d-%04d".formatted(today.getYear(), seq),
                    funder.getId(), month, now);
            funderRequest.setTotal(newLines.stream().mapToLong(Aid::amount).sum());
            requests.save(funderRequest);
            for (var l : newLines) {
                requestLines.save(new FunderRequestLine(funderRequest, l.childId(), l.invoiceId(), l.beneficiary(),
                        l.attendanceDays(), l.amount()));
            }
            summaries.add(new FunderRequestSummary(funderRequest.getId(), funderRequest.getNumber(), funder.getName(),
                    funderRequest.getTotal()));
        }

        long familyTotal = issued.stream().mapToLong(Invoice::getFamilyTotal).sum();
        long aidTotal = issued.stream().mapToLong(Invoice::getAidTotal).sum();
        var details = new LinkedHashMap<String, Object>();
        details.put("month", month.toString());
        details.put("invoices", issued.size());
        details.put("first", issued.getFirst().getNumber());
        details.put("last", issued.getLast().getNumber());
        details.put("familyTotal", familyTotal);
        details.put("aidTotal", aidTotal);
        details.put("skipped", skipped.size());
        audit.record(AuditAction.INVOICES_GENERATED, "invoice", issued.getFirst().getId(), details);
        return new GenerationResult(month, issued.size(), issued.getFirst().getNumber(), issued.getLast().getNumber(),
                familyTotal, aidTotal, skipped, summaries);
    }

    // ---------------------------------------------------------------- outils

    private List<Computed> compute(UUID structureId, YearMonth month) {
        var result = new ArrayList<Computed>();
        for (var fm : assembler.assemble(structureId, month)) {
            var r = InvoiceCalculator.compute(fm.input());
            var anomalies = new ArrayList<AnomalyDto>();
            r.anomalies().forEach(a -> anomalies.add(new AnomalyDto(fm.family().getId(), fm.family().getLabel(),
                    a.childId(), a.childName(), a.type().name(), a.message(), a.days())));
            if (fm.payer() == null) {
                anomalies.add(new AnomalyDto(fm.family().getId(), fm.family().getLabel(), null, null, "SANS_PAYEUR",
                        "Famille %s — aucun responsable n’est désigné payeur.".formatted(fm.family().getLabel()),
                        List.of()));
            }
            FamilyStatus status;
            if (fm.invoice() != null) {
                status = FamilyStatus.FACTUREE;
                anomalies.clear();
            } else if (!anomalies.isEmpty()) {
                status = FamilyStatus.ANOMALIE;
            } else if (r.lines().isEmpty()) {
                status = FamilyStatus.RIEN;
            } else {
                status = FamilyStatus.PRETE;
            }
            result.add(new Computed(fm, r, status, anomalies));
        }
        return result;
    }

    private static FamilyRow row(Computed c) {
        var fm = c.month();
        var r = c.result();
        long forfait = 0;
        long extra = 0;
        long overtime = 0;
        long absences = 0;
        long other = 0;
        var aids = new LinkedHashMap<UUID, Long>();
        for (var t : r.children()) {
            forfait += t.forfait();
            extra += t.extraHours();
            overtime += t.overtime();
            absences += t.absences();
            other += t.other();
            t.aids().forEach((id, amount) -> aids.merge(id, amount, Long::sum));
        }
        var invoice = fm.invoice();
        return new FamilyRow(fm.family().getId(), fm.family().getLabel(),
                fm.payer() == null ? null : fm.payer().getFirstName() + " " + fm.payer().getLastName(),
                fm.children().stream().map(Child::getFirstName).toList(), forfait, extra, overtime, absences, other,
                r.tgcTotal(), r.careTotal() + r.tgcTotal(), aids,
                invoice == null ? r.aidTotal() : invoice.getAidTotal(),
                invoice == null ? r.familyTotal() : invoice.getFamilyTotal(), c.status(), c.anomalies().size(),
                invoice == null ? null : invoice.getId(), invoice == null ? null : invoice.getNumber());
    }

    static List<LineDto> lineDtos(FamilyMonth fm, List<InvoiceCalculator.Line> calculated) {
        var names = fm.children().stream().collect(Collectors.toMap(Child::getId, Child::getFirstName));
        return calculated.stream().map(l -> new LineDto(l.childId(), names.get(l.childId()), l.kind(), l.label(),
                l.detail(), l.quantity(), l.unitPrice(), l.amount(), l.tgcRate(), l.tgcAmount(), l.total(),
                l.funderId())).toList();
    }

    private static String contractText(FamilyMonth fm) {
        return fm.contractText();
    }
}
