package nc.ticagou.console.audit;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import nc.ticagou.console.auth.CurrentOperator;
import nc.ticagou.console.common.ClientAddress;

/** Journal de la console (ADR 0017) : toute connexion et toute écriture, avec l'auteur et l'adresse. */
@Service
public class AuditService {

    public record Event(long id, Instant at, String operatorLogin, String action, String target, String detail, String ip) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public AuditService(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /** Action de l'opérateur connecté. */
    public void record(String action, String target, Map<String, ?> detail) {
        var operator = CurrentOperator.find();
        record(operator.map(CurrentOperator.Principal::id).orElse(null), operator.map(CurrentOperator.Principal::login).orElse(null),
                action, target, detail);
    }

    /** Action d'un opérateur désigné (connexion en cours, avant que la session ne soit ouverte). */
    public void record(UUID operatorId, String login, String action, String target, Map<String, ?> detail) {
        String text;
        try {
            text = detail == null || detail.isEmpty() ? null : json.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            text = null;
        }
        jdbc.update("INSERT INTO audit_event (at, operator_id, operator_login, action, target, detail, ip) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)",
                Timestamp.from(clock.instant()), operatorId, login, action, target, text, ClientAddress.current());
    }

    public List<Event> latest(int limit) {
        return jdbc.query("SELECT id, at, operator_login, action, target, detail::text AS detail, ip FROM audit_event ORDER BY at DESC, id DESC LIMIT ?",
                (rs, i) -> new Event(rs.getLong("id"), rs.getTimestamp("at").toInstant(), rs.getString("operator_login"),
                        rs.getString("action"), rs.getString("target"), rs.getString("detail"), rs.getString("ip")),
                Math.max(1, Math.min(limit, 500)));
    }
}
