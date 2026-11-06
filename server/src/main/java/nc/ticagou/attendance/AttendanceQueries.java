package nc.ticagou.attendance;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.attendance.AttendanceCorrection.Target;
import nc.ticagou.attendance.AttendanceDtos.AbsentStaff;
import nc.ticagou.attendance.AttendanceDtos.AllergyInfo;
import nc.ticagou.attendance.AttendanceDtos.ChildRow;
import nc.ticagou.attendance.AttendanceDtos.Contact;
import nc.ticagou.attendance.AttendanceDtos.CorrectionDto;
import nc.ticagou.attendance.AttendanceDtos.DayView;
import nc.ticagou.attendance.AttendanceDtos.Director;
import nc.ticagou.attendance.AttendanceDtos.ExpectedRow;
import nc.ticagou.attendance.AttendanceDtos.InstructionDto;
import nc.ticagou.attendance.AttendanceDtos.KioskAttendance;
import nc.ticagou.attendance.AttendanceDtos.KioskChild;
import nc.ticagou.attendance.AttendanceDtos.KioskHealth;
import nc.ticagou.attendance.AttendanceDtos.KioskSection;
import nc.ticagou.attendance.AttendanceDtos.KioskStaff;
import nc.ticagou.attendance.AttendanceDtos.KioskStaffAttendance;
import nc.ticagou.attendance.AttendanceDtos.KioskToday;
import nc.ticagou.attendance.AttendanceDtos.OverrideDto;
import nc.ticagou.attendance.AttendanceDtos.RefusedOperation;
import nc.ticagou.attendance.AttendanceDtos.ReviewList;
import nc.ticagou.attendance.AttendanceDtos.StaffRow;
import nc.ticagou.auth.AppUser;
import nc.ticagou.auth.AppUserRepository;
import nc.ticagou.auth.Role;
import nc.ticagou.child.Allergy;
import nc.ticagou.child.AllergyRepository;
import nc.ticagou.child.Child;
import nc.ticagou.child.ChildRepository;
import nc.ticagou.child.HealthInfo;
import nc.ticagou.child.HealthInfoRepository;
import nc.ticagou.config.TiCagouProperties;
import nc.ticagou.family.GuardianRepository;
import nc.ticagou.staffing.RatioCalculator;
import nc.ticagou.staffing.RatioRule;
import nc.ticagou.staffing.RatioRuleRepository;
import nc.ticagou.structure.SectionRepository;
import nc.ticagou.structure.StructureRepository;

/** Lectures du pointage : journée au bureau, liste à vérifier, instantané du jour pour la tablette. */
@Service
public class AttendanceQueries {

    /** Un enfant attendu est « en retard » 15 minutes après l'heure prévue à son contrat. */
    static final Duration LATE_AFTER = Duration.ofMinutes(15);

    static final java.util.Set<String> LIVE_REFUSALS = java.util.Set.of("bad_director_pin", "validator_unknown",
            "pickup_not_authorized", "locked");

    private final ChildAttendanceRepository attendances;
    private final ChildInstructionRepository instructions;
    private final DepartureOverrideRepository overrides;
    private final StaffAttendanceRepository staffAttendances;
    private final AttendanceCorrectionRepository corrections;
    private final KioskOperationRepository operations;
    private final ChildRepository children;
    private final AllergyRepository allergies;
    private final HealthInfoRepository healthInfos;
    private final GuardianRepository guardians;
    private final AppUserRepository users;
    private final SectionRepository sections;
    private final StructureRepository structures;
    private final RatioRuleRepository ratioRules;
    private final PlannedDays plannedDays;
    private final AttendanceService service;
    private final ZoneId zone;
    private final Clock clock;

    public AttendanceQueries(ChildAttendanceRepository attendances, ChildInstructionRepository instructions,
                             DepartureOverrideRepository overrides, StaffAttendanceRepository staffAttendances,
                             AttendanceCorrectionRepository corrections, KioskOperationRepository operations,
                             ChildRepository children, AllergyRepository allergies, HealthInfoRepository healthInfos,
                             GuardianRepository guardians, AppUserRepository users, SectionRepository sections,
                             StructureRepository structures, RatioRuleRepository ratioRules, PlannedDays plannedDays,
                             AttendanceService service, TiCagouProperties properties, Clock clock) {
        this.attendances = attendances;
        this.instructions = instructions;
        this.overrides = overrides;
        this.staffAttendances = staffAttendances;
        this.corrections = corrections;
        this.operations = operations;
        this.children = children;
        this.allergies = allergies;
        this.healthInfos = healthInfos;
        this.guardians = guardians;
        this.users = users;
        this.sections = sections;
        this.structures = structures;
        this.ratioRules = ratioRules;
        this.plannedDays = plannedDays;
        this.service = service;
        this.zone = properties.zone();
        this.clock = clock;
    }

