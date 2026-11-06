package nc.ticagou.attendance;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.attendance.AttendanceCorrection.Target;
import nc.ticagou.attendance.AttendanceDtos.InstructionInput;
import nc.ticagou.attendance.AttendanceDtos.OverrideInput;
import nc.ticagou.attendance.AttendanceDtos.PickupInput;
import nc.ticagou.attendance.PickupRules.Candidate;
import nc.ticagou.attendance.PickupRules.Kind;
import nc.ticagou.audit.AuditAction;
import nc.ticagou.portal.PortalChange;
import nc.ticagou.portal.PortalItemType;
import nc.ticagou.audit.AuditService;
import nc.ticagou.audit.AuditService.Actor;
import nc.ticagou.audit.ChildAccessContext;
import nc.ticagou.audit.ChildAccessLogger;
import nc.ticagou.auth.AppUser;
import nc.ticagou.auth.Role;
import nc.ticagou.child.AuthorizedPerson;
import nc.ticagou.child.AuthorizedPersonRepository;
import nc.ticagou.child.Child;
import nc.ticagou.child.ChildRepository;
import nc.ticagou.common.ApiException;
import nc.ticagou.common.Formats;
import nc.ticagou.config.TiCagouProperties;
import nc.ticagou.family.Guardian;
import nc.ticagou.family.GuardianRepository;
import nc.ticagou.meal.ClosedPeriods;
import nc.ticagou.staffing.StaffingChanged;
import nc.ticagou.structure.SectionRepository;

/**
 * Règles du pointage, communes à la tablette (en direct ou à la resynchronisation) et au poste bureau.
 * <ul>
 *   <li>un seul passage ouvert par enfant et par jour ;</li>
 *   <li>départ uniquement avec un responsable légal ou une personne autorisée, sinon validation tracée
 *       de la direction ;</li>
 *   <li>corrections réservées à la direction, avec motif, dans un journal en ajout seul.</li>
 * </ul>
 * Chaque modification publie {@link StaffingChanged} pour le taux d'encadrement en temps réel.
 */
@Service
public class AttendanceService {

    /** Auteur d'une opération : utilisateur connecté au bureau, ou identifié sur la tablette. */
    public record Author(UUID userId, String name, Role role) {

        public static Author of(AppUser user) {
            return new Author(user.getId(), user.displayName(), user.getRole());
        }

        Actor actor(UUID structureId) {
            return new Actor(structureId, userId, name);
        }
    }

    /** Validation de la direction obtenue pour un départ hors liste. */
    public record Validation(UUID directorId, String directorName, boolean pinVerified) {
    }

    private final ChildAttendanceRepository attendances;
    private final ChildInstructionRepository instructions;
    private final DepartureOverrideRepository overrides;
    private final StaffAttendanceRepository staffAttendances;
    private final AttendanceCorrectionRepository corrections;
    private final ChildRepository children;
    private final GuardianRepository guardians;
    private final AuthorizedPersonRepository authorizedPersons;
    private final SectionRepository sections;
    private final PlannedDays plannedDays;
    private final AuditService audit;
    private final ChildAccessLogger accessLogger;
    private final ApplicationEventPublisher events;
    private final ClosedPeriods closedPeriods;
    private final ZoneId zone;
    private final Clock clock;

    public AttendanceService(ChildAttendanceRepository attendances, ChildInstructionRepository instructions,
                             DepartureOverrideRepository overrides, StaffAttendanceRepository staffAttendances,
                             AttendanceCorrectionRepository corrections, ChildRepository children,
                             GuardianRepository guardians, AuthorizedPersonRepository authorizedPersons,
                             SectionRepository sections, PlannedDays plannedDays, AuditService audit,
                             ChildAccessLogger accessLogger, ApplicationEventPublisher events,
                             ClosedPeriods closedPeriods, TiCagouProperties properties, Clock clock) {
        this.closedPeriods = closedPeriods;
        this.attendances = attendances;
        this.instructions = instructions;
        this.overrides = overrides;
        this.staffAttendances = staffAttendances;
        this.corrections = corrections;
        this.children = children;
        this.guardians = guardians;
        this.authorizedPersons = authorizedPersons;
        this.sections = sections;
        this.plannedDays = plannedDays;
        this.audit = audit;
        this.accessLogger = accessLogger;
        this.events = events;
        this.zone = properties.zone();
        this.clock = clock;
    }

