package nc.ticagou.contract;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.billing.BillingRule;
import nc.ticagou.billing.BillingRuleRepository;
import nc.ticagou.child.Child;
import nc.ticagou.child.ChildDocument;
import nc.ticagou.child.ChildDocumentService;
import nc.ticagou.child.ChildDtos.ChildIdentityRequest;
import nc.ticagou.child.ChildDtos.CreateChildRequest;
import nc.ticagou.child.ChildRepository;
import nc.ticagou.child.ChildService;
import nc.ticagou.common.ApiException;
import nc.ticagou.common.CounterService;
import nc.ticagou.common.Formats;
import nc.ticagou.contract.ContractDtos.AmendmentRequest;
import nc.ticagou.contract.ContractDtos.BandDto;
import nc.ticagou.contract.ContractDtos.ContractDraft;
import nc.ticagou.contract.ContractDtos.ContractDto;
import nc.ticagou.contract.ContractDtos.CreateContractRequest;
import nc.ticagou.contract.ContractDtos.DayDto;
import nc.ticagou.contract.ContractDtos.FormulaDto;
import nc.ticagou.contract.ContractDtos.PlanningInput;
import nc.ticagou.contract.ContractDtos.QuoteDto;
import nc.ticagou.contract.ContractDtos.QuoteRequest;
import nc.ticagou.contract.ContractDtos.SessionDto;
import nc.ticagou.contract.ContractDtos.SessionInput;
import nc.ticagou.contract.ContractDtos.State;
import nc.ticagou.contract.ContractDtos.VersionDto;
import nc.ticagou.contract.ContractVersion.PlanningMode;
import nc.ticagou.contract.ForfaitCalculator.AdaptationBilling;
import nc.ticagou.contract.ForfaitCalculator.OpeningWindow;
import nc.ticagou.enrollment.EnrollmentRequest;
import nc.ticagou.enrollment.EnrollmentService;
import nc.ticagou.funder.TariffCriterion;
import nc.ticagou.funder.TariffSchedule;
import nc.ticagou.funder.TariffService;
import nc.ticagou.structure.StructureRepository;

/**
 * Contrats d'accueil et avenants. Le contrat initial (version 0) et chaque avenant sont d'abord des brouillons
 * modifiables ; une fois signée (sur papier), une version ne change plus. Le forfait est calculé avec le
 * barème en vigueur à la date d'effet de la version, puis figé dans la version.
 */
@Service
public class ContractService {

    private final ContractRepository contracts;
    private final ContractVersionRepository versions;
    private final ChildRepository children;
    private final ChildService childService;
    private final ChildDocumentService documents;
    private final EnrollmentService enrollments;
    private final TariffService tariffs;
    private final StructureRepository structures;
    private final CounterService counters;
    private final AuditService audit;
    private final BillingRuleRepository billingRules;
    private final Clock clock;

    public ContractService(ContractRepository contracts, ContractVersionRepository versions, ChildRepository children,
                           ChildService childService, ChildDocumentService documents, EnrollmentService enrollments,
                           TariffService tariffs, StructureRepository structures, CounterService counters,
                           AuditService audit, BillingRuleRepository billingRules, Clock clock) {
        this.billingRules = billingRules;
        this.contracts = contracts;
        this.versions = versions;
        this.children = children;
        this.childService = childService;
        this.documents = documents;
        this.enrollments = enrollments;
        this.tariffs = tariffs;
        this.structures = structures;
        this.counters = counters;
        this.audit = audit;
        this.clock = clock;
    }

    // --- Calcul ------------------------------------------------------------------------------------

