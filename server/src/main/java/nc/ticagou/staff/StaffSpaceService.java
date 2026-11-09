package nc.ticagou.staff;

import java.security.SecureRandom;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.AppUserRepository;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.common.ApiException;
import nc.ticagou.portal.PortalSigner;
import nc.ticagou.staff.Shifts.AbsenceKind;
import nc.ticagou.staff.StaffDtos.AbsenceRequest;

/**
 * Espace pro (lot 4) : demandes de congé, de récupération et d'heures supplémentaires avec la réponse de la direction,
 * compteurs de l'année, cahier d'équipe et agenda partagé. Une demande de congé acceptée devient une absence du
 * planning ; la réponse signale les jours où l'encadrement prévu devient insuffisant.
 */
@Service
public class StaffSpaceService {

    public enum RequestKind { CONGES, RECUPERATION, HEURES_SUP }

    public record StaffRequestDto(UUID id, UUID userId, String userName, RequestKind kind, LocalDate fromDay, LocalDate toDay,
                                  Integer minutes, String note, String status, String answer, java.time.Instant requestedAt,
                                  String handledBy) {
    }

    /** Réponse de la direction : la demande, et les jours devenus en sous-effectif (congé ou récupération acceptés). */
    public record Answer(StaffRequestDto request, List<LocalDate> shortageDays) {
    }

    /** Compteurs de l'année : jours de congé pris, heures supplémentaires acceptées, heures récupérées, solde. */
    public record Counters(int year, double leaveDaysTaken, int overtimeMinutes, int recoveredMinutes, int balanceMinutes) {
    }

    public record TeamNote(UUID id, UUID sectionId, String body, String authorName, java.time.Instant createdAt, boolean read,
                           int readers) {
    }

    private static final ZoneId NOUMEA = ZoneId.of("Pacific/Noumea");
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Une journée de récupération compte pour 7 h 30, la durée de référence d'une journée (à paramétrer plus tard). */
    static final int RECOVERY_DAY_MINUTES = 450;

    private final JdbcTemplate jdbc;
    private final PlanningService planning;
    private final AppUserRepository users;
    private final AuditService audit;
    private final Clock clock;