    public LocalDate dayOf(Instant at) {
        return at.atZone(zone).toLocalDate();
    }

    // --- Enfants --------------------------------------------------------------------------

    /** Arrivée ; {@code already_present} (409) si l'enfant a déjà un passage ouvert ce jour-là. */
    @Transactional
    public ChildAttendance arrive(UUID structureId, UUID attendanceId, UUID childId, UUID sectionId, Instant at,
                                  Author author, ChildAttendance.Source source) {
        var child = loadChild(structureId, childId);
        var day = dayOf(at);
        closedPeriods.requireOpen(structureId, day);
        if (!child.isEnrolledOn(day)) {
            throw ApiException.rule("child_not_enrolled",
                    "%s n’est pas inscrit(e) le %s.".formatted(child.displayName(), Formats.date(day)));
        }
        var open = attendances.findByChildIdAndDayAndDepartedAtIsNull(childId, day);
        if (open.isPresent()) {
            throw ApiException.conflict("already_present", "%s est déjà présent(e) depuis %s."
                    .formatted(child.getFirstName(), Formats.time(open.get().getArrivedAt().atZone(zone).toLocalTime())));
        }
        var section = sectionId != null ? checkSection(structureId, sectionId) : child.getSectionId();
        var planned = plannedDays.forDay(structureId, day).get(childId);
        var attendance = attendances.saveAndFlush(new ChildAttendance(
                attendanceId != null ? attendanceId : UUID.randomUUID(), structureId, childId, day, section, at,
                planned == null, author.name(), source));
        accessLogger.log(structureId, childId, author.userId(), author.name(), ChildAccessContext.POINTAGE);
        audit.record(author.actor(structureId), AuditAction.CHILD_ARRIVAL, "child_attendance", attendance.getId(),
                Map.of("child", child.displayName(), "at", at.toString(), "source", source.name()));
        events.publishEvent(new StaffingChanged(structureId));
        events.publishEvent(new PortalChange(structureId, PortalItemType.ATTENDANCE, attendance.getId()));
        return attendance;
    }

    /** Annulation d'une arrivée pointée par erreur (avant le départ). */
    @Transactional
    public void cancelArrival(UUID structureId, UUID attendanceId, Author author) {
        var attendance = loadAttendance(structureId, attendanceId);
        closedPeriods.requireOpen(structureId, attendance.getDay());
        if (!attendance.isPresent()) {
            throw ApiException.rule("already_departed", "Le départ est déjà enregistré : demandez une correction à la direction.");
        }
        attendances.delete(attendance);
        audit.record(author.actor(structureId), AuditAction.CHILD_ARRIVAL_CANCELLED, "child_attendance",
                attendanceId, Map.of("childId", attendance.getChildId().toString()));
        events.publishEvent(new StaffingChanged(structureId));
        events.publishEvent(new PortalChange(structureId, PortalItemType.ATTENDANCE, attendanceId));
    }

