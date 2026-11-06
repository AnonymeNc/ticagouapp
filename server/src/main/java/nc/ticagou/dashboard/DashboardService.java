package nc.ticagou.dashboard;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.attendance.ChildAttendance;
import nc.ticagou.attendance.ChildAttendanceRepository;
import nc.ticagou.attendance.PlannedDays;
import nc.ticagou.auth.AppUser;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.billing.AbsenceReason;
import nc.ticagou.billing.AbsenceReasonRepository;
import nc.ticagou.billing.BillingDtos.InvoiceSummary;
import nc.ticagou.billing.ChildAbsenceRepository;
import nc.ticagou.billing.CreditNote;
import nc.ticagou.billing.CreditNoteRepository;
import nc.ticagou.billing.Invoice;
import nc.ticagou.billing.InvoiceRepository;
import nc.ticagou.billing.InvoiceService;
import nc.ticagou.child.Child;
import nc.ticagou.child.ChildRepository;
import nc.ticagou.contract.Contract;
import nc.ticagou.contract.ContractRepository;
import nc.ticagou.contract.ContractVersion;
import nc.ticagou.contract.ContractVersionRepository;
import nc.ticagou.dashboard.DashboardDtos.Kpis;
import nc.ticagou.dashboard.DashboardDtos.PlannedDeparture;
import nc.ticagou.dashboard.DashboardDtos.ReportedAbsence;
import nc.ticagou.dashboard.DashboardDtos.StaffArrival;
import nc.ticagou.dashboard.DashboardDtos.Today;
import nc.ticagou.enrollment.EnrollmentRequest;
import nc.ticagou.enrollment.EnrollmentRequestRepository;
import nc.ticagou.staff.PlanningService;
import nc.ticagou.structure.OpeningHours;
import nc.ticagou.structure.StructureRepository;

/**
 * Tableau de bord de la direction (maquette « matinée type ») : ce que les autres écrans n'ont pas déjà
 * (absents signalés, départs prévus, arrivées du personnel) et les indicateurs du mois.
 */
@Service
public class DashboardService {

    /** Demandes encore sur la liste d'attente. */
    static final EnumSet<EnrollmentRequest.Stage> WAITING = EnumSet.of(EnrollmentRequest.Stage.NOUVELLE,
            EnrollmentRequest.Stage.A_RECONTACTER, EnrollmentRequest.Stage.VISITE_PREVUE,
            EnrollmentRequest.Stage.PLACE_PROPOSEE);

    private final StructureRepository structures;
    private final ChildRepository children;
    private final ChildAbsenceRepository absences;
    private final AbsenceReasonRepository reasons;
    private final ChildAttendanceRepository attendances;
    private final PlannedDays plannedDays;
    private final PlanningService planning;
    private final ContractRepository contracts;
    private final ContractVersionRepository versions;
    private final InvoiceRepository invoices;
    private final CreditNoteRepository creditNotes;
    private final InvoiceService invoiceService;
    private final EnrollmentRequestRepository requests;
    private final Clock clock;

