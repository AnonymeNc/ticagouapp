package nc.ticagou.enrollment;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.child.ChildRepository;
import nc.ticagou.common.ApiException;
import nc.ticagou.common.Formats;
import nc.ticagou.contract.ContractRepository;
import nc.ticagou.enrollment.CapacityDtos.TestResult;
import nc.ticagou.enrollment.EnrollmentDtos.BoardDto;
import nc.ticagou.enrollment.EnrollmentDtos.BoardStats;
import nc.ticagou.enrollment.EnrollmentDtos.ContractLink;
import nc.ticagou.enrollment.EnrollmentDtos.CriterionDto;
import nc.ticagou.enrollment.EnrollmentDtos.EventDto;
import nc.ticagou.enrollment.EnrollmentDtos.NoteDto;
import nc.ticagou.enrollment.EnrollmentDtos.NoteRequest;
import nc.ticagou.enrollment.EnrollmentDtos.Ranking;
import nc.ticagou.enrollment.EnrollmentDtos.RequestCard;
import nc.ticagou.enrollment.EnrollmentDtos.RequestForm;
import nc.ticagou.enrollment.EnrollmentDtos.RequestRecord;
import nc.ticagou.enrollment.EnrollmentDtos.Sibling;
import nc.ticagou.enrollment.EnrollmentDtos.StageRequest;
import nc.ticagou.enrollment.EnrollmentDtos.VisitRequest;
import nc.ticagou.enrollment.EnrollmentRequest.Stage;
import nc.ticagou.family.FamilyService;
import nc.ticagou.structure.SectionRepository;

/**
 * Demandes d'inscription : Nouvelle → À recontacter → Visite prévue → Place proposée → Contrat signé, ou Refusée.
 * L'étape « Contrat signé » n'est atteinte que par la signature du contrat ({@link #markSigned}).
 */
@Service
public class EnrollmentService {

    /** Les demandes closes depuis moins longtemps restent affichées sur le tableau. */
    static final int RECENTLY_CLOSED_DAYS = 30;

    private final EnrollmentRequestRepository requests;
    private final EnrollmentCriterionRepository criteria;
    private final EnrollmentNoteRepository notes;
    private final EnrollmentEventRepository events;
    private final SectionRepository sections;
    private final ChildRepository children;
    private final ContractRepository contracts;
    private final FamilyService families;
    private final CapacityService capacity;
    private final AuditService audit;
    private final Clock clock;

    public EnrollmentService(EnrollmentRequestRepository requests, EnrollmentCriterionRepository criteria,
                             EnrollmentNoteRepository notes, EnrollmentEventRepository events,
                             SectionRepository sections, ChildRepository children, ContractRepository contracts,
                             FamilyService families, CapacityService capacity, AuditService audit, Clock clock) {
        this.requests = requests;
        this.criteria = criteria;
        this.notes = notes;
        this.events = events;
        this.sections = sections;
        this.children = children;
        this.contracts = contracts;
        this.families = families;
        this.capacity = capacity;
        this.audit = audit;
        this.clock = clock;
    }

    // --- Lecture ---------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public BoardDto board(String query, UUID sectionId, YearMonth month, boolean includeClosed) {
        var structureId = CurrentUser.get().structureId();
        var today = LocalDate.now(clock);
        var all = requests.findByStructureId(structureId);
        var criteriaById = criteriaById(structureId);
        var snapshot = capacity.snapshot(capacity.currentMonth(), null);
        var needle = FamilyService.normalize(query);

