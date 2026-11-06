package nc.ticagou.staff;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.attendance.PlannedDays;
import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.AppUser;
import nc.ticagou.auth.AppUserRepository;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.billing.ChildAbsenceRepository;
import nc.ticagou.child.Child;
import nc.ticagou.child.ChildRepository;
import nc.ticagou.common.ApiException;
import nc.ticagou.common.Formats;
import nc.ticagou.staff.ForecastCalculator.ExpectedChild;
import nc.ticagou.staff.ForecastCalculator.PlannedAdult;
import nc.ticagou.staff.Shifts.Shift;
import nc.ticagou.staff.StaffDtos.AbsenceDto;
import nc.ticagou.staff.StaffDtos.AbsenceRequest;
import nc.ticagou.staff.StaffDtos.Forecast;
import nc.ticagou.staff.StaffDtos.LoadDto;
import nc.ticagou.staff.StaffDtos.OverrideRequest;
import nc.ticagou.staff.StaffDtos.PlanningRow;
import nc.ticagou.staff.StaffDtos.ScheduleDay;
import nc.ticagou.staff.StaffDtos.ScheduleDto;
import nc.ticagou.staff.StaffDtos.ScheduleRequest;
import nc.ticagou.staff.StaffDtos.SectionDto;
import nc.ticagou.staff.StaffDtos.ShiftDto;
import nc.ticagou.staff.StaffDtos.SlotDto;
import nc.ticagou.staff.StaffDtos.WeekPlanning;
import nc.ticagou.staffing.RatioCalculator.Load;
import nc.ticagou.staffing.RatioCalculator.Status;
import nc.ticagou.staffing.RatioRule;
import nc.ticagou.staffing.RatioRuleRepository;
import nc.ticagou.staffing.StaffingChanged;
import nc.ticagou.structure.Section;
import nc.ticagou.structure.SectionRepository;
import nc.ticagou.structure.StructureRepository;

/**
 * Planning du personnel : semaine type datée, exceptions d'un jour, absences ; planning de la semaine et
 * encadrement prévisionnel par créneau de 30 minutes. Un mois dont les feuilles d'heures sont validées ne change plus.
 */
@Service
public class PlanningService {

    static final int SLOT_MINUTES = 30;

