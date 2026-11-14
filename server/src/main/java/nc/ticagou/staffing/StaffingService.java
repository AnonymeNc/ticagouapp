package nc.ticagou.staffing;

import nc.ticagou.notification.NativePush.Message;
import nc.ticagou.notification.NotificationService;
import nc.ticagou.notification.NotificationService.Category;
import nc.ticagou.notification.PushProperties;

import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.attendance.ChildAttendance;
import nc.ticagou.attendance.ChildAttendanceRepository;
import nc.ticagou.attendance.StaffAttendance;
import nc.ticagou.attendance.StaffAttendanceRepository;
import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.audit.AuditService.Actor;
import nc.ticagou.auth.AppUser;
import nc.ticagou.auth.AppUserRepository;
import nc.ticagou.child.Child;
import nc.ticagou.child.ChildRepository;
import nc.ticagou.config.TiCagouProperties;
import nc.ticagou.staffing.RatioCalculator.PresentAdult;
import nc.ticagou.staffing.RatioCalculator.PresentChild;
import nc.ticagou.staffing.RatioCalculator.Status;
import nc.ticagou.staffing.StaffingDtos.AlertDto;
import nc.ticagou.staffing.StaffingDtos.LoadDto;
import nc.ticagou.staffing.StaffingDtos.SectionLoadDto;
import nc.ticagou.staffing.StaffingDtos.StaffMember;
import nc.ticagou.staffing.StaffingDtos.StaffingDto;
import nc.ticagou.structure.SectionRepository;
import nc.ticagou.structure.StructureRepository;

/**
 * Taux d'encadrement à l'instant présent : enfants pointés présents, adultes présents qui comptent dans
 * l'encadrement, règles valides aujourd'hui. Les dépassements sont tracés dans {@code ratio_breach}.
 */
@Service
public class StaffingService {

    private final RatioRuleRepository rules;
    private final RatioBreachRepository breaches;
    private final ChildAttendanceRepository childAttendances;
    private final StaffAttendanceRepository staffAttendances;
    private final ChildRepository children;
    private final AppUserRepository users;
    private final SectionRepository sections;
    private final StructureRepository structures;
    private final AuditService audit;
    private final ZoneId zone;
    private final Clock clock;
    private final NotificationService notifications;
    private final java.time.Duration ratioDelay;

    public StaffingService(RatioRuleRepository rules, RatioBreachRepository breaches,
                           ChildAttendanceRepository childAttendances, StaffAttendanceRepository staffAttendances,
                           ChildRepository children, AppUserRepository users, SectionRepository sections,
                           StructureRepository structures, AuditService audit, TiCagouProperties properties,
                           Clock clock, NotificationService notifications, PushProperties push) {
        this.notifications = notifications;
        this.ratioDelay = push.ratioDelay();
        this.rules = rules;
        this.breaches = breaches;
        this.childAttendances = childAttendances;
        this.staffAttendances = staffAttendances;
        this.children = children;
        this.users = users;
        this.sections = sections;
        this.structures = structures;
        this.audit = audit;
        this.zone = properties.zone();
        this.clock = clock;
    }

    /** Nouvelle transaction : appelé aussi après validation d'une autre transaction (diffusion). */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public StaffingDto snapshot(UUID structureId) {
        var now = clock.instant();
        var day = now.atZone(zone).toLocalDate();
        var structure = structures.findById(structureId).orElseThrow();
        var ruleList = rules.findByStructureIdOrderByMinAgeMonthsAscValidFromDesc(structureId).stream()
                .map(RatioRule::toRule).toList();
        var sectionList = sections.findByStructureIdOrderByDisplayOrderAscMinAgeMonthsAsc(structureId);
        Map<UUID, Child> childById = children.findByStructureId(structureId).stream()
                .collect(Collectors.toMap(Child::getId, Function.identity()));
        Map<UUID, AppUser> userById = users.findByStructureIdOrderByLastNameAscFirstNameAsc(structureId).stream()
                .collect(Collectors.toMap(AppUser::getId, Function.identity()));

        var presentChildren = new ArrayList<PresentChild>();
        for (ChildAttendance a : childAttendances.findByStructureIdAndDayAndDepartedAtIsNull(structureId, day)) {
            var child = childById.get(a.getChildId());
            if (child != null) {
                presentChildren.add(new PresentChild(a.getSectionId() != null ? a.getSectionId() : child.getSectionId(),
                        child.getBirthDate()));
            }
        }
        var adults = new ArrayList<PresentAdult>();
        var staffBySection = new HashMap<UUID, List<StaffMember>>();
        for (StaffAttendance a : staffAttendances.findByStructureIdAndDayAndDepartedAtIsNull(structureId, day)) {
            var user = userById.get(a.getUserId());
            if (user != null && user.isCountsInRatio()) {
                adults.add(new PresentAdult(a.getSectionId()));
                staffBySection.computeIfAbsent(a.getSectionId(), k -> new ArrayList<>())
                        .add(new StaffMember(user.getId(), user.shortName(), user.initials()));
            }
        }
        var result = RatioCalculator.compute(day, ruleList,
                sectionList.stream().map(s -> s.getId()).toList(), presentChildren, adults);
        var sectionLoads = sectionList.stream()
                .map(s -> new SectionLoadDto(s.getId(), s.getName(), s.getColor(),
                        LoadDto.from(result.sections().get(s.getId())), staffBySection.getOrDefault(s.getId(), List.of())))
                .toList();
        var ruleLabels = ruleList.stream().filter(r -> r.isValidOn(day))
                .map(r -> "1 adulte pour %d %s".formatted(r.childrenPerAdult(), r.label().toLowerCase()))
                .toList();
        return new StaffingDto(now, day, LoadDto.from(result.global()), sectionLoads, ruleLabels,
                structure.isRatioAlertDesktop());
    }

