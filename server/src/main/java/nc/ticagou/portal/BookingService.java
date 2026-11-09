package nc.ticagou.portal;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.attendance.PlannedDays;
import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.child.Child;
import nc.ticagou.child.ChildRepository;
import nc.ticagou.common.ApiException;
import nc.ticagou.portal.PortalDtos.BookingDto;
import nc.ticagou.portal.PortalDtos.BookingRules;
import nc.ticagou.portal.SyncDtos.BookingIn;
import nc.ticagou.structure.SectionRepository;

/**
 * Demandes des parents (lot 2) : accueils occasionnels et retards annoncés. Une demande hors des règles datées
 * (délai minimum, horizon) est refusée d'office avec son motif ; une demande acceptée rend l'enfant attendu sur la
 * tablette ({@link PlannedDays}) et le temps passé est facturé en heures complémentaires, comme tout accueil hors
 * planning. Accepter au-delà des places de la section exige un motif, tracé.
 */
@Service
public class BookingService {

    private static final ZoneId NOUMEA = ZoneId.of("Pacific/Noumea");
    /** Règles appliquées tant que la direction n'en a pas réglé : 24 h de délai, 60 jours d'horizon. */
    static final BookingRules DEFAULT_RULES = new BookingRules(24, 60, null);

    private final BookingRequestRepository bookings;
    private final ChildRepository children;
    private final SectionRepository sections;
    private final PlannedDays plannedDays;
    private final PortalPublisher publisher;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public BookingService(BookingRequestRepository bookings, ChildRepository children, SectionRepository sections,
                          PlannedDays plannedDays, PortalPublisher publisher, AuditService audit, JdbcTemplate jdbc,
                          Clock clock) {
        this.bookings = bookings;
        this.children = children;
        this.sections = sections;
        this.plannedDays = plannedDays;
        this.publisher = publisher;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // --- Réception depuis le portail ----------------------------------------------------------

    /** Demande remontée du portail ; faux si elle est ignorée (déjà reçue, enfant d'une autre famille). */
    @Transactional
    public boolean received(UUID structureId, UUID itemId, UUID familyId, BookingIn in, String guardianName) {
        if (bookings.existsById(itemId) || in.childId() == null || in.day() == null || in.leavesAt() == null) {
            return false;
        }
        var child = children.findByIdAndStructureId(in.childId(), structureId).filter(c -> c.getFamilyId().equals(familyId));
        if (child.isEmpty()) {
            return false;
        }
        var kind = "RETARD".equals(in.kind()) ? BookingRequest.Kind.RETARD : BookingRequest.Kind.RESERVATION;
        if (kind == BookingRequest.Kind.RESERVATION && (in.arrivesAt() == null || !in.arrivesAt().isBefore(in.leavesAt()))) {
            return false;
        }
        var booking = new BookingRequest(itemId, structureId, familyId, in.childId(), kind, in.day(),
                kind == BookingRequest.Kind.RETARD ? null : in.arrivesAt(), in.leavesAt(), clip(in.note()), guardianName,
                in.requestedAt() == null ? clock.instant() : in.requestedAt());
        if (kind == BookingRequest.Kind.RESERVATION) {
            var refusal = outsideRules(structureId, booking);
            if (refusal != null) {
                booking.answer(BookingRequest.Status.REFUSEE, refusal, false, "Règle de la crèche", clock.instant());
            }
        }
        bookings.save(booking);
        if (kind == BookingRequest.Kind.RESERVATION) {
            publisher.on(new PortalChange(structureId, PortalItemType.BOOKING, booking.getId()));
        }
        return true;
    }

    private String outsideRules(UUID structureId, BookingRequest b) {
        var now = LocalDateTime.now(clock.withZone(NOUMEA));
        var rules = rules(structureId, now.toLocalDate());
        var start = b.getDay().atTime(b.getArrivesAt());
        if (Duration.between(now, start).toHours() < rules.minNoticeHours()) {
            return "Demande trop tardive : prévenez la crèche au moins %d h avant l’accueil.".formatted(rules.minNoticeHours());
        }
        if (b.getDay().isAfter(now.toLocalDate().plusDays(rules.maxDaysAhead()))) {
            return "La crèche prend les demandes jusqu’à %d jours à l’avance.".formatted(rules.maxDaysAhead());
        }
        return null;
    }

    // --- Bureau --------------------------------------------------------------------------------

    /** Demandes à traiter et retards annoncés pour aujourd'hui et la suite. */
    @Transactional(readOnly = true)
    public List<BookingDto> open() {
        var structureId = CurrentUser.get().structureId();
        var today = LocalDate.now(clock.withZone(NOUMEA));
        var names = new HashMap<UUID, String>();
        return bookings.findByStructureIdAndDayGreaterThanEqualOrderByDayAscRequestedAtAsc(structureId, today).stream()
                .filter(b -> b.getStatus() == BookingRequest.Status.DEMANDEE
                        || (b.getKind() == BookingRequest.Kind.RETARD && b.getDay().equals(today)))
                .map(b -> dto(b, names)).toList();
    }

    @Transactional
    public BookingDto accept(UUID id, String overrideReason) {
        var user = CurrentUser.get();
        var booking = pending(id);
        var free = freePlaces(booking);
        boolean over = free != null && free <= 0;
        if (over && (overrideReason == null || overrideReason.isBlank())) {
            throw ApiException.rule("over_capacity", "La section est complète ce jour-là : indiquez pourquoi vous acceptez quand même.");
        }
        booking.answer(BookingRequest.Status.ACCEPTEE, null, over, user.displayName(), clock.instant());
        publisher.on(new PortalChange(user.structureId(), PortalItemType.BOOKING, id));
        audit.record(AuditAction.BOOKING_ACCEPTED, "booking", id, over
                ? Map.of("overCapacity", true, "reason", overrideReason.strip()) : Map.of());
        return dto(booking, new HashMap<>());
    }

    @Transactional
    public BookingDto refuse(UUID id, String reason) {
        var user = CurrentUser.get();
        var booking = pending(id);
        if (reason == null || reason.isBlank()) {
            throw ApiException.rule("refusal_reason", "Indiquez le motif du refus : il est transmis à la famille.");
        }
        booking.answer(BookingRequest.Status.REFUSEE, clip(reason.strip()), false, user.displayName(), clock.instant());
        publisher.on(new PortalChange(user.structureId(), PortalItemType.BOOKING, id));
        audit.record(AuditAction.BOOKING_REFUSED, "booking", id, Map.of());
        return dto(booking, new HashMap<>());
    }

    /**
     * Places libres dans la section de l'enfant, sur le créneau demandé : capacité moins les enfants attendus (contrat
     * ou demande acceptée) dont l'accueil chevauche ce créneau. {@code null} si l'enfant n'a pas de section.
     */
    Integer freePlaces(BookingRequest booking) {
        var child = children.findById(booking.getChildId()).orElseThrow();
        if (child.getSectionId() == null) {
            return null;
        }
        var section = sections.findById(child.getSectionId()).orElseThrow();
        var planned = plannedDays.forDay(booking.getStructureId(), booking.getDay());
        var inSection = new HashMap<UUID, Child>();
        children.findByStructureId(booking.getStructureId()).stream()
                .filter(c -> section.getId().equals(c.getSectionId()) && !c.getId().equals(child.getId()))
                .forEach(c -> inSection.put(c.getId(), c));
        long taken = planned.entrySet().stream().filter(e -> inSection.containsKey(e.getKey()))
                .filter(e -> e.getValue().variable() || overlaps(e.getValue(), booking)).count();
        return section.getCapacity() - (int) taken;
    }

    private static boolean overlaps(PlannedDays.Planned p, BookingRequest b) {
        return p.arrivesAt() == null || (p.arrivesAt().isBefore(b.getLeavesAt()) && b.getArrivesAt().isBefore(p.leavesAt()));
    }

    // --- Règles datées ------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public BookingRules rules() {
        return rules(CurrentUser.get().structureId(), LocalDate.now(clock.withZone(NOUMEA)));
    }