    private final StaffScheduleRepository schedules;
    private final StaffShiftOverrideRepository overrides;
    private final StaffAbsenceRepository absences;
    private final TimesheetMonthRepository validatedMonths;
    private final AppUserRepository users;
    private final StaffProfileRepository profiles;
    private final SectionRepository sections;
    private final StructureRepository structures;
    private final RatioRuleRepository ratioRules;
    private final ChildRepository children;
    private final ChildAbsenceRepository childAbsences;
    private final PlannedDays plannedDays;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public PlanningService(StaffScheduleRepository schedules, StaffShiftOverrideRepository overrides,
                           StaffAbsenceRepository absences, TimesheetMonthRepository validatedMonths,
                           AppUserRepository users, StaffProfileRepository profiles, SectionRepository sections,
                           StructureRepository structures, RatioRuleRepository ratioRules, ChildRepository children,
                           ChildAbsenceRepository childAbsences, PlannedDays plannedDays, AuditService audit,
                           ApplicationEventPublisher events, Clock clock) {
        this.schedules = schedules;
        this.overrides = overrides;
        this.absences = absences;
        this.validatedMonths = validatedMonths;
        this.users = users;
        this.profiles = profiles;
        this.sections = sections;
        this.structures = structures;
        this.ratioRules = ratioRules;
        this.children = children;
        this.childAbsences = childAbsences;
        this.plannedDays = plannedDays;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- semaine type

    @Transactional(readOnly = true)
    public ScheduleDto scheduleOn(UUID userId, LocalDate day) {
        return schedules.findByUserIdOrderByEffectiveFromAsc(userId).stream()
                .filter(s -> !s.getEffectiveFrom().isAfter(day))
                .reduce((a, b) -> b).map(PlanningService::scheduleDto).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<ScheduleDto> upcomingSchedules(UUID userId, LocalDate day) {
        return schedules.findByUserIdOrderByEffectiveFromAsc(userId).stream()
                .filter(s -> s.getEffectiveFrom().isAfter(day)).map(PlanningService::scheduleDto).toList();
    }

    /** Nouvelle semaine type à partir d'une date (remplace celle qui commence le même jour). */
    @Transactional
    public ScheduleDto setSchedule(UUID userId, ScheduleRequest r) {
        var user = loadUser(userId);
        requireOpenFrom(user.getStructureId(), r.effectiveFrom());
        for (var d : r.days()) {
            checkSection(user.getStructureId(), d.sectionId());
        }
        var schedule = schedules.findByUserIdAndEffectiveFrom(userId, r.effectiveFrom())
                .orElseGet(() -> new StaffSchedule(user.getStructureId(), userId, r.effectiveFrom(), Instant.now(clock)));
        schedule.replaceDays(r.days().stream().sorted(Comparator.comparingInt(ScheduleDay::dayOfWeek))
                .map(d -> new StaffSchedule.Day(d.dayOfWeek(), d.startsAt(), d.endsAt(), d.breakMinutes(), d.sectionId()))
                .toList());
        schedules.saveAndFlush(schedule);
        events.publishEvent(new StaffingChanged(user.getStructureId()));
        audit.record(AuditAction.STAFF_SCHEDULE_UPDATED, "user", userId,
                Map.of("effectiveFrom", r.effectiveFrom().toString(), "days", r.days().size()));
        return scheduleDto(schedule);
    }

    private static ScheduleDto scheduleDto(StaffSchedule s) {
        var days = s.getDays().stream().sorted(Comparator.comparingInt(StaffSchedule.Day::dayOfWeek))
                .map(d -> new ScheduleDay(d.dayOfWeek(), d.startsAt(), d.endsAt(), d.breakMinutes(), d.sectionId())).toList();
        int minutes = s.toWeek().days().stream()
                .mapToInt(d -> new Shift(null, d.startsAt(), d.endsAt(), d.breakMinutes(), null, false, null, null, false)
                        .plannedMinutes()).sum();
        return new ScheduleDto(s.getEffectiveFrom(), days, minutes);
    }

    // ---------------------------------------------------------------- planning de la semaine

    @Transactional(readOnly = true)
    public WeekPlanning week(LocalDate anyDay, boolean onlyMe) {
        var current = CurrentUser.get();
        var structureId = current.structureId();
        var monday = anyDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var sunday = monday.plusDays(6);
        var staff = staff(structureId).stream().filter(u -> !onlyMe || u.getId().equals(current.userId())).toList();
        var shifts = shifts(structureId, staff, monday, sunday);
        var absenceIds = absenceIds(structureId, monday, sunday);
        var profileById = profiles.findAllById(staff.stream().map(AppUser::getId).toList()).stream()
                .collect(Collectors.toMap(StaffProfile::getUserId, Function.identity()));
        var rows = staff.stream().map(u -> {
            var list = shifts.get(u.getId());
            var dtos = list.stream().map(s -> shiftDto(s, absenceIds.get(u.getId() + "|" + s.day()))).toList();
            var profile = profileById.get(u.getId());
            return new PlanningRow(u.getId(), u.displayName(), u.getJobTitle(), u.getHomeSectionId(), u.isCountsInRatio(),
                    profile == null ? Integer.valueOf(39 * 60) : profile.getWeeklyMinutes(),
                    list.stream().mapToInt(s -> s.plannedMinutes() - s.absenceMinutes()).sum(), dtos);
        }).toList();
        var shortages = new LinkedHashMap<LocalDate, Integer>();
        if (!onlyMe) {
            for (var day = monday; !day.isAfter(sunday); day = day.plusDays(1)) {
                shortages.put(day, forecast(structureId, day).shortages());
            }
        }
        var openDays = structures.findById(structureId).orElseThrow().getOpeningHours().stream()
                .map(h -> h.dayOfWeek()).sorted().toList();
        var months = List.of(YearMonth.from(monday), YearMonth.from(sunday)).stream().distinct()
                .filter(m -> validatedMonths.findByStructureIdAndMonth(structureId, m.atDay(1)).isPresent()).toList();
        return new WeekPlanning(monday, openDays, rows, shortages, months);
    }

    /** Postes de chaque personne, jour par jour (semaine type, exceptions, absences). */
    @Transactional(readOnly = true)
    public Map<UUID, List<Shift>> shifts(UUID structureId, List<AppUser> staff, LocalDate from, LocalDate to) {
        var weeksByUser = schedules.findByStructureId(structureId).stream()
                .collect(Collectors.groupingBy(StaffSchedule::getUserId,
                        Collectors.mapping(StaffSchedule::toWeek, Collectors.toList())));
        var overridesByUser = overrides.findByStructureIdAndDayBetween(structureId, from, to).stream()
                .collect(Collectors.groupingBy(StaffShiftOverride::getUserId,
                        Collectors.mapping(StaffShiftOverride::toOverride, Collectors.toList())));
        var absencesByUser = absences.findByStructureIdAndFromDayLessThanEqualAndToDayGreaterThanEqual(structureId, to, from)
                .stream().collect(Collectors.groupingBy(StaffAbsence::getUserId,
                        Collectors.mapping(StaffAbsence::toAbsence, Collectors.toList())));
        var result = new LinkedHashMap<UUID, List<Shift>>();
        for (var user : staff) {
            var list = new ArrayList<Shift>();
            for (var day = from; !day.isAfter(to); day = day.plusDays(1)) {
                list.add(Shifts.resolve(day, weeksByUser.getOrDefault(user.getId(), List.of()),
                        overridesByUser.getOrDefault(user.getId(), List.of()),
                        absencesByUser.getOrDefault(user.getId(), List.of())));
            }
            result.put(user.getId(), list);
        }
        return result;
    }

    private Map<String, UUID> absenceIds(UUID structureId, LocalDate from, LocalDate to) {
        var map = new HashMap<String, UUID>();
        for (var a : absences.findByStructureIdAndFromDayLessThanEqualAndToDayGreaterThanEqual(structureId, to, from)) {
            for (var d = a.getFromDay(); !d.isAfter(a.getToDay()); d = d.plusDays(1)) {
                map.put(a.getUserId() + "|" + d, a.getId());
            }
        }
        return map;
    }

    private static ShiftDto shiftDto(Shift s, UUID absenceId) {
        return new ShiftDto(s.day(), s.startsAt(), s.endsAt(), s.breakMinutes(), s.sectionId(), s.overridden(), s.note(),
                s.absence(), absenceId, s.halfDay(), s.plannedMinutes());
    }

    /** Personnel actif de la structure. */
    public List<AppUser> staff(UUID structureId) {
        return users.findByStructureIdOrderByLastNameAscFirstNameAsc(structureId).stream().filter(AppUser::isActive).toList();
    }

    // ---------------------------------------------------------------- exceptions

    @Transactional
    public ShiftDto setOverride(UUID userId, LocalDate day, OverrideRequest r) {
        var user = loadUser(userId);
        requireOpen(user.getStructureId(), day);
        checkSection(user.getStructureId(), r.sectionId());
        var override = overrides.findByUserIdAndDay(userId, day)
                .orElseGet(() -> new StaffShiftOverride(user.getStructureId(), userId, day, Instant.now(clock)));
        override.update(r.startsAt(), r.endsAt(), r.breakMinutes(), r.sectionId(), StaffService.blank(r.note()),
                CurrentUser.get().displayName());
        overrides.saveAndFlush(override);
        events.publishEvent(new StaffingChanged(user.getStructureId()));
        audit.record(AuditAction.STAFF_SHIFT_CHANGED, "user", userId, Map.of("day", day.toString(),
                "shift", r.startsAt() == null ? "repos" : Formats.time(r.startsAt()) + "-" + Formats.time(r.endsAt())));
        return shiftOf(user, day);
    }

    /** Retour à la semaine type pour ce jour. */
    @Transactional
    public ShiftDto resetOverride(UUID userId, LocalDate day) {
        var user = loadUser(userId);
        requireOpen(user.getStructureId(), day);
        overrides.findByUserIdAndDay(userId, day).ifPresent(o -> {
            overrides.delete(o);
            overrides.flush();
            audit.record(AuditAction.STAFF_SHIFT_RESET, "user", userId, Map.of("day", day.toString()));
        });
        events.publishEvent(new StaffingChanged(user.getStructureId()));
        return shiftOf(user, day);
    }

    private ShiftDto shiftOf(AppUser user, LocalDate day) {
        var shift = shifts(user.getStructureId(), List.of(user), day, day).get(user.getId()).getFirst();
        return shiftDto(shift, absenceIds(user.getStructureId(), day, day).get(user.getId() + "|" + day));
    }

    // ---------------------------------------------------------------- absences

    @Transactional(readOnly = true)
    public List<AbsenceDto> absences(LocalDate from, LocalDate to) {
        var structureId = CurrentUser.get().structureId();
        var names = staff(structureId).stream().collect(Collectors.toMap(AppUser::getId, AppUser::displayName));
        return absences.findByStructureIdAndFromDayLessThanEqualAndToDayGreaterThanEqual(structureId, to, from).stream()
                .sorted(Comparator.comparing(StaffAbsence::getFromDay))
                .map(a -> absenceDto(a, names.getOrDefault(a.getUserId(), ""))).toList();
    }

    @Transactional
    public AbsenceDto createAbsence(AbsenceRequest r) {
        var user = loadUser(r.userId());
        var absence = new StaffAbsence(user.getStructureId(), user.getId(), Instant.now(clock));
        apply(user, absence, r);
        audit.record(AuditAction.STAFF_ABSENCE_RECORDED, "staff_absence", absence.getId(), details(user, absence));
        return absenceDto(absence, user.displayName());
    }

    @Transactional
    public AbsenceDto updateAbsence(UUID id, AbsenceRequest r) {
        var absence = loadAbsence(id);
        var user = loadUser(absence.getUserId());
        requireOpen(user.getStructureId(), absence.getFromDay(), absence.getToDay());
        apply(user, absence, r);
        audit.record(AuditAction.STAFF_ABSENCE_UPDATED, "staff_absence", id, details(user, absence));
        return absenceDto(absence, user.displayName());
    }

    @Transactional
    public void deleteAbsence(UUID id) {
        var absence = loadAbsence(id);
        var user = loadUser(absence.getUserId());
        requireOpen(user.getStructureId(), absence.getFromDay(), absence.getToDay());
        absences.delete(absence);
        absences.flush();
        events.publishEvent(new StaffingChanged(user.getStructureId()));
        audit.record(AuditAction.STAFF_ABSENCE_DELETED, "staff_absence", id, details(user, absence));
    }

    private void apply(AppUser user, StaffAbsence absence, AbsenceRequest r) {
        if (!r.userId().equals(absence.getUserId())) {
            throw ApiException.rule("absence_user", "Une absence ne change pas de personne : supprimez-la et recréez-la.");
        }
        if (r.toDay().isAfter(r.fromDay().plusDays(366))) {
            throw ApiException.rule("absence_period", "Une absence couvre au plus un an.");
        }
        requireOpen(user.getStructureId(), r.fromDay(), r.toDay());
        boolean overlap = absences.findByUserIdOrderByFromDayDesc(user.getId()).stream()
                .filter(o -> !Objects.equals(o.getId(), absence.getId()))
                .anyMatch(o -> !o.getFromDay().isAfter(r.toDay()) && !o.getToDay().isBefore(r.fromDay()));
        if (overlap) {
            throw ApiException.rule("absence_overlap", "Une absence est déjà saisie sur une partie de ces jours.");
        }
        absence.update(r.kind(), r.fromDay(), r.toDay(), r.halfDay(), StaffService.blank(r.note()),
                CurrentUser.get().displayName());
        absences.saveAndFlush(absence);
        events.publishEvent(new StaffingChanged(user.getStructureId()));
    }

    private static Map<String, Object> details(AppUser user, StaffAbsence a) {
        return Map.of("user", user.displayName(), "kind", a.getKind().name(), "from", a.getFromDay().toString(),
                "to", a.getToDay().toString());
    }

    private static AbsenceDto absenceDto(StaffAbsence a, String name) {
        return new AbsenceDto(a.getId(), a.getUserId(), name, a.getKind(), a.getFromDay(), a.getToDay(), a.isHalfDay(),
                a.getNote(), a.getAuthor(), a.getVersion());
    }

    private StaffAbsence loadAbsence(UUID id) {
        return absences.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Absence introuvable."));
    }

    // ---------------------------------------------------------------- encadrement prévisionnel

    @Transactional(readOnly = true)
    public Forecast forecast(LocalDate day) {
        return forecast(CurrentUser.get().structureId(), day);
    }

    Forecast forecast(UUID structureId, LocalDate day) {
        var structure = structures.findById(structureId).orElseThrow();
        var sectionList = sections.findByStructureIdOrderByDisplayOrderAscMinAgeMonthsAsc(structureId);
        var sectionDtos = sectionList.stream().map(s -> new SectionDto(s.getId(), s.getName(), s.getColor())).toList();
        var hours = structure.getOpeningHours().stream().filter(h -> h.dayOfWeek() == day.getDayOfWeek().getValue())
                .findFirst().orElse(null);
        if (hours == null) {
            return new Forecast(day, false, sectionDtos, List.of(), 0, 0);
        }
        var rules = ratioRules.findByStructureIdOrderByMinAgeMonthsAscValidFromDesc(structureId).stream()
                .map(RatioRule::toRule).toList();
        var absent = childAbsences.overlapping(structureId, day, day).stream()
                .map(a -> a.getChildId()).collect(Collectors.toSet());
        var childById = children.findByStructureId(structureId).stream()
                .collect(Collectors.toMap(Child::getId, Function.identity()));
        var expected = new ArrayList<ExpectedChild>();
        int variable = 0;
        for (var entry : plannedDays.forDay(structureId, day).entrySet()) {
            var child = childById.get(entry.getKey());
            if (child == null || absent.contains(child.getId()) || !child.isEnrolledOn(day)) {
                continue;
            }
            if (!entry.getValue().expected()) {
                variable++;
                continue;
            }
            expected.add(new ExpectedChild(child.getId(), child.getSectionId(), child.getBirthDate(),
                    entry.getValue().arrivesAt(), entry.getValue().leavesAt()));
        }
        var staff = staff(structureId).stream().filter(AppUser::isCountsInRatio).toList();
        var adults = shifts(structureId, staff, day, day).entrySet().stream()
                .map(e -> e.getValue().getFirst())
                .filter(Shift::working)
                .map(s -> new PlannedAdult(null, s.sectionId(), s.startsAt(), s.effectiveEnd()))
                .toList();
        var slots = ForecastCalculator.compute(day, hours.opensAt(), hours.closesAt(), SLOT_MINUTES, rules,
                sectionList.stream().map(Section::getId).toList(), expected, adults);
        var dtos = slots.stream().map(s -> new SlotDto(s.startsAt(), s.endsAt(),
                s.sections().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> load(e.getValue()))),
                load(s.global()))).toList();
        int shortages = (int) slots.stream().filter(s -> s.global().status() == Status.INSUFFISANT
                || s.sections().values().stream().anyMatch(l -> l.status() == Status.INSUFFISANT)).count();
        return new Forecast(day, true, sectionDtos, dtos, shortages, variable);
    }

