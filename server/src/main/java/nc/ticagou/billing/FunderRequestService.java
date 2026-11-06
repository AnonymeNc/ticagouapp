package nc.ticagou.billing;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.billing.BillingDtos.FunderRequestDto;
import nc.ticagou.billing.BillingDtos.FunderRequestLineDto;
import nc.ticagou.billing.BillingDtos.MarkRequestSentRequest;
import nc.ticagou.child.Child;
import nc.ticagou.child.ChildRepository;
import nc.ticagou.common.ApiException;

/** Demandes de règlement aux financeurs, créées avec les factures du mois ; l'envoi est noté à la main. */
@Service
public class FunderRequestService {

    private final FunderRequestRepository requests;
    private final FunderRequestLineRepository lines;
    private final FunderRepository funders;
    private final InvoiceRepository invoices;
    private final ChildRepository children;
    private final InvoiceBalances balances;
    private final AuditService audit;
    private final Clock clock;

    public FunderRequestService(FunderRequestRepository requests, FunderRequestLineRepository lines,
                                FunderRepository funders, InvoiceRepository invoices, ChildRepository children,
                                InvoiceBalances balances, AuditService audit, Clock clock) {
        this.requests = requests;
        this.lines = lines;
        this.funders = funders;
        this.invoices = invoices;
        this.children = children;
        this.balances = balances;
        this.audit = audit;
        this.clock = clock;
    }

    /** Demandes d'un mois, ou toutes (historique) si le mois est absent. */
    @Transactional(readOnly = true)
    public List<FunderRequestDto> list(YearMonth month) {
        var structureId = CurrentUser.get().structureId();
        var found = month == null ? requests.findByStructureIdOrderByMonthDescNumberDesc(structureId)
                : requests.findByStructureIdAndMonthOrderByNumberAsc(structureId, month.atDay(1));
        return toDtos(found);
    }

    @Transactional(readOnly = true)
    public FunderRequestDto get(UUID id) {
        return toDtos(List.of(load(id))).getFirst();
    }

    @Transactional
    public FunderRequestDto markSent(UUID id, MarkRequestSentRequest request) {
        var funderRequest = load(id);
        var today = LocalDate.now(clock);
        if (request.sentOn().isAfter(today) || request.sentOn().isBefore(funderRequest.getMonth().atDay(1))) {
            throw ApiException.rule("funder_request_sent_on",
                    "La date d’envoi doit être comprise entre le mois facturé et aujourd’hui.");
        }
        funderRequest.markSent(request.sentOn(), CurrentUser.get().displayName());
        requests.saveAndFlush(funderRequest);
        audit.record(AuditAction.FUNDER_REQUEST_SENT, "funder_request", id,
                Map.of("number", funderRequest.getNumber(), "sentOn", request.sentOn().toString()));
        return get(id);
    }

    FunderRequest load(UUID id) {
        return requests.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Demande au financeur introuvable."));
    }

    List<FunderRequestDto> toDtos(List<FunderRequest> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        var structureId = found.getFirst().getStructureId();
        var fundersById = funders.findByStructureIdOrderByPriorityAscNameAsc(structureId).stream()
                .collect(Collectors.toMap(Funder::getId, Function.identity()));
        var linesByRequest = lines.findByRequestIdIn(found.stream().map(FunderRequest::getId).toList()).stream()
                .collect(Collectors.groupingBy(FunderRequestLine::getRequestId));
        var allLines = linesByRequest.values().stream().flatMap(List::stream).toList();
        var names = children.findAllById(allLines.stream().map(FunderRequestLine::getChildId).distinct().toList())
                .stream().collect(Collectors.toMap(Child::getId, Child::displayName));
        var numbers = invoices.findAllById(allLines.stream().map(FunderRequestLine::getInvoiceId).distinct().toList())
                .stream().collect(Collectors.toMap(Invoice::getId, Invoice::getNumber));
        var received = balances.received(found.stream().map(FunderRequest::getId).toList());
        return found.stream().map(r -> {
            var funder = fundersById.get(r.getFunderId());
            long got = received.getOrDefault(r.getId(), 0L);
            var requestLines = linesByRequest.getOrDefault(r.getId(), List.of()).stream()
                    .map(l -> new FunderRequestLineDto(l.getChildId(), names.get(l.getChildId()), l.getBeneficiary(),
                            l.getInvoiceId(), numbers.get(l.getInvoiceId()), l.getAttendanceDays(), l.getAmount()))
                    .sorted((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.childName(), b.childName()))
                    .toList();
            return new FunderRequestDto(r.getId(), r.getNumber(), r.getFunderId(), funder.getName(),
                    funder.getConventionRef(), r.getMonth(), r.getTotal(), got, Math.max(r.getTotal() - got, 0),
                    r.getSentOn(), r.getSentBy(), InvoiceBalances.requestStatus(r, got), requestLines, r.getVersion());
        }).toList();
    }
}