    // --- Poste bureau -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public DayView day(UUID structureId, LocalDate day) {
        var childById = childrenById(structureId);
        var dayAttendances = attendances.findByStructureIdAndDayOrderByArrivedAtAsc(structureId, day);
        var rows = childRows(dayAttendances, childById);
        var planned = plannedDays.forDay(structureId, day);
        var arrived = dayAttendances.stream().map(ChildAttendance::getChildId).collect(Collectors.toSet());
        var now = clock.instant().atZone(zone);
        boolean isToday = now.toLocalDate().equals(day);
        var notArrived = planned.entrySet().stream()
                .filter(e -> e.getValue().expected() && !arrived.contains(e.getKey()))
                .map(e -> Map.entry(childById.get(e.getKey()), e.getValue()))
                .filter(e -> e.getKey() != null && e.getKey().isEnrolledOn(day))
                .map(e -> new ExpectedRow(e.getKey().getId(), e.getKey().getFirstName(), e.getKey().getLastName(),
                        e.getKey().getSectionId(), e.getValue().arrivesAt(),
                        isToday && now.toLocalTime().isAfter(e.getValue().arrivesAt().plus(LATE_AFTER))))
                .sorted(Comparator.comparing(ExpectedRow::expectedAt).thenComparing(ExpectedRow::lastName))
                .toList();
        int expected = (int) planned.entrySet().stream()
                .filter(e -> e.getValue().expected() && childById.containsKey(e.getKey())
                        && childById.get(e.getKey()).isEnrolledOn(day))
                .count();

