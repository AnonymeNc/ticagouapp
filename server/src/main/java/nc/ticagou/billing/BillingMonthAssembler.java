package nc.ticagou.billing;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.attendance.ChildAttendance;
import nc.ticagou.attendance.ChildAttendanceRepository;
import nc.ticagou.billing.InvoiceCalculator.AdaptationBilling;
import nc.ticagou.billing.InvoiceCalculator.ChildInput;
import nc.ticagou.billing.InvoiceCalculator.ContractInput;
import nc.ticagou.billing.InvoiceCalculator.DayPlan;
import nc.ticagou.billing.InvoiceCalculator.MonthInput;
import nc.ticagou.billing.InvoiceCalculator.Passage;
import nc.ticagou.billing.InvoiceCalculator.Session;
import nc.ticagou.billing.InvoiceCalculator.VersionPeriod;
import nc.ticagou.child.Child;
import nc.ticagou.child.ChildRepository;
import nc.ticagou.common.Formats;
import nc.ticagou.contract.Contract;
import nc.ticagou.contract.ContractRepository;
import nc.ticagou.contract.ContractVersion;
import nc.ticagou.contract.ContractVersionRepository;
import nc.ticagou.family.Family;
import nc.ticagou.family.FamilyRepository;
import nc.ticagou.family.Guardian;
import nc.ticagou.family.GuardianRepository;
import nc.ticagou.structure.OpeningHours;
import nc.ticagou.structure.StructureRepository;

/**
 * Rassemble, pour un mois, ce que le calcul de facture attend de chaque famille : versions de contrat signées,
 * adaptation, pointages, absences, aides accordées, règles et taux en vigueur. Lecture seule ; le calcul lui-même
 * est fait par {@link InvoiceCalculator}.
 */
@Component
public class BillingMonthAssembler {

    /** Ce qu'il faut pour facturer une famille un mois donné. */
    public record FamilyMonth(Family family, Guardian payer, List<Child> children, MonthInput input,
                              Map<UUID, Integer> attendanceDays, Map<UUID, Map<UUID, String>> beneficiaries,
                              String contractText, Invoice invoice) {

        public String payerAddress() {
            return address(family);
        }
    }

    /** Adresse postale de la famille, sur deux lignes. */
    static String address(Family family) {
        var parts = new ArrayList<String>();
        if (family.getAddress() != null && !family.getAddress().isBlank()) {
            parts.add(family.getAddress().trim());
        }
        var city = ((family.getPostalCode() == null ? "" : family.getPostalCode()) + " "
                + (family.getCity() == null ? "" : family.getCity())).trim();
        if (!city.isEmpty()) {
            parts.add(city);
        }
        return parts.isEmpty() ? null : String.join("\n", parts);
    }

    private final StructureRepository structures;
    private final FamilyRepository families;
    private final GuardianRepository guardians;
    private final ChildRepository children;
    private final ContractRepository contracts;
    private final ContractVersionRepository versions;
    private final ChildAttendanceRepository attendances;
    private final ChildAbsenceRepository absences;
    private final AbsenceReasonRepository reasons;
    private final ChildFundingRepository fundings;
    private final FunderRepository funders;
    private final InvoiceRepository invoices;
    private final InvoiceLineRepository invoiceLines;
    private final CreditNoteRepository creditNotes;
    private final BillingSettingsService settings;
    private final ZoneId zone;

    public BillingMonthAssembler(StructureRepository structures, FamilyRepository families,
                                 GuardianRepository guardians, ChildRepository children, ContractRepository contracts,
                                 ContractVersionRepository versions, ChildAttendanceRepository attendances,
                                 ChildAbsenceRepository absences, AbsenceReasonRepository reasons,
                                 ChildFundingRepository fundings, FunderRepository funders, InvoiceRepository invoices,
                                 InvoiceLineRepository invoiceLines, CreditNoteRepository creditNotes,
                                 BillingSettingsService settings, Clock clock) {
        this.structures = structures;
        this.families = families;
        this.guardians = guardians;
        this.children = children;
        this.contracts = contracts;
        this.versions = versions;
        this.attendances = attendances;
        this.absences = absences;
        this.reasons = reasons;
        this.fundings = fundings;
        this.funders = funders;
        this.invoices = invoices;
        this.invoiceLines = invoiceLines;
        this.creditNotes = creditNotes;
        this.settings = settings;
        this.zone = clock.getZone();
    }