    /** Aperçu du forfait et du coût de l'adaptation pendant la saisie. */
    @Transactional(readOnly = true)
    public QuoteDto quote(QuoteRequest request) {
        var tariff = tariffs.requireApplicable(request.effectiveFrom());
        var problems = ForfaitCalculator.checkSlots(slots(request.planning()), openingWindows());
        var pricing = pricing(tariff, request.planning());
        // Forfait calculé à l'heure, pour le comparer aux formules du barème.
        var asked = request.planning();
        long hourlyFee = asked.formulaLabel() == null ? pricing.monthlyFee()
                : pricing(tariff, new PlanningInput(asked.mode(), asked.days(), asked.monthlyReservedMinutes(), asked.planningNoticeDays(),
                        asked.familyQuotient(), asked.billingMode(), null)).monthlyFee();
        var sessions = request.adaptation() == null ? List.<AdaptationSession>of()
                : request.adaptation().stream().map(SessionInput::toSession).toList();
        var billing = request.adaptationBilling() == null ? AdaptationBilling.AU_TEMPS : request.adaptationBilling();
        long rate = ForfaitCalculator.adaptationRate(tariff.tariff(), request.planning().familyQuotient());
        var adaptation = sessions.stream().map(AdaptationSession::toSession).toList();
        var allProblems = new ArrayList<>(problems);
        for (var s : sessions) {
            ForfaitCalculator.checkSlot(s.sessionDate().getDayOfWeek().getValue(), s.startsAt(), s.endsAt(), openingWindows())
                    .ifPresent(p -> allProblems.add("Adaptation du " + Formats.date(s.sessionDate()) + " — " + p));
        }
        return new QuoteDto(tariff.getId(), tariff.getLabel(), tariff.getCriterion(), tariff.getWeeksPerYear(),
                tariff.getBands().stream().map(b -> new BandDto(b.lowerBound(), b.hourlyRate())).toList(),
                pricing.bandLowerBound(), pricing.hourlyRate(), pricing.weeklyMinutes(), pricing.monthlyMinutes(),
                pricing.monthlyFee(), pricing.registrationFee(), ForfaitCalculator.adaptationMinutes(adaptation), rate,
                ForfaitCalculator.adaptationCost(adaptation, billing, rate),
                billingMode(request.planning(), request.effectiveFrom()), pricing.formulaLabel(), hourlyFee,
                tariff.getFormulas().stream().map(f -> new FormulaDto(f.label(), f.monthlyFee())).toList(), allProblems);
    }

    /** Mode choisi pour le contrat, sinon celui des règles de facturation en vigueur à la date d'effet. */
    private BillingMode billingMode(PlanningInput planning, LocalDate day) {
        if (planning.formulaLabel() != null) {
            // Une formule est un forfait mensuel : le même montant chaque mois.
            return BillingMode.MENSUALISE;
        }
        if (planning.billingMode() != null) {
            return planning.billingMode();
        }
        return billingRules.findByStructureIdOrderByValidFromDesc(CurrentUser.get().structureId()).stream()
                .filter(r -> r.isValidOn(day)).findFirst().map(BillingRule::getDefaultBillingMode)
                .orElse(BillingMode.MENSUALISE);
    }

    private ContractVersion.Pricing pricing(TariffSchedule tariff, PlanningInput planning) {
        if (tariff.getCriterion() == TariffCriterion.QUOTIENT_FAMILIAL && planning.familyQuotient() == null) {
            throw ApiException.rule("quotient_required",
                    "Le barème « %s » dépend du quotient familial : indiquez celui de la famille.".formatted(tariff.getLabel()));
        }
        var quote = planning.mode() == PlanningMode.FIXE
                ? ForfaitCalculator.fixed(slots(planning), tariff.tariff(), planning.familyQuotient())
                : ForfaitCalculator.variable(planning.monthlyReservedMinutes(), tariff.tariff(), planning.familyQuotient());
        // Formule choisie : son montant remplace le forfait calculé ; la tranche garde le tarif horaire des heures
        // complémentaires et de l'adaptation.
        var formulaLabel = planning.formulaLabel();
        long monthlyFee = quote.monthlyFee();
        if (formulaLabel != null) {
            monthlyFee = tariff.formula(formulaLabel).orElseThrow(() -> ApiException.rule("formula_unknown",
                    "Le barème « %s » ne propose pas la formule « %s ».".formatted(tariff.getLabel(), formulaLabel)))
                    .monthlyFee();
        }
        return new ContractVersion.Pricing(tariff.getId(), tariff.getCriterion(), quote.bandLowerBound(),
                quote.hourlyRate(), tariff.getWeeksPerYear(), quote.weeklyMinutes(), quote.monthlyMinutes(),
                monthlyFee, tariff.getRegistrationFee(), formulaLabel);
    }