        var cards = all.stream()
                .filter(r -> includeClosed || !r.getStage().isClosed()
                        || !r.getStageChangedOn().isBefore(today.minusDays(RECENTLY_CLOSED_DAYS)))
                .filter(r -> sectionId == null || sectionId.equals(r.getSectionId()))
                .filter(r -> month == null || YearMonth.from(r.getDesiredStart()).equals(month))
                .filter(r -> needle.isEmpty() || FamilyService.normalize(r.childName() + " "
                        + r.getGuardianFirstName() + " " + r.getGuardianLastName()).contains(needle))
                .sorted(Comparator.comparing(EnrollmentRequest::getReceivedOn).reversed())
                .map(r -> {
                    TestResult hint = r.getStage().isClosed() || r.getStage() == Stage.PLACE_PROPOSEE ? null
                            : TestResult.from(snapshot.test(r.effectiveBirthDate(), r.getSectionId(), r.getDays(),
                            r.getDesiredStart()));
                    return new RequestCard(r.getId(), r.getChildFirstName(), r.getChildLastName(), r.getBirthDate(),
                            r.getExpectedBirthDate(), r.getSectionId(), r.getDesiredStart(), r.getDays(), r.getStage(),
                            r.getReceivedOn(), r.getStageChangedOn(), r.getFollowUpOn(), r.getVisitAt(),
                            r.isVisitDone(), r.getOfferExpiresOn(), r.getRefusalReason(),
                            score(r, criteriaById, today), tags(r, criteriaById, today), hint);
                })
                .toList();
        return new BoardDto(stats(all, today), cards);
    }

    static BoardStats stats(List<EnrollmentRequest> all, LocalDate today) {
        var monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var nextMonday = monday.plusWeeks(1);
        var open = all.stream().filter(r -> !r.getStage().isClosed()).toList();
        int overdue = (int) open.stream()
                .filter(r -> r.getStage() == Stage.A_RECONTACTER && r.getFollowUpOn() != null
                        && r.getFollowUpOn().isBefore(today))
                .count();
        int visits = (int) open.stream()
                .filter(r -> r.getStage() == Stage.VISITE_PREVUE && r.getVisitAt() != null && !r.isVisitDone())
                .filter(r -> !r.getVisitAt().toLocalDate().isBefore(monday) && r.getVisitAt().toLocalDate().isBefore(nextMonday))
                .count();
        int awaiting = (int) open.stream().filter(r -> r.getStage() == Stage.PLACE_PROPOSEE).count();
        return new BoardStats(open.size(), overdue, visits, awaiting);
    }

    @Transactional(readOnly = true)
    public RequestRecord record(UUID id) {
        var r = load(id);
        var structureId = r.getStructureId();
        var today = LocalDate.now(clock);
        var criteriaById = criteriaById(structureId);
        var available = criteria.findByStructureIdOrderByDisplayOrderAscLabelAsc(structureId).stream()
                .filter(c -> c.isValidOn(today) || r.getCriteria().contains(c.getId()))
                .map(CriterionDto::from)
                .toList();

        String familyLabel = null;
        List<Sibling> siblings = List.of();
        if (r.getFamilyId() != null) {
            familyLabel = families.load(r.getFamilyId()).getLabel();
            siblings = children.findByFamilyIdOrderByBirthDateAsc(r.getFamilyId()).stream()
                    .filter(c -> !c.getId().equals(r.getChildId()))
                    .map(c -> new Sibling(c.getId(), c.getFirstName(), c.getLastName(), c.getBirthDate(),
                            c.getSectionId(), c.getEntryDate(), c.getExitDate()))
                    .toList();
        }
        TestResult hint = r.getStage().isClosed() ? null : TestResult.from(capacity.snapshot(capacity.currentMonth(), r.getId())
                .test(r.effectiveBirthDate(), r.getSectionId(), r.getDays(), r.getDesiredStart()));
        var links = contracts.findByRequestId(id).stream().map(c -> new ContractLink(c.getId(), c.getNumber())).toList();

        return new RequestRecord(r.getId(), r.getReceivedOn(), r.getChildFirstName(), r.getChildLastName(),
                r.getBirthDate(), r.getExpectedBirthDate(), r.getSectionId(), r.getDesiredStart(), r.getCareType(),
                r.getArrivesAt(), r.getLeavesAt(), r.getDays(), r.getGuardianFirstName(), r.getGuardianLastName(),
                r.getRelationship(), r.getPhone(), r.getEmail(), r.getFamilyId(), familyLabel, siblings, r.getStage(),
                r.getStageChangedOn(), r.getFollowUpOn(), r.getVisitAt(), r.isVisitDone(), r.getOfferExpiresOn(),
                r.getRefusalReason(), r.getChildId(), links, r.getCriteria(), available,
                score(r, criteriaById, today), ranking(r, criteriaById, today), hint,
                notes.findByRequestIdOrderByCreatedAtDesc(id).stream().map(NoteDto::from).toList(),
                events.findByRequestIdOrderByCreatedAtDesc(id).stream().map(EventDto::from).toList(),
                r.getVersion());
    }

    private Ranking ranking(EnrollmentRequest request, Map<UUID, EnrollmentCriterion> criteriaById, LocalDate today) {
        if (request.getStage().isClosed()) {
            return null;
        }
        var month = YearMonth.from(request.getDesiredStart());
        Map<UUID, Integer> scores = new HashMap<>();
        var peers = requests.findByStructureId(request.getStructureId()).stream()
                .filter(r -> !r.getStage().isClosed())
                .filter(r -> Objects.equals(r.getSectionId(), request.getSectionId()))
                .filter(r -> YearMonth.from(r.getDesiredStart()).equals(month))
                .peek(r -> scores.put(r.getId(), score(r, criteriaById, today)))
                .sorted(Comparator.comparing((EnrollmentRequest r) -> -scores.get(r.getId()))
                        .thenComparing(EnrollmentRequest::getReceivedOn)
                        .thenComparing(EnrollmentRequest::getId))
                .map(EnrollmentRequest::getId)
                .toList();
        return new Ranking(peers.indexOf(request.getId()) + 1, peers.size());
    }

    private Map<UUID, EnrollmentCriterion> criteriaById(UUID structureId) {
        return criteria.findByStructureIdOrderByDisplayOrderAscLabelAsc(structureId).stream()
                .collect(Collectors.toMap(EnrollmentCriterion::getId, Function.identity()));
    }

    /** Somme des points des critères cochés, au barème des critères en vigueur aujourd'hui. */
    static int score(EnrollmentRequest request, Map<UUID, EnrollmentCriterion> criteriaById, LocalDate today) {
        return request.getCriteria().stream().map(criteriaById::get)
                .filter(c -> c != null && c.isValidOn(today))
                .mapToInt(EnrollmentCriterion::getPoints).sum();
    }

    private static List<String> tags(EnrollmentRequest request, Map<UUID, EnrollmentCriterion> criteriaById,
                                     LocalDate today) {
        return request.getCriteria().stream().map(criteriaById::get)
                .filter(c -> c != null && c.isValidOn(today))
                .sorted(Comparator.comparingInt(EnrollmentCriterion::getDisplayOrder))
                .map(EnrollmentCriterion::getLabel)
                .toList();
    }

    // --- Saisie ----------------------------------------------------------------------------------------

    @Transactional
    public UUID create(RequestForm form) {
        var structureId = CurrentUser.get().structureId();
        var request = new EnrollmentRequest(structureId, LocalDate.now(clock));
        apply(request, form);
        requests.saveAndFlush(request);
        event(request, "Demande reçue au bureau");
        audit.record(AuditAction.ENROLLMENT_REQUEST_CREATED, "enrollment_request", request.getId(), Map.of());
        return request.getId();
    }

    @Transactional
    public void update(UUID id, RequestForm form) {
        var request = load(id);
        if (request.getStage() == Stage.SIGNEE) {
            throw ApiException.rule("request_closed", "Le contrat est signé : modifiez plutôt la fiche de l’enfant.");
        }
        apply(request, form);
        requests.saveAndFlush(request);
        audit.record(AuditAction.ENROLLMENT_REQUEST_UPDATED, "enrollment_request", id, Map.of());
    }

    @Transactional
    public void changeStage(UUID id, StageRequest change) {
        var request = load(id);
        var target = change.stage();
        var previous = request.getStage();
        if (previous == Stage.SIGNEE) {
            throw ApiException.rule("request_closed", "Le contrat de cette demande est déjà signé.");
        }
        if (target == Stage.SIGNEE) {
            throw ApiException.rule("stage_signed_by_contract",
                    "La demande passe en « Contrat signé » à la signature du contrat.");
        }
        if (target == Stage.VISITE_PREVUE && change.visitAt() == null && request.getVisitAt() == null) {
            throw ApiException.rule("visit_required", "Indiquez la date et l’heure de la visite.");
        }
        if (target == Stage.PLACE_PROPOSEE && request.getSectionId() == null) {
            throw ApiException.rule("section_required", "Choisissez la section prévue avant de proposer une place.");
        }
        if (target == Stage.REFUSEE && (change.reason() == null || change.reason().isBlank())) {
            throw ApiException.rule("reason_required", "Indiquez le motif du refus.");
        }
        var today = LocalDate.now(clock);
        request.moveTo(target, today, change.followUpOn(),
                change.visitAt() != null ? change.visitAt() : request.getVisitAt(), change.offerExpiresOn(),
                change.reason() == null ? null : change.reason().trim());
        requests.saveAndFlush(request);

        if (previous == Stage.REFUSEE) {
            event(request, "Demande rouverte, passée à « %s »".formatted(target.label()));
        } else if (target == Stage.REFUSEE) {
            event(request, "Refusée : " + request.getRefusalReason());
        } else if (target == Stage.VISITE_PREVUE) {
            event(request, "Visite fixée au %s à %s".formatted(Formats.date(request.getVisitAt().toLocalDate()),
                    Formats.time(request.getVisitAt().toLocalTime())));
        } else if (target == Stage.PLACE_PROPOSEE) {
            event(request, change.offerExpiresOn() == null ? "Place proposée"
                    : "Place proposée, réponse attendue avant le " + Formats.date(change.offerExpiresOn()));
        } else if (target == Stage.A_RECONTACTER && change.followUpOn() != null) {
            event(request, "Passée à « À recontacter », relance prévue le " + Formats.date(change.followUpOn()));
        } else if (target != previous) {
            event(request, "Passée à « %s »".formatted(target.label()));
        }
        audit.record(AuditAction.ENROLLMENT_STAGE_CHANGED, "enrollment_request", id,
                Map.of("from", previous.name(), "to", target.name()));
    }

    @Transactional
    public void moveVisit(UUID id, VisitRequest visit) {
        var request = load(id);
        if (request.getStage() != Stage.VISITE_PREVUE) {
            throw ApiException.rule("no_visit", "Aucune visite n’est prévue pour cette demande.");
        }
        changeStage(id, new StageRequest(Stage.VISITE_PREVUE, null, visit.visitAt(), null, null));
    }

    @Transactional
    public void markVisitDone(UUID id) {
        var request = load(id);
        if (request.getStage() != Stage.VISITE_PREVUE || request.getVisitAt() == null) {
            throw ApiException.rule("no_visit", "Aucune visite n’est prévue pour cette demande.");
        }
        request.markVisitDone();
        requests.saveAndFlush(request);
        event(request, "Visite faite");
        audit.record(AuditAction.ENROLLMENT_REQUEST_UPDATED, "enrollment_request", id, Map.of("visitDone", true));
    }

    @Transactional
    public NoteDto addNote(UUID id, NoteRequest note) {
        var request = load(id);
        var saved = notes.saveAndFlush(new EnrollmentNote(request.getStructureId(), id, note.body().trim(),
                CurrentUser.get().displayName(), clock.instant()));
        audit.record(AuditAction.ENROLLMENT_NOTE_ADDED, "enrollment_request", id, Map.of());
        return NoteDto.from(saved);
    }

    // --- Appelé par les contrats ------------------------------------------------------------------

    /** La fiche enfant (et sa famille) créée à partir de la demande. */
    @Transactional
    public void linkChild(UUID id, UUID childId, UUID familyId) {
        var request = load(id);
        request.linkChild(childId, familyId);
        requests.saveAndFlush(request);
    }

    @Transactional
    public void markSigned(UUID id, String contractNumber) {
        var request = load(id);
        if (request.getStage() == Stage.SIGNEE) {
            return;
        }
        var previous = request.getStage();
        request.moveTo(Stage.SIGNEE, LocalDate.now(clock), null, null, null, null);
        requests.saveAndFlush(request);
        event(request, "Contrat n° %s signé".formatted(contractNumber));
        audit.record(AuditAction.ENROLLMENT_STAGE_CHANGED, "enrollment_request", id,
                Map.of("from", previous.name(), "to", Stage.SIGNEE.name()));
    }

    public EnrollmentRequest load(UUID id) {
        return requests.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Demande introuvable."));
    }

    private void apply(EnrollmentRequest request, RequestForm f) {
        var structureId = request.getStructureId();
        if (f.sectionId() != null && sections.findByIdAndStructureId(f.sectionId(), structureId).isEmpty()) {
            throw ApiException.rule("unknown_section", "Cette section n’existe pas dans la structure.");
        }
        if (f.familyId() != null) {
            families.load(f.familyId());
        }
        Set<UUID> known = criteriaById(structureId).keySet();
        if (!known.containsAll(f.criteria())) {
            throw ApiException.rule("unknown_criterion", "Un critère de priorité n’existe pas dans la structure.");
        }
        request.update(new EnrollmentRequest.Details(f.childFirstName().trim(), f.childLastName().trim(),
                f.birthDate(), f.expectedBirthDate(), f.sectionId(), f.desiredStart(), f.careType(), f.arrivesAt(),
                f.leavesAt(), f.days(), f.guardianFirstName().trim(), f.guardianLastName().trim(),
                f.relationship().trim(), blankToNull(f.phone()), blankToNull(f.email()), f.familyId(), f.criteria()));
    }

    private void event(EnrollmentRequest request, String label) {
        events.saveAndFlush(new EnrollmentEvent(request.getStructureId(), request.getId(), label,
                CurrentUser.get().displayName(), clock.instant()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