    public StaffSpaceService(JdbcTemplate jdbc, PlanningService planning, AppUserRepository users, AuditService audit,
                             Clock clock) {
        this.jdbc = jdbc;
        this.planning = planning;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    // --- Demandes ------------------------------------------------------------------------------

    @Transactional
    public StaffRequestDto request(RequestKind kind, LocalDate from, LocalDate to, Integer minutes, String note) {
        var user = CurrentUser.get();
        var today = LocalDate.now(clock.withZone(NOUMEA));
        if (to.isBefore(from)) {
            throw ApiException.rule("request_dates", "La fin ne peut pas précéder le début.");
        }
        if (kind == RequestKind.HEURES_SUP) {
            if (minutes == null || minutes < 15 || minutes > 720 || !from.equals(to)) {
                throw ApiException.rule("overtime_minutes", "Indiquez les heures supplémentaires d’un jour (entre 15 min et 12 h).");
            }
            if (from.isAfter(today)) {
                throw ApiException.rule("overtime_future", "Les heures supplémentaires se déclarent une fois faites.");
            }
        } else if (from.isBefore(today)) {
            throw ApiException.rule("request_past", "Un congé ou une récupération se demande à l’avance.");
        } else if (from.plusDays(62).isBefore(to)) {
            throw ApiException.rule("request_dates", "Deux mois au plus par demande.");
        }
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO staff_request (id, structure_id, user_id, kind, from_day, to_day, minutes, note, status, requested_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DEMANDEE', ?)", id, user.structureId(), user.userId(), kind.name(),
                Date.valueOf(from), Date.valueOf(to), kind == RequestKind.HEURES_SUP ? minutes : null, clip(note, 300),
                Timestamp.from(clock.instant()));
        return find(id);
    }

    public List<StaffRequestDto> mine() {
        var user = CurrentUser.get();
        return jdbc.query(SELECT + "WHERE r.user_id = ? ORDER BY r.from_day DESC LIMIT 100", (rs, i) -> dto(rs), user.userId());
    }

    public List<StaffRequestDto> pending() {
        return jdbc.query(SELECT + "WHERE r.structure_id = ? AND r.status = 'DEMANDEE' ORDER BY r.from_day",
                (rs, i) -> dto(rs), CurrentUser.get().structureId());
    }

    @Transactional
    public void cancel(UUID id) {
        var user = CurrentUser.get();
        int changed = jdbc.update("UPDATE staff_request SET status = 'ANNULEE', handled_at = ? WHERE id = ? AND user_id = ? "
                + "AND status = 'DEMANDEE'", Timestamp.from(clock.instant()), id, user.userId());
        if (changed == 0) {
            throw ApiException.rule("request_handled", "Cette demande a déjà reçu une réponse.");
        }
    }

    @Transactional
    public Answer accept(UUID id) {
        var user = CurrentUser.get();
        var request = pendingOne(id);
        UUID absenceId = null;
        var shortages = new ArrayList<LocalDate>();
        if (request.kind() != RequestKind.HEURES_SUP) {
            var absence = planning.createAbsence(new AbsenceRequest(request.userId(),
                    request.kind() == RequestKind.CONGES ? AbsenceKind.CONGES : AbsenceKind.RECUPERATION,
                    request.fromDay(), request.toDay(), false, request.note()));
            absenceId = absence.id();
            for (var day = request.fromDay(); !day.isAfter(request.toDay()); day = day.plusDays(1)) {
                if (planning.forecast(user.structureId(), day).shortages() > 0) {
                    shortages.add(day);
                }
            }
        }
        jdbc.update("UPDATE staff_request SET status = 'ACCEPTEE', absence_id = ?, handled_by = ?, handled_at = ? WHERE id = ?",
                absenceId, user.displayName(), Timestamp.from(clock.instant()), id);
        audit.record(AuditAction.STAFF_REQUEST_ANSWERED, "staff_request", id,
                Map.of("status", "ACCEPTEE", "shortageDays", shortages.size()));
        return new Answer(find(id), shortages);
    }

    @Transactional
    public StaffRequestDto refuse(UUID id, String reason) {
        var user = CurrentUser.get();
        pendingOne(id);
        if (reason == null || reason.isBlank()) {
            throw ApiException.rule("refusal_reason", "Indiquez le motif du refus.");
        }
        jdbc.update("UPDATE staff_request SET status = 'REFUSEE', answer = ?, handled_by = ?, handled_at = ? WHERE id = ?",
                clip(reason, 300), user.displayName(), Timestamp.from(clock.instant()), id);
        audit.record(AuditAction.STAFF_REQUEST_ANSWERED, "staff_request", id, Map.of("status", "REFUSEE"));
        return find(id);
    }

    /** Compteurs de l'année en cours, pour soi (ou, pour la direction, un membre du personnel). */
    public Counters counters(UUID userId) {
        var current = CurrentUser.get();
        var target = userId == null ? current.userId() : userId;
        if (!target.equals(current.userId()) && current.role() != nc.ticagou.auth.Role.DIRECTION) {
            throw ApiException.forbidden("not_allowed", "Seule la direction voit les compteurs des autres.");
        }
        var year = LocalDate.now(clock.withZone(NOUMEA)).getYear();
        var from = Date.valueOf(LocalDate.of(year, 1, 1));
        var to = Date.valueOf(LocalDate.of(year, 12, 31));
        double leave = jdbc.queryForObject("SELECT COALESCE(sum(CASE WHEN half_day THEN 0.5 ELSE (LEAST(to_day, ?) - GREATEST(from_day, ?) + 1) END), 0) "
                + "FROM staff_absence WHERE user_id = ? AND kind = 'CONGES' AND to_day >= ? AND from_day <= ?", Double.class,
                to, from, target, from, to);
        int overtime = jdbc.queryForObject("SELECT COALESCE(sum(minutes), 0) FROM staff_request WHERE user_id = ? AND kind = 'HEURES_SUP' "
                + "AND status = 'ACCEPTEE' AND from_day BETWEEN ? AND ?", Integer.class, target, from, to);
        int recoveredDays = jdbc.queryForObject("SELECT COALESCE(sum(LEAST(to_day, ?) - GREATEST(from_day, ?) + 1), 0) FROM staff_absence "
                + "WHERE user_id = ? AND kind = 'RECUPERATION' AND to_day >= ? AND from_day <= ?", Integer.class, to, from, target, from, to);
        int recovered = recoveredDays * RECOVERY_DAY_MINUTES;
        return new Counters(year, leave, overtime, recovered, overtime - recovered);
    }

    // --- Cahier d'équipe ----------------------------------------------------------------------

    public List<TeamNote> notes() {
        var user = CurrentUser.get();
        var since = Timestamp.from(clock.instant().minus(60, ChronoUnit.DAYS));
        return jdbc.query("SELECT n.id, n.section_id, n.body, n.author_name, n.created_at, "
                        + "EXISTS (SELECT 1 FROM team_note_read r WHERE r.note_id = n.id AND r.user_id = ?) AS read, "
                        + "(SELECT count(*) FROM team_note_read r WHERE r.note_id = n.id) AS readers "
                        + "FROM team_note n WHERE n.structure_id = ? AND n.created_at >= ? ORDER BY n.created_at DESC LIMIT 200",
                (rs, i) -> new TeamNote(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant(), rs.getBoolean(6), rs.getInt(7)),
                user.userId(), user.structureId(), since);
    }

    @Transactional
    public TeamNote writeNote(UUID sectionId, String body) {
        var user = CurrentUser.get();
        var text = body == null ? "" : body.strip();
        if (text.isEmpty() || text.length() > 1000) {
            throw ApiException.rule("note_body", "Écrivez la note (1 000 caractères au plus).");
        }
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO team_note (id, structure_id, section_id, body, author_id, author_name, created_at) "
                        + "VALUES (?, ?, (SELECT id FROM section WHERE id = ? AND structure_id = ?), ?, ?, ?, ?)", id, user.structureId(),
                sectionId, user.structureId(), text, user.userId(), user.displayName(), Timestamp.from(clock.instant()));
        markRead(id);
        return notes().stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void markRead(UUID noteId) {
        var user = CurrentUser.get();
        jdbc.update("INSERT INTO team_note_read (note_id, user_id, read_at) SELECT id, ?, ? FROM team_note "
                + "WHERE id = ? AND structure_id = ? ON CONFLICT DO NOTHING", user.userId(), Timestamp.from(clock.instant()),
                noteId, user.structureId());
    }

    // --- Agenda partagé -----------------------------------------------------------------------

    /** Nouveau lien privé (l'ancien cesse de marcher) ; seule l'empreinte est gardée. */
    @Transactional
    public String newCalendarToken() {
        var user = CurrentUser.get();
        var bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("INSERT INTO staff_calendar_token (user_id, token_hash, created_at) VALUES (?, ?, ?) ON CONFLICT (user_id) "
                        + "DO UPDATE SET token_hash = excluded.token_hash, created_at = excluded.created_at", user.userId(),
                PortalSigner.sha256(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII)), Timestamp.from(clock.instant()));
        return token;
    }

