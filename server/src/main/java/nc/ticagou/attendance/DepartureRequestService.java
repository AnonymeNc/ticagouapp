package nc.ticagou.attendance;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.attendance.AttendanceDtos.OverrideInput;
import nc.ticagou.attendance.AttendanceService.Author;
import nc.ticagou.attendance.AttendanceService.Validation;
import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.audit.AuditService.Actor;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.auth.Role;
import nc.ticagou.common.ApiException;
import nc.ticagou.device.DeviceService;
import nc.ticagou.notification.NativePush.Message;
import nc.ticagou.notification.NotificationService;
import nc.ticagou.notification.NotificationService.Category;

/**
 * Départ bloqué validé à distance (maquette B5) : la tablette demande, la direction est prévenue sur son téléphone,
 * puis autorise ou refuse avec un motif. Autoriser enregistre le départ exactement comme une validation de la
 * direction au bureau (règle : départ avec une personne non autorisée = blocage + validation de la direction tracée).
 */
@Service
public class DepartureRequestService {

    public enum Status { PENDING, APPROVED, REFUSED, CANCELLED }

    /**
     * Demande vue par la direction : l'enfant, ses allergies et son PAI (santé visible partout où il apparaît), la
     * personne présente, qui a demandé.
     */
    public record RequestDto(UUID id, UUID childId, String childName, String sectionName, List<String> allergies,
                             boolean pai, String diet, String personName, String relationship, boolean idChecked,
                             String requestedBy, String deviceName, Instant requestedAt, Status status, String decidedBy,
                             Instant decidedAt, String reason) {
    }

    /** Ce que la tablette voit de sa demande. */
    public record RequestStatus(UUID id, Status status, String decidedBy, String reason) {
    }

    private final JdbcTemplate jdbc;
    private final AttendanceService attendance;
    private final DeviceService devices;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Clock clock;

    public DepartureRequestService(JdbcTemplate jdbc, AttendanceService attendance, DeviceService devices,
                                   NotificationService notifications, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.attendance = attendance;
        this.devices = devices;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
    }

    /** Tablette : la personne présente n'est pas sur la liste, aucune directrice n'est sur place. */
    @Transactional
    public RequestStatus request(UUID attendanceId, String personName, String relationship, boolean idChecked, String requestedBy) {
        var device = devices.requireCurrent();
        var structureId = device.getStructureId();
        var record = attendance.loadAttendance(structureId, attendanceId);
        if (!record.isPresent()) {
            throw ApiException.rule("not_present", "Le départ est déjà enregistré.");
        }
        // Une seule demande ouverte par passage : une nouvelle remplace l'ancienne.
        jdbc.update("UPDATE departure_request SET status = 'CANCELLED' WHERE attendance_id = ? AND status = 'PENDING'", attendanceId);
        var id = UUID.randomUUID();
        var now = clock.instant();
        jdbc.update("""
                INSERT INTO departure_request (id, structure_id, attendance_id, child_id, device_id, person_name, relationship,
                                               id_checked, requested_by, requested_at, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')""",
                id, structureId, attendanceId, record.getChildId(), device.getId(), personName.strip(), relationship.strip(),
                idChecked, requestedBy.strip(), Timestamp.from(now));
        var child = jdbc.queryForMap("SELECT first_name, last_name FROM child WHERE id = ?", record.getChildId());
        var shortName = child.get("first_name") + " " + String.valueOf(child.get("last_name")).charAt(0) + ".";
        audit.record(new Actor(structureId, null, requestedBy.strip()), AuditAction.DEPARTURE_REQUESTED, "child_attendance",
                attendanceId, Map.of("person", personName.strip(), "relationship", relationship.strip(), "device", device.getName()));
        // Prénom et initiale seulement ; ni le nom de la personne venue, ni la santé (ADR 0026).
        notifications.notifyDirection(structureId, Category.DEPART, new Message("Départ à valider : " + shortName,
                "Ouvrez Ti’Cagou pour autoriser ou refuser.", "/presences?depart=" + id, Category.DEPART.name(),
                NotificationService.ALERT_TTL, "depart-" + id));
        return new RequestStatus(id, Status.PENDING, null, null);
    }

    /** Tablette : où en est la demande ? */
    @Transactional(readOnly = true)
    public RequestStatus status(UUID id) {
        var device = devices.requireCurrent();
        return jdbc.query("SELECT id, status, decided_by, reason FROM departure_request WHERE id = ? AND structure_id = ?",
                (rs, i) -> new RequestStatus(rs.getObject("id", UUID.class), Status.valueOf(rs.getString("status")),
                        rs.getString("decided_by"), rs.getString("reason")), id, device.getStructureId())
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Demande introuvable."));
    }