    BookingRules rules(UUID structureId, LocalDate day) {
        return jdbc.query("SELECT min_notice_hours, max_days_ahead, valid_from FROM booking_rule WHERE structure_id = ? "
                        + "AND valid_from <= ? AND (valid_to IS NULL OR valid_to >= ?) ORDER BY valid_from DESC LIMIT 1",
                (rs, i) -> new BookingRules(rs.getInt(1), rs.getInt(2), rs.getDate(3).toLocalDate()),
                structureId, Date.valueOf(day), Date.valueOf(day)).stream().findFirst().orElse(DEFAULT_RULES);
    }

    /** Nouvelles règles à partir d'aujourd'hui ; les précédentes s'arrêtent la veille (historique gardé). */
    @Transactional
    public BookingRules updateRules(int minNoticeHours, int maxDaysAhead) {
        var structureId = CurrentUser.get().structureId();
        var today = LocalDate.now(clock.withZone(NOUMEA));
        jdbc.update("DELETE FROM booking_rule WHERE structure_id = ? AND valid_from = ?", structureId, Date.valueOf(today));
        jdbc.update("UPDATE booking_rule SET valid_to = ? WHERE structure_id = ? AND valid_to IS NULL",
                Date.valueOf(today.minusDays(1)), structureId);
        jdbc.update("INSERT INTO booking_rule (id, structure_id, valid_from, min_notice_hours, max_days_ahead, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), structureId, Date.valueOf(today), minNoticeHours,
                maxDaysAhead, Timestamp.from(clock.instant()));
        audit.record(AuditAction.BOOKING_RULES_UPDATED, "structure", structureId,
                Map.of("minNoticeHours", minNoticeHours, "maxDaysAhead", maxDaysAhead));
        return rules(structureId, today);
    }

    // --- Outils -------------------------------------------------------------------------------

    private BookingRequest pending(UUID id) {
        var booking = bookings.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Demande introuvable."));
        if (booking.getStatus() != BookingRequest.Status.DEMANDEE) {
            throw ApiException.rule("booking_handled", "Cette demande a déjà reçu une réponse.");
        }
        return booking;
    }

    private BookingDto dto(BookingRequest b, Map<UUID, String> names) {
        var childName = names.computeIfAbsent(b.getChildId(), id -> children.findById(id)
                .map(c -> c.getFirstName() + " " + c.getLastName()).orElse(null));
        Integer free = b.getStatus() == BookingRequest.Status.DEMANDEE ? freePlaces(b) : null;
        return new BookingDto(b.getId(), b.getChildId(), childName, b.getFamilyId(), b.getKind(), b.getDay(),
                b.getArrivesAt(), b.getLeavesAt(), b.getNote(), b.getGuardianName(), b.getRequestedAt(), b.getStatus(),
                b.getAnswer(), b.isOverCapacity(), free);
    }

    private static String clip(String value) {
        return value == null || value.isBlank() ? null : value.strip().length() > 300 ? value.strip().substring(0, 300) : value.strip();
    }
}