    /** « Déposé par » et consignes du parent ; une prise de médicament déjà notée est conservée. */
    @Transactional
    public void setInstructions(UUID structureId, UUID attendanceId, String droppedOffBy,
                                List<InstructionInput> inputs, Author author) {
        var attendance = loadAttendance(structureId, attendanceId);
        attendance.setDroppedOffBy(blankToNull(droppedOffBy));
        var existing = instructions.findByAttendanceIdInOrderByCreatedAtAsc(List.of(attendanceId));
        var kept = new ArrayList<UUID>();
        for (var instruction : existing) {
            if (instruction.getGivenAt() != null) {
                kept.add(instruction.getId());
            } else {
                instructions.delete(instruction);
            }
        }
        instructions.flush();
        var now = clock.instant();
        for (var input : inputs == null ? List.<InstructionInput>of() : inputs) {
            if (kept.contains(input.id())) {
                continue;
            }
            if (input.kind() == ChildInstruction.Kind.MEDICAMENT && input.medicationAt() == null) {
                throw ApiException.rule("medication_time_required", "Indiquez l’heure de la prise du médicament.");
            }
            instructions.save(new ChildInstruction(input.id(), structureId, attendanceId, input.kind(),
                    blankToNull(input.details()), input.kind() == ChildInstruction.Kind.MEDICAMENT ? input.medicationAt() : null,
                    input.kind() == ChildInstruction.Kind.MEDICAMENT ? blankToNull(input.medicationDose()) : null,
                    input.kind() == ChildInstruction.Kind.MEDICAMENT && input.prescriptionGiven(), now));
        }
        attendances.save(attendance);
    }

    @Transactional
    public void medicationGiven(UUID structureId, UUID instructionId, Instant at, Author author) {
        var instruction = instructions.findById(instructionId)
                .filter(i -> i.getKind() == ChildInstruction.Kind.MEDICAMENT)
                .orElseThrow(() -> ApiException.notFound("Médicament introuvable."));
        loadAttendance(structureId, instruction.getAttendanceId());
        if (instruction.getGivenAt() == null) {
            instruction.given(at, author.name());
            instructions.save(instruction);
        }
    }

    /** Liste des personnes pouvant venir chercher l'enfant (responsables légaux, personnes autorisées). */
    @Transactional(readOnly = true)
    public List<Candidate> pickupList(Child child) {
        return pickupList(guardians.findByFamilyIdOrderByDisplayOrderAscLastNameAsc(child.getFamilyId()),
                authorizedPersons.findByChildIdOrderByLastNameAscFirstNameAsc(child.getId()));
    }

    /** Listes de départ de plusieurs enfants en deux requêtes (journée de la tablette). */
    public Map<UUID, List<Candidate>> pickupLists(Collection<Child> children) {
        var byFamily = guardians.findByFamilyIdIn(children.stream().map(Child::getFamilyId).distinct().toList()).stream()
                .sorted(Comparator.comparingInt(Guardian::getDisplayOrder).thenComparing(Guardian::getLastName))
                .collect(Collectors.groupingBy(Guardian::getFamilyId));
        var byChild = authorizedPersons.findByChildIdIn(children.stream().map(Child::getId).toList()).stream()
                .sorted(Comparator.comparing(AuthorizedPerson::getLastName).thenComparing(AuthorizedPerson::getFirstName))
                .collect(Collectors.groupingBy(AuthorizedPerson::getChildId));
        var result = new HashMap<UUID, List<Candidate>>();
        for (var child : children) {
            result.put(child.getId(), pickupList(byFamily.getOrDefault(child.getFamilyId(), List.of()),
                    byChild.getOrDefault(child.getId(), List.of())));
        }
        return result;
    }

    private static List<Candidate> pickupList(List<Guardian> familyGuardians, List<AuthorizedPerson> authorized) {
        var family = familyGuardians.stream()
                .map(g -> new Candidate(Kind.RESPONSABLE, g.getId(), g.getFirstName() + " " + g.getLastName(),
                        g.getRelationship(), g.getMobilePhone(), g.isLegalGuardian()))
                .toList();
        var others = authorized.stream()
                .map(p -> new Candidate(Kind.AUTORISEE, p.getId(), p.getFirstName() + " " + p.getLastName(),
                        p.getRelationship(), p.getPhone(), false))
                .toList();
        return PickupRules.pickupList(family, others);
    }