    /** Tablette : la directrice est arrivée, ou l'enfant n'est finalement pas parti. */
    @Transactional
    public void cancel(UUID id) {
        var device = devices.requireCurrent();
        jdbc.update("UPDATE departure_request SET status = 'CANCELLED' WHERE id = ? AND structure_id = ? AND status = 'PENDING'",
                id, device.getStructureId());
    }

    /** Direction : demandes en attente (bandeau du tableau de bord, des présences et de l'application). */
    @Transactional(readOnly = true)
    public List<RequestDto> pending() {
        return list(CurrentUser.get().structureId(), "r.status = 'PENDING'");
    }

    private List<RequestDto> list(UUID structureId, String condition) {
        return jdbc.query("""
                SELECT r.*, c.first_name, c.last_name, s.name AS section_name, d.name AS device_name, h.pai_active, h.diet,
                       (SELECT string_agg(a.allergen, '|' ORDER BY a.display_order) FROM allergy a WHERE a.child_id = c.id) AS allergies
                FROM departure_request r
                JOIN child c ON c.id = r.child_id
                LEFT JOIN section s ON s.id = c.section_id
                LEFT JOIN device d ON d.id = r.device_id
                LEFT JOIN health_info h ON h.child_id = c.id
                WHERE r.structure_id = ?""" + " AND " + condition + " ORDER BY r.requested_at",
                (rs, i) -> new RequestDto(rs.getObject("id", UUID.class), rs.getObject("child_id", UUID.class),
                        rs.getString("first_name") + " " + rs.getString("last_name"), rs.getString("section_name"),
                        rs.getString("allergies") == null ? List.of() : List.of(rs.getString("allergies").split("\\|")),
                        rs.getBoolean("pai_active"), rs.getString("diet"), rs.getString("person_name"),
                        rs.getString("relationship"), rs.getBoolean("id_checked"), rs.getString("requested_by"),
                        rs.getString("device_name"), rs.getTimestamp("requested_at").toInstant(),
                        Status.valueOf(rs.getString("status")), rs.getString("decided_by"),
                        rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant(),
                        rs.getString("reason")),
                structureId);
    }

    /** Direction : autoriser (le départ est enregistré à cet instant) ou refuser, toujours avec un motif. */
    @Transactional
    public RequestDto decide(UUID id, boolean approve, String reason) {
        var user = CurrentUser.get();
        if (user.role() != Role.DIRECTION) {
            throw ApiException.forbidden("direction_only", "Seule la direction valide un départ.");
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.rule("reason_required", "Indiquez le motif de votre décision.");
        }
        var rows = jdbc.queryForList("SELECT * FROM departure_request WHERE id = ? AND structure_id = ? FOR UPDATE",
                id, user.structureId());
        if (rows.isEmpty()) {
            throw ApiException.notFound("Demande introuvable.");
        }
        var row = rows.getFirst();
        if (!"PENDING".equals(row.get("status"))) {
            throw ApiException.rule("already_decided", "Cette demande a déjà été traitée (ou annulée par la tablette).");
        }
        var now = clock.instant();
        if (approve) {
            var record = attendance.loadAttendance(user.structureId(), (UUID) row.get("attendance_id"));
            var other = new OverrideInput((String) row.get("person_name"), (String) row.get("relationship"),
                    (Boolean) row.get("id_checked"), null, null, "Validé à distance : " + reason.strip());
            attendance.depart(user.structureId(), record, now, null, other, new Validation(user.userId(), user.displayName(), true),
                    new Author(user.userId(), user.displayName(), user.role()));
        }
        jdbc.update("""
                UPDATE departure_request SET status = ?, decided_by_id = ?, decided_by = ?, decided_at = ?, reason = ? WHERE id = ?""",
                approve ? "APPROVED" : "REFUSED", user.userId(), user.displayName(), Timestamp.from(now), reason.strip(), id);
        audit.record(AuditAction.DEPARTURE_REQUEST_DECIDED, "child_attendance", (UUID) row.get("attendance_id"),
                Map.of("decision", approve ? "AUTORISÉ" : "REFUSÉ", "person", row.get("person_name"), "reason", reason.strip()));
        return list(user.structureId(), "r.id = '" + id + "'").getFirst();
    }
}