    @Transactional
    public void revokeCalendar() {
        jdbc.update("DELETE FROM staff_calendar_token WHERE user_id = ?", CurrentUser.get().userId());
    }

    public boolean hasCalendar() {
        return jdbc.queryForObject("SELECT count(*) FROM staff_calendar_token WHERE user_id = ?", Long.class,
                CurrentUser.get().userId()) > 0;
    }

    /** Horaires prévus de la personne, d'une semaine en arrière à huit semaines en avant, au format iCalendar. */
    @Transactional(readOnly = true)
    public String calendar(String token) {
        var userIds = jdbc.queryForList("SELECT user_id FROM staff_calendar_token WHERE token_hash = ?", UUID.class,
                PortalSigner.sha256(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        if (userIds.isEmpty()) {
            throw ApiException.notFound("Agenda introuvable.");
        }
        var user = users.findById(userIds.getFirst()).filter(u -> u.isActive()).orElseThrow(() -> ApiException.notFound("Agenda introuvable."));
        var today = LocalDate.now(clock.withZone(NOUMEA));
        var shifts = planning.shifts(user.getStructureId(), List.of(user), today.minusDays(7), today.plusWeeks(8))
                .getOrDefault(user.getId(), List.of());
        var utc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
        var stamp = utc.format(clock.instant().atOffset(ZoneOffset.UTC));
        var ics = new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Ti'Cagou//Planning//FR\r\nCALSCALE:GREGORIAN\r\n"
                + "X-WR-CALNAME:Mon planning Ti'Cagou\r\n");
        for (var s : shifts) {
            if (!s.planned() || s.absence() != null) {
                continue;
            }
            var start = s.day().atTime(s.startsAt()).atZone(NOUMEA).withZoneSameInstant(ZoneOffset.UTC);
            var end = s.day().atTime(s.endsAt()).atZone(NOUMEA).withZoneSameInstant(ZoneOffset.UTC);
            ics.append("BEGIN:VEVENT\r\nUID:").append(user.getId()).append('-').append(s.day()).append("@ticagou\r\n")
                    .append("DTSTAMP:").append(stamp).append("\r\nDTSTART:").append(utc.format(start))
                    .append("\r\nDTEND:").append(utc.format(end)).append("\r\nSUMMARY:Crèche\r\nEND:VEVENT\r\n");
        }
        return ics.append("END:VCALENDAR\r\n").toString();
    }

    // --- Outils -------------------------------------------------------------------------------

    private static final String SELECT = "SELECT r.id, r.user_id, u.first_name || ' ' || u.last_name AS user_name, r.kind, r.from_day, "
            + "r.to_day, r.minutes, r.note, r.status, r.answer, r.requested_at, r.handled_by FROM staff_request r "
            + "JOIN app_user u ON u.id = r.user_id ";

    private StaffRequestDto find(UUID id) {
        return jdbc.query(SELECT + "WHERE r.id = ? AND r.structure_id = ?", (rs, i) -> dto(rs), id, CurrentUser.get().structureId())
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Demande introuvable."));
    }

    private StaffRequestDto pendingOne(UUID id) {
        var request = find(id);
        if (!"DEMANDEE".equals(request.status())) {
            throw ApiException.rule("request_handled", "Cette demande a déjà reçu une réponse.");
        }
        return request;
    }

    private static StaffRequestDto dto(java.sql.ResultSet rs) throws java.sql.SQLException {
        var minutes = rs.getObject("minutes", Integer.class);
        return new StaffRequestDto(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("user_name"),
                RequestKind.valueOf(rs.getString("kind")), rs.getDate("from_day").toLocalDate(), rs.getDate("to_day").toLocalDate(),
                minutes, rs.getString("note"), rs.getString("status"), rs.getString("answer"),
                rs.getTimestamp("requested_at").toInstant(), rs.getString("handled_by"));
    }

    private static String clip(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var v = value.strip();
        return v.length() > max ? v.substring(0, max) : v;
    }
}