    private static List<ForfaitCalculator.Slot> slots(PlanningInput planning) {
        return planning.scheduleDays().stream().map(ScheduleDay::toSlot).toList();
    }

    private Map<Integer, OpeningWindow> openingWindows() {
        var structure = structures.findById(CurrentUser.get().structureId()).orElseThrow();
        return structure.getOpeningHours().stream().collect(Collectors.toMap(
                h -> h.dayOfWeek(), h -> new OpeningWindow(h.opensAt(), h.closesAt())));
    }

    /** Planning prêt à enregistrer : toute incohérence bloque. */
    private void requireValidPlanning(PlanningInput planning) {
        var problems = ForfaitCalculator.checkSlots(slots(planning), openingWindows());
        if (!problems.isEmpty()) {
            throw ApiException.rule("planning_invalid", String.join(" ", problems));
        }
    }

    // --- Contrat initial -------------------------------------------------------------------------

    @Transactional
    public UUID create(CreateContractRequest request) {
        var structureId = CurrentUser.get().structureId();
        var draft = request.contract();
        EnrollmentRequest enrollment = null;
        if (request.requestId() != null) {
            enrollment = enrollments.load(request.requestId());
            if (enrollment.getStage().isClosed()) {
                throw ApiException.rule("request_closed", "Cette demande est close : rouvrez-la avant d’établir un contrat.");
            }
            if (!contracts.findByRequestId(enrollment.getId()).isEmpty()) {
                throw ApiException.conflict("contract_exists", "Un contrat a déjà été établi pour cette demande.");
            }
        }
        Child child;
        if (request.childId() != null) {
            child = childService.load(request.childId());
        } else {
            child = childService.load(childService.create(withEntryDate(request.newChild(), draft.startDate())));
        }
        if (enrollment != null) {
            enrollments.linkChild(enrollment.getId(), child.getId(), child.getFamilyId());
        }
        checkNoOverlap(child.getId(), null, draft.startDate(), draft.endDate());

        int year = draft.startDate().getYear();
        var number = "%d-%03d".formatted(year, counters.next(structureId, "contract", year));
        var contract = new Contract(structureId, child.getId(), enrollment == null ? null : enrollment.getId(), number);
        var initial = applyDraft(contract, null, draft);
        audit.record(AuditAction.CONTRACT_CREATED, "contract", contract.getId(),
                Map.of("number", number, "child", child.getId(), "monthlyFee", initial.getMonthlyFee()));
        return contract.getId();
    }

    /** La date d'entrée de l'enfant créé depuis une demande est le début du contrat. */
    private static CreateChildRequest withEntryDate(CreateChildRequest request, LocalDate start) {
        var c = request.child();
        var identity = new ChildIdentityRequest(c.firstName(), c.lastName(), c.birthDate(),
                c.sectionId(), c.entryDate() != null ? c.entryDate() : start, c.exitDate(), c.homeLanguages());
        return new CreateChildRequest(identity, request.familyId(), request.family());
    }

    @Transactional
    public void updateDraft(UUID id, ContractDraft draft) {
        var contract = load(id);
        var initial = version(contract, 0);
        if (initial.isSigned()) {
            throw ApiException.rule("contract_signed", "Le contrat est signé : toute modification passe par un avenant.");
        }
        checkNoOverlap(contract.getChildId(), contract.getId(), draft.startDate(), draft.endDate());
        applyDraft(contract, initial, draft);
        audit.record(AuditAction.CONTRACT_UPDATED, "contract", id, Map.of("monthlyFee", initial.getMonthlyFee()));
    }

