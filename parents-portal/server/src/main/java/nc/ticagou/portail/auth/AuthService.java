package nc.ticagou.portail.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.portail.common.ApiException;
import nc.ticagou.portail.common.Hashes;

/**
 * Connexion des parents par lien magique (jeton de 32 octets) ou code à 6 chiffres reçu dans le même e-mail :
 * 15 minutes, usage unique, 5 essais de code au plus. La session ouverte dure 30 jours et peut être révoquée.
 * Seules les empreintes des jetons et codes sont gardées.
 */
@Service
public class AuthService {

    static final Duration LOGIN_VALIDITY = Duration.ofMinutes(15);
    static final Duration SESSION_VALIDITY = Duration.ofDays(30);
    static final int MAX_CODE_ATTEMPTS = 5;

    private final JdbcTemplate jdbc;
    private final Mailer mailer;
    private final RateLimiter limiter;
    private final Clock clock;

    public AuthService(JdbcTemplate jdbc, Mailer mailer, RateLimiter limiter, Clock clock) {
        this.jdbc = jdbc;
        this.mailer = mailer;
        this.limiter = limiter;
        this.clock = clock;
    }

    /** Toujours la même réponse : on ne révèle pas si l'adresse est connue. */
    @Transactional
    public void request(String rawEmail, String ip) {
        var email = rawEmail.strip().toLowerCase();
        if (!limiter.allow("ip:" + ip, 20, Duration.ofMinutes(15)) || !limiter.allow("mail:" + email, 5, Duration.ofMinutes(15))) {
            throw ApiException.tooMany("Trop de demandes : réessayez dans quelques minutes.");
        }
        boolean known = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM guardian g JOIN portal_structure s ON s.id = g.structure_id "
                        + "WHERE lower(g.email) = ? AND s.suspended_at IS NULL)", Boolean.class, email));
        if (!known) {
            return;
        }
        var token = Hashes.token(32);
        var code = Hashes.digits(6);
        var now = clock.instant();
        jdbc.update("INSERT INTO login_request (id, email, token_hash, code_hash, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), email, Hashes.sha256(token), Hashes.sha256(email + ":" + code), Timestamp.from(now),
                Timestamp.from(now.plus(LOGIN_VALIDITY)));
        mailer.sendLogin(email, token, code);
    }

    /** Lien magique : renvoie le jeton de session. */
    @Transactional
    public Session verifyToken(String token, String ip) {
        if (!limiter.allow("verify:" + ip, 30, Duration.ofMinutes(15))) {
            throw ApiException.tooMany("Trop d’essais : réessayez dans quelques minutes.");
        }
        var now = Timestamp.from(clock.instant());
        var emails = jdbc.queryForList("UPDATE login_request SET used_at = ? WHERE token_hash = ? AND used_at IS NULL "
                + "AND expires_at > ? RETURNING email", String.class, now, Hashes.sha256(token == null ? "" : token), now);
        if (emails.isEmpty()) {
            throw ApiException.unauthorized("link_invalid", "Ce lien a expiré ou a déjà servi : demandez-en un nouveau.");
        }
        return open(emails.getFirst());
    }

    /** Code reçu par e-mail, pour l'adresse saisie. */
    @Transactional(noRollbackFor = ApiException.class)
    public Session verifyCode(String rawEmail, String code, String ip) {
        if (!limiter.allow("verify:" + ip, 30, Duration.ofMinutes(15))) {
            throw ApiException.tooMany("Trop d’essais : réessayez dans quelques minutes.");
        }
        var email = rawEmail.strip().toLowerCase();
        var now = Timestamp.from(clock.instant());
        var pending = jdbc.query("SELECT id, code_hash, attempts FROM login_request WHERE email = ? AND used_at IS NULL "
                        + "AND expires_at > ? ORDER BY created_at DESC LIMIT 1 FOR UPDATE",
                (rs, i) -> new Pending(rs.getObject("id", UUID.class), rs.getString("code_hash"), rs.getInt("attempts")),
                email, now);
        if (pending.isEmpty()) {
            throw ApiException.unauthorized("code_invalid", "Code expiré : demandez un nouvel e-mail.");
        }
        var request = pending.getFirst();
        var normalized = code == null ? "" : code.replaceAll("\\s", "");
        if (!Hashes.equal(request.codeHash(), Hashes.sha256(email + ":" + normalized))) {
            int attempts = request.attempts() + 1;
            jdbc.update("UPDATE login_request SET attempts = ?, used_at = CASE WHEN ? >= ? THEN ? ELSE used_at END WHERE id = ?",
                    attempts, attempts, MAX_CODE_ATTEMPTS, now, request.id());
            throw ApiException.unauthorized("code_invalid", attempts >= MAX_CODE_ATTEMPTS
                    ? "Trop d’essais : demandez un nouvel e-mail." : "Code incorrect.");
        }
        jdbc.update("UPDATE login_request SET used_at = ? WHERE id = ?", now, request.id());
        return open(email);
    }

    private record Pending(UUID id, String codeHash, int attempts) {
    }

    public record Session(String token, String email) {
    }

    private Session open(String email) {
        var token = Hashes.token(32);
        var now = clock.instant();
        jdbc.update("INSERT INTO parent_session (token_hash, email, created_at, expires_at, last_seen_at) VALUES (?, ?, ?, ?, ?)",
                Hashes.sha256(token), email, Timestamp.from(now), Timestamp.from(now.plus(SESSION_VALIDITY)), Timestamp.from(now));
        return new Session(token, email);
    }

    /** Adresse de la session encore valide. */
    public Optional<String> email(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        var now = Timestamp.from(clock.instant());
        var found = jdbc.queryForList("UPDATE parent_session SET last_seen_at = ? WHERE token_hash = ? AND revoked_at IS NULL "
                + "AND expires_at > ? RETURNING email", String.class, now, Hashes.sha256(token), now);
        return found.stream().findFirst();
    }

    @Transactional
    public void logout(String token) {
        if (token != null) {
            jdbc.update("UPDATE parent_session SET revoked_at = ? WHERE token_hash = ?", Timestamp.from(clock.instant()),
                    Hashes.sha256(token));
        }
    }
}