    private static LoadDto load(Load l) {
        return new LoadDto(l.children(), l.adults(), l.required(), l.status());
    }

    // ---------------------------------------------------------------- contrôles

    private void requireOpen(UUID structureId, LocalDate day) {
        requireOpen(structureId, day, day);
    }

    private void requireOpen(UUID structureId, LocalDate from, LocalDate to) {
        for (var month = YearMonth.from(from); !month.isAfter(YearMonth.from(to)); month = month.plusMonths(1)) {
            if (validatedMonths.findByStructureIdAndMonth(structureId, month.atDay(1)).isPresent()) {
                throw ApiException.rule("timesheet_validated",
                        "Les heures de %s sont validées : rouvrez le mois pour modifier le planning."
                                .formatted(Formats.monthYear(month)));
            }
        }
    }

    /** Une semaine type qui commence dans un mois validé changerait ses heures prévues. */
    private void requireOpenFrom(UUID structureId, LocalDate from) {
        requireOpen(structureId, from, from);
    }

    private void checkSection(UUID structureId, UUID sectionId) {
        if (sectionId != null && sections.findByIdAndStructureId(sectionId, structureId).isEmpty()) {
            throw ApiException.notFound("Section introuvable.");
        }
    }

    private AppUser loadUser(UUID userId) {
        return users.findByIdAndStructureId(userId, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Personne introuvable."));
    }
}