    /**
     * Départ. Une personne de la liste suffit ; toute autre personne exige une validation de la direction
     * ({@code validation}), faute de quoi le départ est refusé ({@code pickup_not_authorized}).
     */
    @Transactional
    public ChildAttendance depart(UUID structureId, ChildAttendance attendance, Instant at, PickupInput pickup,
                                  OverrideInput other, Validation validation, Author author) {
        closedPeriods.requireOpen(structureId, attendance.getDay());
        if (!attendance.isPresent()) {
            throw ApiException.rule("not_present", "Le départ est déjà enregistré.");
        }
        if (at.isBefore(attendance.getArrivedAt())) {
            throw ApiException.rule("departure_before_arrival", "Le départ ne peut pas précéder l’arrivée.");
        }
        var child = loadChild(structureId, attendance.getChildId());
        var listed = pickup == null ? java.util.Optional.<Candidate>empty()
                : PickupRules.find(pickupList(child), pickup.kind(), pickup.personId());
        if (listed.isPresent()) {
            var person = listed.get();
            attendance.depart(at, person.kind(), person.id(), person.name(), author.name());
        } else {
            if (other == null || validation == null) {
                throw ApiException.rule("pickup_not_authorized", "Cette personne ne figure pas sur la liste de %s : le départ est bloqué tant que la direction ne l’a pas validé."
                        .formatted(child.getFirstName()));
            }
            attendance.depart(at, Kind.AUTRE, null, other.personName().trim(), author.name());
            overrides.save(new DepartureOverride(structureId, attendance.getId(), other.personName().trim(),
                    other.relationship().trim(), other.idChecked(), validation.directorId(), validation.directorName(),
                    at, validation.pinVerified(), blankToNull(other.reason())));
            if (!validation.pinVerified()) {
                attendance.flag("Départ validé hors connexion avec un code de direction incorrect");
            }
            audit.record(author.actor(structureId), AuditAction.CHILD_DEPARTURE_OVERRIDE, "child_attendance",
                    attendance.getId(), Map.of("child", child.displayName(), "person", other.personName().trim(),
                            "relationship", other.relationship().trim(), "idChecked", other.idChecked(),
                            "validatedBy", validation.directorName(), "pinVerified", validation.pinVerified()));
        }
        var saved = attendances.saveAndFlush(attendance);
        audit.record(author.actor(structureId), AuditAction.CHILD_DEPARTURE, "child_attendance", saved.getId(),
                Map.of("child", child.displayName(), "at", at.toString(), "pickedUpBy", saved.getPickedUpByName()));
        events.publishEvent(new StaffingChanged(structureId));
        events.publishEvent(new PortalChange(structureId, PortalItemType.ATTENDANCE, saved.getId()));
        return saved;
    }

    /** Passage ouvert d'un enfant, par identifiant de passage puis, à défaut, par enfant et jour. */
    @Transactional(readOnly = true)
    public java.util.Optional<ChildAttendance> findOpen(UUID structureId, UUID attendanceId, UUID childId, LocalDate day) {
        if (attendanceId != null) {
            var byId = attendances.findByIdAndStructureId(attendanceId, structureId).filter(ChildAttendance::isPresent);
            if (byId.isPresent()) {
                return byId;
            }
        }
        return childId == null ? java.util.Optional.empty()
                : attendances.findByChildIdAndDayAndDepartedAtIsNull(childId, day)
                .filter(a -> a.getStructureId().equals(structureId));
    }

    // --- Personnel ---------------------------------------------------------------------------

    @Transactional
    public StaffAttendance staffArrive(UUID structureId, UUID attendanceId, AppUser user, UUID sectionId, Instant at,
                                       StaffAttendance.Source source) {
        var day = dayOf(at);
        if (staffAttendances.findByUserIdAndDayAndDepartedAtIsNull(user.getId(), day).isPresent()) {
            throw ApiException.conflict("already_present", "%s est déjà présent(e).".formatted(user.getFirstName()));
        }
        var section = sectionId != null ? checkSection(structureId, sectionId) : user.getHomeSectionId();
        var attendance = staffAttendances.saveAndFlush(new StaffAttendance(
                attendanceId != null ? attendanceId : UUID.randomUUID(), structureId, user.getId(), day, section, at,
                source));
        audit.record(new Actor(structureId, user.getId(), user.displayName()), AuditAction.STAFF_ARRIVAL,
                "staff_attendance", attendance.getId(), Map.of("at", at.toString(), "source", source.name()));
        events.publishEvent(new StaffingChanged(structureId));
        return attendance;
    }

