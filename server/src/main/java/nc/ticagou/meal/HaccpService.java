package nc.ticagou.meal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.common.ApiException;

/**
 * Relevés de températures pour le plan de maîtrise sanitaire (lot 4) : points de contrôle avec seuils, relevés au
 * dixième de degré, mesure corrective obligatoire hors seuil, export mensuel pour les contrôles.
 */
@Service
public class HaccpService {

    public enum Kind { FRIGO, CONGELATEUR, PLAT_TEMOIN, LIVRAISON, AUTRE }

    /** Seuils en dixièmes de degré : -180 = -18,0 °C. */
    public record Point(UUID id, String label, Kind kind, int minTenths, int maxTenths, boolean active) {
    }

    public record Reading(UUID id, UUID pointId, String pointLabel, Instant takenAt, int tenths, boolean outOfRange,
                          String action, String authorName) {
    }

    private static final ZoneId NOUMEA = ZoneId.of("Pacific/Noumea");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public HaccpService(JdbcTemplate jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    public List<Point> points() {
        return jdbc.query("SELECT id, label, kind, min_tenths, max_tenths, active FROM haccp_point WHERE structure_id = ? "
                        + "ORDER BY active DESC, label", (rs, i) -> new Point(rs.getObject(1, UUID.class), rs.getString(2),
                        Kind.valueOf(rs.getString(3)), rs.getInt(4), rs.getInt(5), rs.getBoolean(6)),
                CurrentUser.get().structureId());
    }

    @Transactional
    public Point addPoint(String label, Kind kind, int minTenths, int maxTenths) {
        var user = CurrentUser.get();
        var clean = label == null ? "" : label.strip();
        if (clean.isEmpty() || clean.length() > 80) {
            throw ApiException.rule("point_label", "Nommez le point de contrôle (80 caractères au plus).");
        }
        if (maxTenths < minTenths) {
            throw ApiException.rule("point_range", "Le maximum doit être supérieur ou égal au minimum.");
        }
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO haccp_point (id, structure_id, label, kind, min_tenths, max_tenths, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, user.structureId(), clean, kind.name(), minTenths, maxTenths, Timestamp.from(clock.instant()));
        audit.record(AuditAction.HACCP_POINT_CHANGED, "haccp_point", id, Map.of("action", "added"));
        return points().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void deactivate(UUID id) {
        if (jdbc.update("UPDATE haccp_point SET active = FALSE WHERE id = ? AND structure_id = ?", id, CurrentUser.get().structureId()) == 0) {
            throw ApiException.notFound("Point de contrôle introuvable.");
        }
        audit.record(AuditAction.HACCP_POINT_CHANGED, "haccp_point", id, Map.of("action", "deactivated"));
    }

    /** Relevé ; hors seuil, la mesure corrective est obligatoire et l'écart est tracé. */
    @Transactional
    public Reading record(UUID pointId, int tenths, String action) {
        var user = CurrentUser.get();
        var point = points().stream().filter(p -> p.id().equals(pointId) && p.active()).findFirst()
                .orElseThrow(() -> ApiException.notFound("Point de contrôle introuvable."));
        if (tenths < -500 || tenths > 1500) {
            throw ApiException.rule("reading_value", "Température invalide.");
        }
        boolean out = tenths < point.minTenths() || tenths > point.maxTenths();
        var clean = action == null || action.isBlank() ? null : action.strip();
        if (out && clean == null) {
            throw ApiException.rule("corrective_action", "Hors seuil : indiquez la mesure corrective prise.");
        }
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO haccp_reading (id, structure_id, point_id, taken_at, tenths, out_of_range, action, author_name) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", id, user.structureId(), pointId, Timestamp.from(clock.instant()), tenths, out,
                clean == null ? null : clean.length() > 300 ? clean.substring(0, 300) : clean, user.displayName());
        if (out) {
            audit.record(AuditAction.HACCP_OUT_OF_RANGE, "haccp_reading", id, Map.of("point", point.label(), "tenths", tenths));
        }
        return month(YearMonth.now(clock.withZone(NOUMEA))).stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    public List<Reading> month(YearMonth month) {
        var from = month.atDay(1).atStartOfDay(NOUMEA).toInstant();
        var to = month.plusMonths(1).atDay(1).atStartOfDay(NOUMEA).toInstant();
        return jdbc.query("SELECT r.id, r.point_id, p.label, r.taken_at, r.tenths, r.out_of_range, r.action, r.author_name "
                        + "FROM haccp_reading r JOIN haccp_point p ON p.id = r.point_id WHERE r.structure_id = ? AND r.taken_at >= ? "
                        + "AND r.taken_at < ? ORDER BY r.taken_at", (rs, i) -> new Reading(rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class), rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getInt(5), rs.getBoolean(6),
                        rs.getString(7), rs.getString(8)),
                CurrentUser.get().structureId(), Timestamp.from(from), Timestamp.from(to));
    }

    /** Export du mois pour les contrôles : CSV (point-virgule, UTF-8 avec BOM), dates JJ/MM/AAAA, degrés à virgule. */
    public byte[] csv(YearMonth month) {
        var date = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(NOUMEA);
        var time = DateTimeFormatter.ofPattern("HH:mm").withZone(NOUMEA);
        var out = new StringBuilder("﻿Date;Heure;Point de contrôle;Température (°C);Hors seuil;Mesure corrective;Relevé par\r\n");
        for (var r : month(month)) {
            out.append(date.format(r.takenAt())).append(';').append(time.format(r.takenAt())).append(';').append(cell(r.pointLabel()))
                    .append(';').append(degrees(r.tenths())).append(';').append(r.outOfRange() ? "Oui" : "Non").append(';')
                    .append(cell(r.action())).append(';').append(cell(r.authorName())).append("\r\n");
        }
        return out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    static String degrees(int tenths) {
        var sign = tenths < 0 ? "-" : "";
        var abs = Math.abs(tenths);
        return sign + (abs / 10) + "," + (abs % 10);
    }

    private static String cell(String value) {
        if (value == null) {
            return "";
        }
        // Pas de formule interprétée par le tableur ; guillemets doublés.
        var safe = value.matches("^[=+\\-@].*") ? "'" + value : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
