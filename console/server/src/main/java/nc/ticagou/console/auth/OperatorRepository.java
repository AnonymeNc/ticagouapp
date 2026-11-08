package nc.ticagou.console.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class OperatorRepository {

    private final JdbcTemplate jdbc;

    OperatorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long count() {
        return jdbc.queryForObject("SELECT count(*) FROM operator", Long.class);
    }

    /** Création du premier compte : deux demandes simultanées ne créent pas deux comptes. */
    void lockTable() {
        jdbc.execute("LOCK TABLE operator IN EXCLUSIVE MODE");
    }

    Optional<Operator> findByLogin(String login) {
        return jdbc.query("SELECT * FROM operator WHERE lower(login) = lower(?)", OperatorRepository::map, login).stream().findFirst();
    }

    Optional<Operator> findById(UUID id) {
        return jdbc.query("SELECT * FROM operator WHERE id = ?", OperatorRepository::map, id).stream().findFirst();
    }

    void insert(UUID id, String login, String displayName, String passwordHash, Instant now) {
        jdbc.update("INSERT INTO operator (id, login, display_name, password_hash, created_at) VALUES (?, ?, ?, ?, ?)",
                id, login, displayName, passwordHash, Timestamp.from(now));
    }

    /** Échec : au {@code maxAttempts}-ième, le compte est bloqué {@code lock} et le compteur repart de zéro. */
    Instant fail(UUID id, Instant now, int maxAttempts, java.time.Duration lock) {
        var attempts = jdbc.queryForObject("UPDATE operator SET failed_attempts = failed_attempts + 1 WHERE id = ? RETURNING failed_attempts",
                Integer.class, id);
        if (attempts != null && attempts >= maxAttempts) {
            var until = now.plus(lock);
            jdbc.update("UPDATE operator SET failed_attempts = 0, locked_until = ? WHERE id = ?", Timestamp.from(until), id);
            return until;
        }
        return null;
    }

    void enableTotp(UUID id, String sealedSecret, long step, Instant now) {
        jdbc.update("UPDATE operator SET totp_secret = ?, totp_enabled_at = ?, totp_last_step = ? WHERE id = ?",
                sealedSecret, Timestamp.from(now), step, id);
    }

    void useStep(UUID id, long step) {
        jdbc.update("UPDATE operator SET totp_last_step = ? WHERE id = ?", step, id);
    }

    void loggedIn(UUID id, Instant now) {
        jdbc.update("UPDATE operator SET failed_attempts = 0, locked_until = NULL, last_login_at = ? WHERE id = ?", Timestamp.from(now), id);
    }

    private static Operator map(ResultSet rs, int row) throws SQLException {
        var step = rs.getLong("totp_last_step");
        var lastStep = rs.wasNull() ? null : step;
        return new Operator(rs.getObject("id", UUID.class), rs.getString("login"), rs.getString("display_name"),
                rs.getString("password_hash"), rs.getString("totp_secret"), instant(rs, "totp_enabled_at"), lastStep,
                instant(rs, "locked_until"), instant(rs, "disabled_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