    @Transactional
    public StaffAttendance staffDepart(UUID structureId, StaffAttendance attendance, Instant at, AppUser user) {
        if (at.isBefore(attendance.getArrivedAt())) {
            throw ApiException.rule("departure_before_arrival", "Le départ ne peut pas précéder l’arrivée.");
        }
        attendance.depart(at);
        var saved = staffAttendances.saveAndFlush(attendance);
        audit.record(new Actor(structureId, user.getId(), user.displayName()), AuditAction.STAFF_DEPARTURE,
                "staff_attendance", saved.getId(), Map.of("at", at.toString()));
        events.publishEvent(new StaffingChanged(structureId));
        return saved;
    }

    @Transactional
    public void staffMove(UUID structureId, UUID userId, UUID sectionId, Instant at, Author author) {
        var attendance = staffAttendances.findByUserIdAndDayAndDepartedAtIsNull(userId, dayOf(at))
                .filter(a -> a.getStructureId().equals(structureId))
                .orElseThrow(() -> ApiException.rule("not_present", "Cette personne n’a pas pointé son arrivée."));
        attendance.moveTo(checkSection(structureId, sectionId));
        staffAttendances.save(attendance);
        audit.record(author.actor(structureId), AuditAction.STAFF_SECTION_CHANGED, "staff_attendance",
                attendance.getId(), Map.of("userId", userId.toString(), "sectionId", sectionId.toString()));
        events.publishEvent(new StaffingChanged(structureId));
    }

    // --- Corrections de la direction ---------------------------------------------------------------

    @Transactional
    public ChildAttendance correctChild(UUID structureId, UUID id, Instant arrivedAt, Instant departedAt,
                                        UUID sectionId, String pickedUpBy, String reason, Author author) {
        var attendance = loadAttendance(structureId, id);
        closedPeriods.requireOpen(structureId, attendance.getDay());
        checkTimes(attendance.getDay(), arrivedAt, departedAt);
        var changes = new LinkedHashMap<String, String[]>();
        compare(changes, "arrivee", time(attendance.getArrivedAt()), time(arrivedAt));
        compare(changes, "depart", time(attendance.getDepartedAt()), time(departedAt));
        compare(changes, "section", str(attendance.getSectionId()), str(sectionId));
        var section = sectionId == null ? null : checkSection(structureId, sectionId);
        boolean newDeparture = attendance.getDepartedAt() == null && departedAt != null;
        if (newDeparture && blankToNull(pickedUpBy) == null) {
            throw ApiException.rule("pickup_required", "Indiquez qui est venu chercher l’enfant.");
        }
        if (attendance.getDepartedAt() != null && departedAt == null) {
            attendances.findByChildIdAndDayAndDepartedAtIsNull(attendance.getChildId(), attendance.getDay())
                    .ifPresent(other -> {
                        throw ApiException.conflict("already_present", "L’enfant a déjà un autre passage en cours ce jour-là.");
                    });
        }
        var previousPickup = attendance.getPickedUpByName();
        var previousKind = attendance.getPickedUpByKind();
        var previousPickupId = attendance.getPickedUpById();
        attendance.correct(arrivedAt, departedAt, section);
        if (departedAt != null) {
            if (newDeparture) {
                attendance.depart(departedAt, Kind.AUTRE, null, pickedUpBy.trim(), author.name());
                compare(changes, "recupere_par", null, pickedUpBy.trim());
            } else {
                attendance.depart(departedAt, previousKind, previousPickupId, previousPickup,
                        attendance.getDepartureRecordedBy());
            }
        }
        if (changes.isEmpty()) {
            throw ApiException.rule("no_change", "Aucune modification à enregistrer.");
        }
        journal(structureId, Target.ENFANT, id, changes, reason, author);
        var saved = attendances.saveAndFlush(attendance);
        events.publishEvent(new StaffingChanged(structureId));
        events.publishEvent(new PortalChange(structureId, PortalItemType.ATTENDANCE, saved.getId()));
        return saved;
    }