    public DashboardService(StructureRepository structures, ChildRepository children, ChildAbsenceRepository absences,
                            AbsenceReasonRepository reasons, ChildAttendanceRepository attendances,
                            PlannedDays plannedDays, PlanningService planning, ContractRepository contracts,
                            ContractVersionRepository versions, InvoiceRepository invoices,
                            CreditNoteRepository creditNotes, InvoiceService invoiceService,
                            EnrollmentRequestRepository requests, Clock clock) {
        this.structures = structures;
        this.children = children;
        this.absences = absences;
        this.reasons = reasons;
        this.attendances = attendances;
        this.plannedDays = plannedDays;
        this.planning = planning;
        this.contracts = contracts;
        this.versions = versions;
        this.invoices = invoices;
        this.creditNotes = creditNotes;
        this.invoiceService = invoiceService;
        this.requests = requests;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Today today() {
        var structureId = CurrentUser.get().structureId();
        var day = LocalDate.now(clock);
        var now = LocalTime.now(clock);
        var structure = structures.findById(structureId).orElseThrow();
        var closesAt = structure.getOpeningHours().stream().filter(h -> h.dayOfWeek() == day.getDayOfWeek().getValue())
                .map(OpeningHours::closesAt).findFirst().orElse(null);
        var childById = children.findByStructureId(structureId).stream()
                .collect(Collectors.toMap(Child::getId, Function.identity()));
        var reasonById = reasons.findByStructureIdOrderByDisplayOrderAscLabelAsc(structureId).stream()
                .collect(Collectors.toMap(AbsenceReason::getId, Function.identity()));

        var reported = absences.overlapping(structureId, day, day).stream()
                .filter(a -> childById.containsKey(a.getChildId()))
                .map(a -> {
                    var child = childById.get(a.getChildId());
                    var reason = reasonById.get(a.getReasonId());
                    return new ReportedAbsence(child.getId(), child.displayName(), child.getSectionId(),
                            reason == null ? null : reason.getLabel(), a.getToDay(), a.getNotifiedOn(), a.getNote());
                })
                .sorted(Comparator.comparing(ReportedAbsence::name)).toList();
        var absent = reported.stream().map(ReportedAbsence::childId).collect(Collectors.toSet());

        var today = attendances.findByStructureIdAndDayOrderByArrivedAtAsc(structureId, day);
        var present = today.stream().filter(a -> a.getDepartedAt() == null).map(ChildAttendance::getChildId)
                .collect(Collectors.toSet());
        var departed = today.stream().filter(a -> a.getDepartedAt() != null).map(ChildAttendance::getChildId)
                .collect(Collectors.toSet());
        var departures = closesAt == null ? List.<PlannedDeparture>of() : plannedDays.forDay(structureId, day).entrySet().stream()
                .filter(e -> e.getValue().expected() && childById.containsKey(e.getKey()) && !absent.contains(e.getKey())
                        && !departed.contains(e.getKey()))
                // Départ au moins une demi-heure avant la fermeture : à préparer (transmissions, repas).
                .filter(e -> e.getValue().leavesAt().plusMinutes(30).isBefore(closesAt.plusSeconds(1)))
                .map(e -> {
                    var child = childById.get(e.getKey());
                    return new PlannedDeparture(child.getId(), child.displayName(), child.getSectionId(),
                            e.getValue().leavesAt(), present.contains(child.getId()));
                })
                .sorted(Comparator.comparing(PlannedDeparture::leavesAt).thenComparing(PlannedDeparture::name)).toList();

        var staff = planning.staff(structureId);
        var byId = staff.stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));
        var arrivals = planning.shifts(structureId, staff, day, day).entrySet().stream()
                .map(e -> Map.entry(byId.get(e.getKey()), e.getValue().getFirst()))
                .filter(e -> e.getValue().working() && e.getValue().startsAt().isAfter(now))
                .map(e -> new StaffArrival(e.getKey().getId(), e.getKey().displayName(),
                        e.getValue().sectionId() != null ? e.getValue().sectionId() : e.getKey().getHomeSectionId(),
                        e.getValue().startsAt(), e.getKey().isCountsInRatio()))
                .sorted(Comparator.comparing(StaffArrival::startsAt)).toList();
        return new Today(day, closesAt, reported, departures, arrivals);
    }

    @Transactional(readOnly = true)
    public Kpis kpis() {
        var structureId = CurrentUser.get().structureId();
        var day = LocalDate.now(clock);
        var month = YearMonth.from(day);
        var structure = structures.findById(structureId).orElseThrow();

        // Remplissage : heures hebdomadaires réservées aux contrats en vigueur / heures d'ouverture × capacité.
        var allContracts = contracts.findByStructureId(structureId);
        var signed = versions.findByStructureIdAndStatus(structureId, ContractVersion.Status.SIGNE).stream()
                .collect(Collectors.groupingBy(ContractVersion::getContractId));
        var childById = children.findByStructureId(structureId).stream()
                .collect(Collectors.toMap(Child::getId, Function.identity()));
        long reserved = 0;
        var enrolled = new HashSet<UUID>();
        for (var contract : allContracts) {
            var child = childById.get(contract.getChildId());
            if (child == null || !child.isEnrolledOn(day) || !contract.covers(day)) {
                continue;
            }
            var inForce = signed.getOrDefault(contract.getId(), List.of()).stream()
                    .filter(v -> !v.getEffectiveFrom().isAfter(day))
                    .max(Comparator.comparing(ContractVersion::getEffectiveFrom));
            if (inForce.isPresent()) {
                reserved += inForce.get().getWeeklyMinutes();
                enrolled.add(child.getId());
            }
        }
        long weeklyOpen = structure.getOpeningHours().stream()
                .mapToLong(h -> Duration.between(h.opensAt(), h.closesAt()).toMinutes()).sum();

        // Dernier mois facturé, comparé au précédent (factures non annulées par un avoir total).
        var all = invoices.findByStructureIdOrderByIssuedOnDescNumberDesc(structureId);
        var cancelled = creditNotes.findByInvoiceIdIn(all.stream().map(Invoice::getId).toList()).stream()
                .filter(CreditNote::isFullCancel).map(CreditNote::getInvoiceId).collect(Collectors.toSet());
        var live = all.stream().filter(i -> !cancelled.contains(i.getId())).toList();
        var lastMonth = live.stream().map(Invoice::getMonth).max(Comparator.naturalOrder()).orElse(null);
        long invoiced = revenue(live, lastMonth);
        long previous = lastMonth == null ? 0 : revenue(live, lastMonth.minusMonths(1));
        int invoiceCount = lastMonth == null ? 0 : (int) live.stream().filter(i -> i.getMonth().equals(lastMonth)).count();
        int billable = lastMonth == null ? 0 : (int) allContracts.stream()
                .filter(c -> signed.containsKey(c.getId()) && c.overlaps(lastMonth.atDay(1), lastMonth.atEndOfMonth()))
                .map(Contract::getChildId).map(childById::get).filter(Objects::nonNull)
                .map(Child::getFamilyId).distinct().count();

        var unpaid = invoiceService.summaries(live).stream().filter(s -> s.remaining() > 0).toList();
        var oldest = unpaid.stream().map(InvoiceSummary::issuedOn).min(Comparator.naturalOrder()).orElse(null);

        var waiting = requests.findByStructureId(structureId).stream().filter(r -> WAITING.contains(r.getStage())).toList();
        var bySection = new HashMap<UUID, Integer>();
        waiting.stream().filter(r -> r.getSectionId() != null).forEach(r -> bySection.merge(r.getSectionId(), 1, Integer::sum));
        int newRequests = (int) requests.findByStructureId(structureId).stream()
                .filter(r -> r.getReceivedOn() != null && YearMonth.from(r.getReceivedOn()).equals(month)).count();

        return new Kpis(month, Indicators.fillRate(reserved, structure.getCapacity(), weeklyOpen), reserved,
                enrolled.size(), structure.getCapacity(), lastMonth, invoiced, Indicators.change(invoiced, previous),
                invoiceCount, billable, unpaid.stream().mapToLong(InvoiceSummary::remaining).sum(), unpaid.size(), oldest,
                waiting.size(), bySection, newRequests);
    }

    /** Frais de garde facturés d'un mois, TGC comprise (aides comprises : c'est le chiffre d'affaires). */
    private static long revenue(List<Invoice> live, YearMonth month) {
        if (month == null) {
            return 0;
        }
        return live.stream().filter(i -> i.getMonth().equals(month)).mapToLong(i -> i.getCareTotal() + i.getTgcTotal()).sum();
    }
}