    @Transactional(readOnly = true)
    public List<FamilyMonth> assemble(UUID structureId, YearMonth month) {
        var first = month.atDay(1);
        var last = month.atEndOfMonth();
        var structure = structures.findById(structureId).orElseThrow();
        var rules = settings.requireRule(structureId, first).toRules();
        var tgcRates = settings.tgcRatesOn(structureId, first);
        var openDays = structure.getOpeningHours().stream().map(OpeningHours::dayOfWeek).collect(Collectors.toSet());
        var closingTimes = structure.getOpeningHours().stream()
                .collect(Collectors.toMap(OpeningHours::dayOfWeek, OpeningHours::closesAt, (a, b) -> a.isAfter(b) ? a : b));

        var childrenById = children.findByStructureId(structureId).stream()
                .filter(c -> c.getFamilyId() != null)
                .collect(Collectors.toMap(Child::getId, Function.identity()));

        // Contrats signés qui touchent le mois (accueil ou séances d'adaptation).
        var contractsByChild = new HashMap<UUID, List<Contract>>();
        var allContracts = contracts.findByStructureId(structureId);
        var versionsByContract = versions.findByContractIdIn(allContracts.stream().map(Contract::getId).toList()).stream()
                .filter(ContractVersion::isSigned)
                .collect(Collectors.groupingBy(ContractVersion::getContractId));
        for (var contract : allContracts) {
            boolean adaptation = contract.getAdaptationSessions().stream()
                    .anyMatch(s -> YearMonth.from(s.sessionDate()).equals(month));
            if (versionsByContract.containsKey(contract.getId()) && (contract.overlaps(first, last) || adaptation)) {
                contractsByChild.computeIfAbsent(contract.getChildId(), k -> new ArrayList<>()).add(contract);
            }
        }

        var passagesByChild = attendances.findByStructureIdAndDayBetween(structureId, first, last).stream()
                .collect(Collectors.groupingBy(ChildAttendance::getChildId));

        var involved = new HashSet<UUID>();
        involved.addAll(contractsByChild.keySet());
        involved.addAll(passagesByChild.keySet());
        involved.retainAll(childrenById.keySet());
        if (involved.isEmpty()) {
            return List.of();
        }

        // Absences jusqu'à un mois avant (carence d'un arrêt commencé le mois précédent).
        var reasonsById = reasons.findByStructureIdOrderByDisplayOrderAscLabelAsc(structureId).stream()
                .collect(Collectors.toMap(AbsenceReason::getId, Function.identity()));
        var absencesByChild = absences.overlapping(structureId, first.minusMonths(1), last).stream()
                .collect(Collectors.groupingBy(ChildAbsence::getChildId));
        var fundersById = funders.findByStructureIdOrderByPriorityAscNameAsc(structureId).stream()
                .collect(Collectors.toMap(Funder::getId, Function.identity()));
        var fundingsByChild = fundings.findByChildIdIn(involved).stream()
                .collect(Collectors.groupingBy(ChildFunding::getChildId));

        // Frais d'inscription déjà facturés (facture non annulée par un avoir total).
        var familyIds = involved.stream().map(id -> childrenById.get(id).getFamilyId()).collect(Collectors.toSet());
        var familyInvoices = invoices.findByFamilyIdIn(familyIds);
        var cancelled = creditNotes.findByInvoiceIdIn(familyInvoices.stream().map(Invoice::getId).toList()).stream()
                .filter(CreditNote::isFullCancel).map(CreditNote::getInvoiceId).collect(Collectors.toSet());
        var liveInvoices = familyInvoices.stream().filter(i -> !cancelled.contains(i.getId())).toList();
        var liveIds = liveInvoices.stream().map(Invoice::getId).collect(Collectors.toSet());
        Set<UUID> registrationBilled = invoiceLines.findByInvoiceIdIn(liveIds).stream()
                .filter(l -> l.getKind() == InvoiceCalculator.Kind.FRAIS_INSCRIPTION && l.getChildId() != null)
                .map(InvoiceLine::getChildId).collect(Collectors.toSet());
        Map<UUID, Invoice> invoiceOfMonth = liveInvoices.stream()
                .filter(i -> i.getMonth().equals(month))
                .collect(Collectors.toMap(Invoice::getFamilyId, Function.identity(), (a, b) -> a));

        var byFamily = involved.stream().map(childrenById::get)
                .sorted(Comparator.comparing(Child::getBirthDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.groupingBy(Child::getFamilyId, LinkedHashMap::new, Collectors.toList()));
        var familiesById = families.findAllById(byFamily.keySet()).stream()
                .collect(Collectors.toMap(Family::getId, Function.identity()));
        var guardiansByFamily = guardians.findByFamilyIdIn(byFamily.keySet()).stream()
                .sorted(Comparator.comparingInt(Guardian::getDisplayOrder))
                .collect(Collectors.groupingBy(Guardian::getFamilyId));

        var result = new ArrayList<FamilyMonth>();
        for (var entry : byFamily.entrySet()) {
            var family = familiesById.get(entry.getKey());
            var payer = guardiansByFamily.getOrDefault(family.getId(), List.of()).stream()
                    .filter(Guardian::isPayer).findFirst().orElse(null);
            var childInputs = new ArrayList<ChildInput>();
            var attendanceDays = new HashMap<UUID, Integer>();
            var beneficiaries = new HashMap<UUID, Map<UUID, String>>();
            var plannings = new ArrayList<String>();
            for (var child : entry.getValue()) {
                var childContracts = contractsByChild.getOrDefault(child.getId(), List.of()).stream()
                        .sorted(Comparator.comparing(Contract::getStartDate)).toList();
                var contractInputs = new ArrayList<ContractInput>();
                var adaptationDays = new HashSet<LocalDate>();
                for (var contract : childContracts) {
                    var signed = versionsByContract.get(contract.getId()).stream()
                            .sorted(Comparator.comparingInt(ContractVersion::getNumber)).toList();
                    contract.getAdaptationSessions().forEach(s -> adaptationDays.add(s.sessionDate()));
                    contractInputs.add(contractInput(contract, signed, month, registrationBilled.contains(child.getId())));
                }
                var covered = contractInputs.stream().flatMap(c -> c.versions().stream()).toList();
                if (!covered.isEmpty()) {
                    plannings.add((entry.getValue().size() > 1 ? child.getFirstName() + " : " : "")
                            + planning(covered.getLast()));
                }
                var passages = passagesByChild.getOrDefault(child.getId(), List.of()).stream()
                        // Passage d'une séance d'adaptation avant le contrat : facturé comme adaptation.
                        .filter(a -> !adaptationDays.contains(a.getDay())
                                || covered.stream().anyMatch(v -> !a.getDay().isBefore(v.from()) && !a.getDay().isAfter(v.to())))
                        .sorted(Comparator.comparing(ChildAttendance::getArrivedAt))
                        .map(a -> new Passage(a.getDay(), time(a.getArrivedAt()),
                                a.getDepartedAt() == null ? null : time(a.getDepartedAt())))
                        .toList();
                attendanceDays.put(child.getId(), (int) passages.stream().map(Passage::day).distinct().count());
                var childAbsences = absencesByChild.getOrDefault(child.getId(), List.of()).stream()
                        .map(a -> {
                            var reason = reasonsById.get(a.getReasonId());
                            return new InvoiceCalculator.Absence(a.getFromDay(), a.getToDay(), reason.getLabel(),
                                    reason.isDeductible(), reason.isProofRequired(), a.getDocumentId() != null,
                                    reason.isWaitingDaysApply(), reason.isNoticeApplies(), a.getNotifiedOn());
                        })
                        .toList();
                var childFundings = new ArrayList<InvoiceCalculator.Funding>();
                for (var funding : fundingsByChild.getOrDefault(child.getId(), List.of())) {
                    var funder = fundersById.get(funding.getFunderId());
                    if (funder == null || funder.getKind() != Funder.Kind.AIDE_FAMILLE) {
                        continue;
                    }
                    var from = max(funding.getFromDay(), funder.getValidFrom());
                    var to = min(funding.getToDay(), funder.getValidTo());
                    if (from.isAfter(last) || (to != null && to.isBefore(first))) {
                        continue;
                    }
                    childFundings.add(new InvoiceCalculator.Funding(funder.getId(), funder.getName(),
                            funder.getPriority(), funder.getCalculation(),
                            Objects.requireNonNullElse(funding.getAmountOverride(), funder.getAmount()),
                            Objects.requireNonNullElse(funding.getRateOverride(), funder.getRate()),
                            funder.getRateBase(), funder.getMonthlyCap(), from, to));
                    beneficiaries.computeIfAbsent(child.getId(), k -> new HashMap<>())
                            .put(funder.getId(), funding.getBeneficiary());
                }
                childInputs.add(new ChildInput(child.getId(), child.displayName(), contractInputs, passages,
                        childAbsences, childFundings));
            }
            var input = new MonthInput(month, openDays, closingTimes, rules, tgcRates, childInputs);
            result.add(new FamilyMonth(family, payer, entry.getValue(), input, attendanceDays, beneficiaries,
                    plannings.isEmpty() ? null : String.join(" · ", plannings), invoiceOfMonth.get(family.getId())));
        }
        result.sort(Comparator.comparing(f -> f.family().getLabel(), String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /** Versions signées ramenées aux jours qu'elles couvrent dans le mois (un avenant prend effet à sa date). */
    private static ContractInput contractInput(Contract contract, List<ContractVersion> signed, YearMonth month,
                                               boolean registrationBilled) {
        var first = month.atDay(1);
        var last = month.atEndOfMonth();
        var periods = new ArrayList<VersionPeriod>();
        for (int i = 0; i < signed.size(); i++) {
            var version = signed.get(i);
            var from = max(max(version.getEffectiveFrom(), contract.getStartDate()), first);
            var to = min(min(i + 1 < signed.size() ? signed.get(i + 1).getEffectiveFrom().minusDays(1) : null,
                    contract.getEndDate()), last);
            if (to == null || from.isAfter(to)) {
                continue;
            }
            var label = version.getNumber() == 0 ? "Contrat n° " + contract.getNumber()
                    : "Contrat n° %s, avenant %d".formatted(contract.getNumber(), version.getNumber());
            periods.add(new VersionPeriod(label, from, to,
                    version.getPlanningMode() == ContractVersion.PlanningMode.FIXE, version.getMonthlyFee(),
                    version.getHourlyRate(),
                    version.getDays().stream().map(d -> new DayPlan(d.dayOfWeek(), d.arrivesAt(), d.leavesAt())).toList(),
                    Objects.requireNonNullElse(version.getMonthlyReservedMinutes(), version.getMonthlyMinutes()),
                    version.getBillingMode()));
        }
        var sessions = contract.getAdaptationSessions().stream()
                .map(s -> new Session(s.sessionDate(), s.startsAt(), s.endsAt())).toList();
        var firstSession = sessions.stream().map(Session::date).min(Comparator.naturalOrder()).orElse(null);
        boolean adaptationStarts = firstSession != null && YearMonth.from(firstSession).equals(month);
        boolean startsThisMonth = YearMonth.from(contract.getStartDate()).equals(month);
        boolean firstInvoice = !registrationBilled && (startsThisMonth || adaptationStarts);
        long registrationFee = signed.isEmpty() ? 0 : signed.getFirst().getRegistrationFee();
        return new ContractInput(contract.getNumber(), periods, firstInvoice, registrationFee, sessions,
                AdaptationBilling.valueOf(contract.getAdaptationBilling().name()), contract.getAdaptationRate(),
                adaptationStarts);
    }

    /** « 5 jours par semaine, 07:00 à 17:00 » (maquette de la facture) ou « Planning variable, 80 h 00 par mois ». */
    static String planning(VersionPeriod version) {
        if (!version.fixed()) {
            return "Planning variable, %s réservées par mois".formatted(Formats.duration(version.monthlyReservedMinutes()));
        }
        int days = version.days().size();
        var hours = version.days().stream().map(d -> Formats.time(d.arrives()) + " à " + Formats.time(d.leaves()))
                .distinct().toList();
        return "%d jour%s par semaine%s".formatted(days, days > 1 ? "s" : "",
                hours.size() == 1 ? ", " + hours.getFirst() : "");
    }

    private LocalTime time(java.time.Instant instant) {
        return instant.atZone(zone).toLocalTime().withSecond(0).withNano(0);
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    /** Minimum de deux dates, {@code null} valant « sans fin ». */
    private static LocalDate min(LocalDate a, LocalDate b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }
}