        var userById = usersById(structureId);
        var staffRows = staffRows(staffAttendances.findByStructureIdAndDayOrderByArrivedAtAsc(structureId, day), userById);
        var pointed = staffRows.stream().map(StaffRow::userId).collect(Collectors.toSet());
        var absent = userById.values().stream()
                .filter(u -> u.isActive() && u.getPinHash() != null && !pointed.contains(u.getId()))
                .sorted(Comparator.comparing(AppUser::getFirstName))
                .map(u -> new AbsentStaff(u.getId(), u.displayName(), u.initials(), u.getJobTitle(), u.getRole(),
                        u.isCountsInRatio(), u.getHomeSectionId()))
                .toList();
        return new DayView(day, rows, notArrived, staffRows, absent, expected);
    }

    @Transactional(readOnly = true)
    public ReviewList toReview(UUID structureId) {
        var today = LocalDate.now(clock.withZone(zone));
        var childRows = childRows(attendances.findToReview(structureId, today), childrenById(structureId));
        var staffRows = staffRows(staffAttendances.findToReview(structureId, today), usersById(structureId));
        var refused = operations.findByStructureIdAndOutcomeAndReceivedAtAfterOrderByReceivedAtDesc(structureId,
                        KioskOperation.Outcome.REFUSEE, clock.instant().minus(Duration.ofDays(7))).stream()
                // Refus vécus en direct sur la tablette (code faux, personne hors liste) : déjà gérés sur place,
                // tracés dans l'audit ; la liste ne garde que ce qui demande une action de la direction.
                .filter(o -> !"CONNEXION_HORS_LIGNE".equals(o.getType()) && (o.getCode() == null || !LIVE_REFUSALS.contains(o.getCode())))
                .map(o -> new RefusedOperation(o.getClientId(), o.getReceivedAt(), o.getOccurredAt(), o.getType(),
                        o.getCode(), o.getMessage()))
                .toList();
        return new ReviewList(childRows, staffRows, refused);
    }

    @Transactional(readOnly = true)
    public List<CorrectionDto> corrections(UUID structureId, Target target, UUID id) {
        return corrections.findByStructureIdAndAttendanceIdOrderByCreatedAtAsc(structureId, id).stream()
                .filter(c -> c.getTarget() == target).map(CorrectionDto::from).toList();
    }

    // --- Tablette --------------------------------------------------------------------------------

    /** Instantané du jour mis en cache par la tablette : il lui suffit pour pointer hors connexion. */
    @Transactional(readOnly = true)
    public KioskToday kioskToday(UUID structureId) {
        var now = clock.instant();
        var day = now.atZone(zone).toLocalDate();
        var structure = structures.findById(structureId).orElseThrow();
        var sectionList = sections.findByStructureIdOrderByDisplayOrderAscMinAgeMonthsAsc(structureId).stream()
                .map(s -> new KioskSection(s.getId(), s.getName(), s.getColor())).toList();
        var rules = ratioRules.findByStructureIdOrderByMinAgeMonthsAscValidFromDesc(structureId).stream()
                .map(RatioRule::toRule).toList();
        int fallback = RatioCalculator.strictest(rules, day);

        var enrolled = children.findByStructureId(structureId).stream().filter(c -> c.isEnrolledOn(day))
                .sorted(Comparator.comparing(Child::getFirstName).thenComparing(Child::getLastName)).toList();
        var planned = plannedDays.forDay(structureId, day);
        var pickups = service.pickupLists(enrolled);
        var dayAttendances = attendances.findByStructureIdAndDayOrderByArrivedAtAsc(structureId, day);
        var attendanceIds = dayAttendances.stream().map(ChildAttendance::getId).toList();
        Map<UUID, List<InstructionDto>> instructionsByAttendance = attendanceIds.isEmpty() ? Map.of()
                : instructions.findByAttendanceIdInOrderByCreatedAtAsc(attendanceIds).stream()
                .collect(Collectors.groupingBy(ChildInstruction::getAttendanceId,
                        Collectors.mapping(InstructionDto::from, Collectors.toList())));
        var attendancesByChild = dayAttendances.stream().collect(Collectors.groupingBy(ChildAttendance::getChildId));
        Map<UUID, List<Allergy>> allergiesByChild = allergies.findByStructureId(structureId).stream()
                .collect(Collectors.groupingBy(Allergy::getChildId));
        Map<UUID, HealthInfo> healthByChild = healthInfos.findByStructureId(structureId).stream()
                .collect(Collectors.toMap(HealthInfo::getChildId, Function.identity()));
        var guardiansByFamily = guardians.findByFamilyIdIn(enrolled.stream().map(Child::getFamilyId).distinct().toList())
                .stream().collect(Collectors.groupingBy(g -> g.getFamilyId()));

        var kioskChildren = enrolled.stream().map(child -> {
            var plan = planned.get(child.getId());
            int ratio = RatioCalculator.childrenPerAdult(rules, child.getBirthDate(), day);
            var health = healthByChild.get(child.getId());
            var kioskHealth = new KioskHealth(
                    allergiesByChild.getOrDefault(child.getId(), List.of()).stream()
                            .sorted(Comparator.comparingInt(Allergy::getDisplayOrder))
                            .map(a -> new AllergyInfo(a.getAllergen(), a.getSeverity(), a.getReaction())).toList(),
                    health != null && health.isPaiValidOn(day),
                    health == null ? null : health.getPaiReason(),
                    health == null ? null : health.getPaiKitLocation(),
                    health == null ? null : health.getDiet(),
                    health == null ? null : health.getDietDetails(),
                    health == null ? null : health.getInstructions());
            var parents = guardiansByFamily.getOrDefault(child.getFamilyId(), List.of()).stream()
                    .filter(g -> g.isLegalGuardian() && g.getMobilePhone() != null)
                    .map(g -> new Contact(g.getFirstName() + " " + g.getLastName(), g.getRelationship(), g.getMobilePhone()))
                    .toList();
            var passages = attendancesByChild.getOrDefault(child.getId(), List.of()).stream()
                    .map(a -> new KioskAttendance(a.getId(), a.getArrivedAt(), a.getDepartedAt(), a.getDroppedOffBy(),
                            a.getPickedUpByName(), instructionsByAttendance.getOrDefault(a.getId(), List.of())))
                    .toList();
            return new KioskChild(child.getId(), child.getFirstName(), child.getLastName(), child.getBirthDate(),
                    child.getSectionId(), plan != null && plan.expected(), plan != null && plan.variable(),
                    plan == null ? null : plan.arrivesAt(), plan == null ? null : plan.leavesAt(),
                    ratio > 0 ? ratio : fallback, kioskHealth, pickups.get(child.getId()), parents, passages);
        }).toList();

        var staffByUser = staffAttendances.findByStructureIdAndDayOrderByArrivedAtAsc(structureId, day).stream()
                .collect(Collectors.groupingBy(StaffAttendance::getUserId));
        var activeUsers = users.findByStructureIdOrderByLastNameAscFirstNameAsc(structureId).stream()
                .filter(AppUser::isActive).toList();
        var staff = activeUsers.stream()
                .filter(u -> u.getPinHash() != null || staffByUser.containsKey(u.getId()))
                .sorted(Comparator.comparing(AppUser::getFirstName))
                .map(u -> new KioskStaff(u.getId(), u.displayName(), u.initials(), u.getRole(), u.getJobTitle(),
                        u.getHomeSectionId(), u.isCountsInRatio(), u.getPinHash() != null,
                        staffByUser.getOrDefault(u.getId(), List.of()).stream()
                                .map(a -> new KioskStaffAttendance(a.getId(), a.getArrivedAt(), a.getDepartedAt(), a.getSectionId()))
                                .toList()))
                .toList();
        var directors = activeUsers.stream().filter(u -> u.getRole() == Role.DIRECTION && u.getPinHash() != null)
                .map(u -> new Director(u.getId(), u.displayName())).toList();
        return new KioskToday(now, day, structure.getName(), sectionList, kioskChildren, staff, directors, fallback);
    }

    // --- Outils --------------------------------------------------------------------------------

    private Map<UUID, Child> childrenById(UUID structureId) {
        return children.findByStructureId(structureId).stream().collect(Collectors.toMap(Child::getId, Function.identity()));
    }

    private Map<UUID, AppUser> usersById(UUID structureId) {
        return users.findByStructureIdOrderByLastNameAscFirstNameAsc(structureId).stream()
                .collect(Collectors.toMap(AppUser::getId, Function.identity()));
    }

    private List<ChildRow> childRows(Collection<ChildAttendance> list, Map<UUID, Child> childById) {
        var ids = list.stream().map(ChildAttendance::getId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        var instructionsByAttendance = instructions.findByAttendanceIdInOrderByCreatedAtAsc(ids).stream()
                .collect(Collectors.groupingBy(ChildInstruction::getAttendanceId,
                        Collectors.mapping(InstructionDto::from, Collectors.toList())));
        var overrideByAttendance = overrides.findByAttendanceIdIn(ids).stream()
                .collect(Collectors.toMap(DepartureOverride::getAttendanceId, Function.identity()));
        return list.stream().map(a -> {
            var child = childById.get(a.getChildId());
            return new ChildRow(a.getId(), a.getDay(), a.getChildId(), child == null ? "?" : child.getFirstName(),
                    child == null ? "?" : child.getLastName(), a.getSectionId(), a.getArrivedAt(), a.getDepartedAt(),
                    a.getDroppedOffBy(), a.getPickedUpByKind(), a.getPickedUpByName(), a.isUnplanned(),
                    a.getArrivalRecordedBy(), a.getDepartureRecordedBy(), a.getSource(),
                    a.isNeedsReview() || (a.getDepartedAt() == null && a.getDay().isBefore(LocalDate.now(clock.withZone(zone)))),
                    a.getReviewReason() != null ? a.getReviewReason()
                            : a.getDepartedAt() == null && a.getDay().isBefore(LocalDate.now(clock.withZone(zone)))
                            ? "Départ non enregistré" : null,
                    instructionsByAttendance.getOrDefault(a.getId(), List.of()),
                    OverrideDto.from(overrideByAttendance.get(a.getId())), a.getVersion());
        }).toList();
    }

    private List<StaffRow> staffRows(Collection<StaffAttendance> list, Map<UUID, AppUser> userById) {
        var today = LocalDate.now(clock.withZone(zone));
        return list.stream().map(a -> {
            var user = userById.get(a.getUserId());
            boolean forgotten = a.getDepartedAt() == null && a.getDay().isBefore(today);
            return new StaffRow(a.getId(), a.getDay(), a.getUserId(), user == null ? "?" : user.displayName(),
                    user == null ? "?" : user.initials(), user == null ? null : user.getJobTitle(),
                    user == null ? null : user.getRole(), user != null && user.isCountsInRatio(), a.getSectionId(),
                    a.getArrivedAt(), a.getDepartedAt(), a.getSource(), a.isPinVerified(),
                    a.isNeedsReview() || forgotten,
                    a.getReviewReason() != null ? a.getReviewReason() : forgotten ? "Départ non pointé" : null,
                    a.getVersion());
        }).toList();
    }
}