    /**
     * Ouvre ou ferme les dépassements d'après l'instantané ; renvoie les franchissements de seuil à diffuser.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<AlertDto> track(UUID structureId, StaffingDto snapshot) {
        var open = breaches.findByStructureIdAndEndedAtIsNull(structureId).stream()
                .collect(Collectors.toMap(b -> Objects.requireNonNullElse(b.getSectionId(), GLOBAL), Function.identity()));
        var alerts = new ArrayList<AlertDto>();
        var loads = new ArrayList<Map.Entry<UUID, Map.Entry<String, LoadDto>>>();
        loads.add(Map.entry(GLOBAL, Map.entry("Toute la structure", snapshot.global())));
        for (var s : snapshot.sections()) {
            loads.add(Map.entry(s.sectionId(), Map.entry(s.name(), s.load())));
        }
        for (var entry : loads) {
            var key = entry.getKey();
            var name = entry.getValue().getKey();
            var load = entry.getValue().getValue();
            var breach = open.remove(key);
            UUID sectionId = key.equals(GLOBAL) ? null : key;
            if (load.status() == Status.INSUFFISANT) {
                if (breach == null) {
                    breaches.save(new RatioBreach(structureId, sectionId, name, snapshot.at(), load.children(),
                            load.adults(), load.required()));
                    audit.record(new Actor(structureId, null, null), AuditAction.RATIO_BREACH_STARTED, "section",
                            sectionId, Map.of("section", name, "children", load.children(), "adults", load.adults(),
                                    "required", load.required()));
                    alerts.add(new AlertDto(sectionId, name, true, load.children(), load.adults(), load.required(),
                            snapshot.at()));
                } else {
                    breach.observe(load.children(), load.adults(), load.required());
                    if (breach.dueForNotification(snapshot.at(), ratioDelay)) {
                        // Aucun nom d’enfant ni donnée de santé : la section et le taux seulement (ADR 0026).
                        notifications.notifyDirection(structureId, Category.ENCADREMENT, new Message(
                                "Encadrement insuffisant",
                                "%s : %d adulte%s pour %d enfant%s".formatted(name, load.adults(), load.adults() > 1 ? "s" : "",
                                        load.children(), load.children() > 1 ? "s" : ""),
                                "/", Category.ENCADREMENT.name(), NotificationService.ALERT_TTL, "ratio-" + key));
                    }
                }
            } else if (breach != null) {
                breach.end(snapshot.at());
                audit.record(new Actor(structureId, null, null), AuditAction.RATIO_BREACH_ENDED, "section", sectionId,
                        Map.of("section", name));
                alerts.add(new AlertDto(sectionId, name, false, load.children(), load.adults(), load.required(),
                        snapshot.at()));
            }
        }
        // Section supprimée pendant un dépassement : on le referme.
        open.values().forEach(b -> b.end(snapshot.at()));
        return alerts;
    }

    @Transactional(readOnly = true)
    public List<UUID> structureIds() {
        return structures.findAll().stream().map(s -> s.getId()).toList();
    }

    /** Clé du dépassement global (section vide). */
    private static final UUID GLOBAL = new UUID(0, 0);
}