    private ContractVersion applyDraft(Contract contract, ContractVersion initial, ContractDraft draft) {
        var planning = draft.planning();
        requireValidPlanning(planning);
        var tariff = tariffs.requireApplicable(draft.startDate());
        var pricing = pricing(tariff, planning);
        var sessions = draft.adaptation().stream().map(SessionInput::toSession).toList();
        var windows = openingWindows();
        for (var s : sessions) {
            ForfaitCalculator.checkSlot(s.sessionDate().getDayOfWeek().getValue(), s.startsAt(), s.endsAt(), windows)
                    .ifPresent(p -> {
                        throw ApiException.rule("planning_invalid", "Adaptation du " + Formats.date(s.sessionDate()) + " — " + p);
                    });
        }
        long rate = ForfaitCalculator.adaptationRate(tariff.tariff(), planning.familyQuotient());
        long cost = ForfaitCalculator.adaptationCost(sessions.stream().map(AdaptationSession::toSession).toList(),
                draft.adaptationBilling(), rate);
        contract.update(draft.startDate(), draft.endDate(), draft.noticeMonths(), draft.adaptationBilling(), rate, cost,
                sessions);
        contracts.saveAndFlush(contract);

        var version = initial != null ? initial : new ContractVersion(contract.getStructureId(), contract.getId(), 0);
        version.update(new ContractVersion.Planning(draft.startDate(), null, planning.mode(),
                billingMode(planning, draft.startDate()), planning.scheduleDays(),
                planning.monthlyReservedMinutes(), planning.planningNoticeDays(), planning.familyQuotient()), pricing);
        return versions.saveAndFlush(version);
    }

    private void checkNoOverlap(UUID childId, UUID contractId, LocalDate start, LocalDate end) {
        contracts.findByChildIdOrderByStartDateDesc(childId).stream()
                .filter(c -> !c.getId().equals(contractId))
                .filter(c -> c.overlaps(start, end))
                .findFirst()
                .ifPresent(c -> {
                    throw ApiException.rule("contract_overlap",
                            "Cette période chevauche le contrat n° %s (du %s au %s).".formatted(
                                    c.getNumber(), Formats.date(c.getStartDate()), Formats.date(c.getEndDate())));
                });
    }

    // --- Avenants ----------------------------------------------------------------------------------

    @Transactional
    public int amend(UUID id, AmendmentRequest request) {
        var contract = load(id);
        var all = versions.findByContractIdOrderByNumberAsc(id);
        if (all.stream().anyMatch(v -> !v.isSigned())) {
            throw ApiException.rule("draft_pending",
                    "Un brouillon est déjà en cours : signez-le ou supprimez-le avant d’en créer un autre.");
        }
        var number = all.getLast().getNumber() + 1;
        var version = new ContractVersion(contract.getStructureId(), id, number);
        applyAmendment(contract, version, all, request);
        audit.record(AuditAction.CONTRACT_AMENDMENT_CREATED, "contract", id,
                Map.of("version", number, "monthlyFee", version.getMonthlyFee()));
        return number;
    }

    @Transactional
    public void updateAmendment(UUID id, int number, AmendmentRequest request) {
        var contract = load(id);
        var version = version(contract, number);
        if (number == 0 || version.isSigned()) {
            throw ApiException.rule("version_signed", "Seul un avenant en brouillon peut être modifié.");
        }
        var previous = versions.findByContractIdOrderByNumberAsc(id).stream().filter(v -> v.getNumber() < number).toList();
        applyAmendment(contract, version, previous, request);
        audit.record(AuditAction.CONTRACT_UPDATED, "contract", id,
                Map.of("version", number, "monthlyFee", version.getMonthlyFee()));
    }