    /** Passage oublié, ajouté par la direction (tablette en panne, pointage manqué). */
    @Transactional
    public ChildAttendance addChild(UUID structureId, UUID childId, Instant arrivedAt, Instant departedAt,
                                    UUID sectionId, String pickedUpBy, String reason, Author author) {
        var day = dayOf(arrivedAt);
        checkTimes(day, arrivedAt, departedAt);
        if (departedAt != null && blankToNull(pickedUpBy) == null) {
            throw ApiException.rule("pickup_required", "Indiquez qui est venu chercher l’enfant.");
        }
        var attendance = arrive(structureId, null, childId, sectionId, arrivedAt, author, ChildAttendance.Source.BUREAU);
        if (departedAt != null) {
            attendance.depart(departedAt, Kind.AUTRE, null, pickedUpBy.trim(), author.name());
            attendance = attendances.saveAndFlush(attendance);
        }
        var changes = new LinkedHashMap<String, String[]>();
        compare(changes, "ajout", null, time(arrivedAt) + (departedAt == null ? "" : " – " + time(departedAt)));
        journal(structureId, Target.ENFANT, attendance.getId(), changes, reason, author);
        audit.record(author.actor(structureId), AuditAction.ATTENDANCE_ADDED, "child_attendance", attendance.getId(),
                Map.of("reason", reason));
        return attendance;
    }

    @Transactional
    public StaffAttendance correctStaff(UUID structureId, UUID id, Instant arrivedAt, Instant departedAt,
                                        UUID sectionId, String reason, Author author) {
        var attendance = staffAttendances.findByIdAndStructureId(id, structureId)
                .orElseThrow(() -> ApiException.notFound("Pointage introuvable."));
        checkTimes(attendance.getDay(), arrivedAt, departedAt);
        if (attendance.getDepartedAt() != null && departedAt == null
                && staffAttendances.findByUserIdAndDayAndDepartedAtIsNull(attendance.getUserId(), attendance.getDay()).isPresent()) {
            throw ApiException.conflict("already_present", "Cette personne a déjà un autre pointage en cours ce jour-là.");
        }
        var changes = new LinkedHashMap<String, String[]>();
        compare(changes, "arrivee", time(attendance.getArrivedAt()), time(arrivedAt));
        compare(changes, "depart", time(attendance.getDepartedAt()), time(departedAt));
        compare(changes, "section", str(attendance.getSectionId()), str(sectionId));
        if (changes.isEmpty()) {
            throw ApiException.rule("no_change", "Aucune modification à enregistrer.");
        }
        attendance.correct(arrivedAt, departedAt, sectionId == null ? null : checkSection(structureId, sectionId));
        journal(structureId, Target.PERSONNEL, id, changes, reason, author);
        var saved = staffAttendances.saveAndFlush(attendance);
        events.publishEvent(new StaffingChanged(structureId));
        return saved;
    }

    @Transactional
    public StaffAttendance addStaff(UUID structureId, AppUser user, Instant arrivedAt, Instant departedAt,
                                    UUID sectionId, String reason, Author author) {
        var day = dayOf(arrivedAt);
        checkTimes(day, arrivedAt, departedAt);
        var attendance = staffArrive(structureId, null, user, sectionId, arrivedAt, StaffAttendance.Source.BUREAU);
        if (departedAt != null) {
            attendance.depart(departedAt);
            attendance = staffAttendances.saveAndFlush(attendance);
        }
        var changes = new LinkedHashMap<String, String[]>();
        compare(changes, "ajout", null, time(arrivedAt) + (departedAt == null ? "" : " – " + time(departedAt)));
        journal(structureId, Target.PERSONNEL, attendance.getId(), changes, reason, author);
        audit.record(author.actor(structureId), AuditAction.ATTENDANCE_ADDED, "staff_attendance", attendance.getId(),
                Map.of("reason", reason));
        return attendance;
    }

    /** La direction a vérifié un pointage signalé (hors connexion, code incorrect, départ non pointé). */
    @Transactional
    public void review(UUID structureId, Target target, UUID id, String note, Author author) {
        String previous;
        if (target == Target.ENFANT) {
            var attendance = loadAttendance(structureId, id);
            previous = attendance.getReviewReason();
            attendance.reviewed();
            attendances.save(attendance);
        } else {
            var attendance = staffAttendances.findByIdAndStructureId(id, structureId)
                    .orElseThrow(() -> ApiException.notFound("Pointage introuvable."));
            previous = attendance.getReviewReason();
            attendance.reviewed();
            staffAttendances.save(attendance);
        }
        var changes = new LinkedHashMap<String, String[]>();
        changes.put("verification", new String[] {previous, "Vérifié"});
        journal(structureId, target, id, changes, note, author);
        audit.record(author.actor(structureId), AuditAction.ATTENDANCE_REVIEWED, target == Target.ENFANT
                ? "child_attendance" : "staff_attendance", id, Map.of("note", note));
    }

    // --- Outils ----------------------------------------------------------------------------------

    private void journal(UUID structureId, Target target, UUID id, Map<String, String[]> changes, String reason,
                         Author author) {
        var now = clock.instant();
        for (var change : changes.entrySet()) {
            corrections.save(new AttendanceCorrection(structureId, target, id, change.getKey(), change.getValue()[0],
                    change.getValue()[1], reason.trim(), author.userId(), author.name(), now));
        }
        var details = new LinkedHashMap<String, Object>();
        details.put("reason", reason.trim());
        changes.forEach((field, values) -> details.put(field, Objects.toString(values[0], "—") + " → "
                + Objects.toString(values[1], "—")));
        audit.record(author.actor(structureId), AuditAction.ATTENDANCE_CORRECTED,
                target == Target.ENFANT ? "child_attendance" : "staff_attendance", id, details);
    }

    private void checkTimes(LocalDate day, Instant arrivedAt, Instant departedAt) {
        if (!dayOf(arrivedAt).equals(day)) {
            throw ApiException.rule("day_mismatch", "L’arrivée doit rester le %s.".formatted(Formats.date(day)));
        }
        if (departedAt != null && departedAt.isBefore(arrivedAt)) {
            throw ApiException.rule("departure_before_arrival", "Le départ ne peut pas précéder l’arrivée.");
        }
        if (arrivedAt.isAfter(clock.instant().plusSeconds(300))) {
            throw ApiException.rule("future_time", "Un pointage ne peut pas être dans le futur.");
        }
    }

    private static void compare(Map<String, String[]> changes, String field, String before, String after) {
        if (!Objects.equals(before, after)) {
            changes.put(field, new String[] {before, after});
        }
    }

    private String time(Instant instant) {
        return instant == null ? null : Formats.time(instant.atZone(zone).toLocalTime());
    }

    private static String str(UUID id) {
        return id == null ? null : id.toString();
    }

    private Child loadChild(UUID structureId, UUID childId) {
        return children.findByIdAndStructureId(childId, structureId)
                .orElseThrow(() -> ApiException.notFound("Enfant introuvable."));
    }

    ChildAttendance loadAttendance(UUID structureId, UUID id) {
        return attendances.findByIdAndStructureId(id, structureId)
                .orElseThrow(() -> ApiException.notFound("Pointage introuvable."));
    }

    private UUID checkSection(UUID structureId, UUID sectionId) {
        return sections.findByIdAndStructureId(sectionId, structureId)
                .orElseThrow(() -> ApiException.rule("section_unknown", "Section inconnue.")).getId();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static Set<Role> pointingRoles() {
        return Set.of(Role.DIRECTION, Role.EDUCATRICE);
    }
}