    private void applyAmendment(Contract contract, ContractVersion version, List<ContractVersion> previous,
                                AmendmentRequest request) {
        var signed = previous.stream().filter(ContractVersion::isSigned).toList();
        if (signed.isEmpty() || !signed.getFirst().isSigned() || signed.getFirst().getNumber() != 0) {
            throw ApiException.rule("contract_not_signed", "Signez d’abord le contrat initial ; modifiez-le tant qu’il est en brouillon.");
        }
        var last = signed.getLast();
        if (!request.effectiveFrom().isAfter(last.getEffectiveFrom())) {
            throw ApiException.rule("amendment_date", "L’avenant doit prendre effet après le %s (date d’effet de la version en vigueur)."
                    .formatted(Formats.date(last.getEffectiveFrom())));
        }
        if (request.effectiveFrom().isAfter(contract.getEndDate())) {
            throw ApiException.rule("amendment_date", "L’avenant doit prendre effet avant la fin du contrat, le %s."
                    .formatted(Formats.date(contract.getEndDate())));
        }
        var planning = request.planning();
        requireValidPlanning(planning);
        var quotient = planning.familyQuotient() != null ? planning.familyQuotient() : last.getFamilyQuotient();
        var withQuotient = new PlanningInput(planning.mode(), planning.days(), planning.monthlyReservedMinutes(),
                planning.planningNoticeDays(), quotient, planning.billingMode(), planning.formula());
        var pricing = pricing(tariffs.requireApplicable(request.effectiveFrom()), withQuotient);
        version.update(new ContractVersion.Planning(request.effectiveFrom(), request.reason().trim(), planning.mode(),
                billingMode(planning, request.effectiveFrom()),
                withQuotient.scheduleDays(), planning.monthlyReservedMinutes(), planning.planningNoticeDays(), quotient),
                // Les frais d'inscription ne concernent que le contrat initial.
                new ContractVersion.Pricing(pricing.tariffScheduleId(), pricing.criterion(), pricing.bandLowerBound(),
                        pricing.hourlyRate(), pricing.weeksPerYear(), pricing.weeklyMinutes(), pricing.monthlyMinutes(),
                        pricing.monthlyFee(), 0, pricing.formulaLabel()));
        versions.saveAndFlush(version);
    }

    // --- Signature, suppression ----------------------------------------------------------------

    /** Signature sur papier : date de signature et, si disponible, exemplaire signé numérisé. */
    @Transactional
    public void sign(UUID id, int number, LocalDate signedOn, byte[] scan, String scanName) {
        var contract = load(id);
        var version = version(contract, number);
        if (version.isSigned()) {
            throw ApiException.conflict("version_signed", "Cette version est déjà signée.");
        }
        if (signedOn.isAfter(LocalDate.now(clock))) {
            throw ApiException.rule("signed_in_future", "La date de signature ne peut pas être dans le futur.");
        }
        if (number > 0 && !version(contract, 0).isSigned()) {
            throw ApiException.rule("contract_not_signed", "Signez d’abord le contrat initial.");
        }
        UUID documentId = null;
        if (scan != null && scan.length > 0) {
            var label = number == 0 ? "Contrat n° %s signé".formatted(contract.getNumber())
                    : "Avenant %d au contrat n° %s signé".formatted(number, contract.getNumber());
            documentId = documents.add(contract.getChildId(), scan, scanName, label, ChildDocument.Category.CONTRAT, null).id();
        }
        version.sign(signedOn, documentId);
        versions.saveAndFlush(version);

        if (number == 0) {
            var child = childService.load(contract.getChildId());
            if (child.getEntryDate() == null) {
                child.update(child.getFirstName(), child.getLastName(), child.getBirthDate(), child.getSectionId(),
                        contract.getStartDate(), child.getExitDate(), child.getHomeLanguages());
                children.saveAndFlush(child);
            }
            if (contract.getRequestId() != null) {
                enrollments.markSigned(contract.getRequestId(), contract.getNumber());
            }
        }
        audit.record(AuditAction.CONTRACT_SIGNED, "contract", id,
                Map.of("version", number, "scan", documentId != null));
    }

    /** Supprime un brouillon : l'avenant, ou le contrat entier tant que le contrat initial n'est pas signé. */
    @Transactional
    public void deleteDraft(UUID id, int number) {
        var contract = load(id);
        var version = version(contract, number);
        if (version.isSigned()) {
            throw ApiException.rule("version_signed", "Une version signée ne peut pas être supprimée.");
        }
        versions.delete(version);
        versions.flush();
        if (number == 0) {
            contracts.delete(contract);
            contracts.flush();
            audit.record(AuditAction.CONTRACT_DELETED, "contract", id, Map.of("number", contract.getNumber()));
        } else {
            audit.record(AuditAction.CONTRACT_AMENDMENT_DELETED, "contract", id, Map.of("version", number));
        }
    }

    // --- Lecture ------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ContractDto detail(UUID id) {
        return toDto(load(id));
    }

    @Transactional(readOnly = true)
    public List<ContractDto> forChild(UUID childId) {
        childService.load(childId);
        return contracts.findByChildIdOrderByStartDateDesc(childId).stream().map(this::toDto).toList();
    }

    ContractDto toDto(Contract contract) {
        var child = children.findById(contract.getChildId()).orElseThrow();
        var all = versions.findByContractIdOrderByNumberAsc(contract.getId());
        var today = LocalDate.now(clock);
        var signed = all.stream().filter(ContractVersion::isSigned)
                .sorted(Comparator.comparing(ContractVersion::getEffectiveFrom)).toList();
        // Version en vigueur : la dernière signée déjà effective, ou la première à venir.
        var inForce = signed.stream().filter(v -> !v.getEffectiveFrom().isAfter(today)).reduce((a, b) -> b)
                .or(() -> signed.stream().findFirst()).orElse(null);
        var versionDtos = all.stream().map(v -> new VersionDto(v.getNumber(), v.getEffectiveFrom(), v.getReason(),
                v.getPlanningMode(), v.getBillingMode(), v.getDays().stream().map(DayDto::from).toList(), v.getMonthlyReservedMinutes(),
                v.getPlanningNoticeDays(), v.getFamilyQuotient(), v.getStatus(), v.getSignedOn(), v.getSignedDocumentId(),
                v.getTariffScheduleId(), v.getTariffCriterion(), v.getBandLowerBound(), v.getHourlyRate(),
                v.getWeeksPerYear(), v.getWeeklyMinutes(), v.getMonthlyMinutes(), v.getMonthlyFee(),
                v.getRegistrationFee(), v.getFormulaLabel(), v == inForce,
                v.isSigned() && inForce != null && v.getEffectiveFrom().isBefore(inForce.getEffectiveFrom()))).toList();
        return new ContractDto(contract.getId(), contract.getNumber(), child.getId(), child.getFirstName(),
                child.getLastName(), child.getBirthDate(), child.getSectionId(), contract.getRequestId(),
                contract.getStartDate(), contract.getEndDate(), contract.getNoticeMonths(),
                contract.getAdaptationBilling(), contract.getAdaptationRate(), contract.getAdaptationCost(),
                contract.getAdaptationSessions().stream().map(SessionDto::from).toList(),
                state(contract, all, child, today), versionDtos, contract.getVersion());
    }

    static State state(Contract contract, List<ContractVersion> all, Child child, LocalDate today) {
        if (all.isEmpty() || !all.getFirst().isSigned()) {
            return State.BROUILLON;
        }
        var end = child.getExitDate() != null && child.getExitDate().isBefore(contract.getEndDate())
                ? child.getExitDate() : contract.getEndDate();
        if (today.isBefore(contract.getStartDate())) {
            return State.A_VENIR;
        }
        return today.isAfter(end) ? State.TERMINE : State.EN_COURS;
    }

    Contract load(UUID id) {
        return contracts.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Contrat introuvable."));
    }

    ContractVersion version(Contract contract, int number) {
        return versions.findByContractIdAndNumber(contract.getId(), number)
                .orElseThrow(() -> ApiException.notFound("Version du contrat introuvable."));
    }
}
