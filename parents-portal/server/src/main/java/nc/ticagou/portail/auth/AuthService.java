package nc.ticagou.portail.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.portail.TimeConfig;
import nc.ticagou.portail.common.ApiException;
import nc.ticagou.portail.common.Hashes;

/**
 * Connexion des parents par lien magique (jeton de 32 octets) ou code à 6 chiffres reçu dans le même e-mail :
 * 15 minutes, usage unique, 5 essais de code au plus. Seules les empreintes des jetons et codes sont gardées.
 * <p>
 * La session suit l'abonnement des crèches du parent : elle court jusqu'au dernier jour d'accès le plus lointain
 * ({@code portal_structure.access_until} : fin d'abonnement plus la grâce, ou fin d'évaluation), relu à chaque
 * requête. Un renouvellement l'allonge sans nouvel e-mail ; une crèche échue est traitée comme suspendue. Une crèche
 * dont le serveur n'envoie pas encore cette date donne 30 jours glissants. La session peut être révoquée.
 */
@Service
public class AuthService {

    static final Duration LOGIN_VALIDITY = Duration.ofMinutes(15);
    /** Durée glissante pour une crèche sans date de fin d'accès (serveur de crèche plus ancien). */
    static final Duration SESSION_VALIDITY = Duration.ofDays(30);
    static final int MAX_CODE_ATTEMPTS = 5;

    /** Structure ouverte aux parents (alias {@code s}) : ni suspendue, ni échue ; paramètre : le jour à Nouméa. */
    public static final String OPEN_STRUCTURE = "s.suspended_at IS NULL AND (s.access_until IS NULL OR s.access_until >= ?)";

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
                        + "WHERE lower(g.email) = ? AND " + OPEN_STRUCTURE + ")", Boolean.class, email, today()));
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

    /** {@code expiresAt} : fin de la session, à reporter sur le cookie. */
    public record Session(String token, String email, Instant expiresAt) {
    }

    /** Session valide : adresse du parent et fin de la session à ce jour. */
    public record Active(String email, Instant expiresAt) {
    }

    private Session open(String email) {
        var token = Hashes.token(32);
        var now = clock.instant();
        var end = sessionEnd(email, now).orElseThrow(
                () -> ApiException.unauthorized("access_closed", "L’accès au portail de votre crèche est fermé."));
        jdbc.update("INSERT INTO parent_session (token_hash, email, created_at, expires_at, last_seen_at) VALUES (?, ?, ?, ?, ?)",
                Hashes.sha256(token), email, Timestamp.from(now), Timestamp.from(end), Timestamp.from(now));
        return new Session(token, email, end);
    }

    /**
     * Fin de session d'une adresse : lendemain, à minuit à Nouméa, du dernier jour d'accès le plus lointain de ses
     * crèches ouvertes ; 30 jours glissants si l'une n'a pas de date ; vide si aucune n'est ouverte.
     */
    Optional<Instant> sessionEnd(String email, Instant now) {
        var today = LocalDate.ofInstant(now, TimeConfig.NOUMEA);
        return jdbc.query("SELECT bool_or(s.access_until IS NULL) AS unlimited, max(s.access_until) AS until "
                        + "FROM guardian g JOIN portal_structure s ON s.id = g.structure_id "
                        + "WHERE lower(g.email) = lower(?) AND " + OPEN_STRUCTURE, rs -> {
                    rs.next();
                    var unlimited = rs.getObject("unlimited", Boolean.class);
                    if (unlimited == null) {
                        return Optional.<Instant>empty();
                    }
                    if (unlimited) {
                        return Optional.of(now.plus(SESSION_VALIDITY));
                    }
                    var until = rs.getObject("until", LocalDate.class);
                    return Optional.of(until.plusDays(1).atStartOfDay(TimeConfig.NOUMEA).toInstant());
                }, email, java.sql.Date.valueOf(today));
    }

    /** Session encore valide ; sa fin suit l'abonnement des crèches (renouvellement ou échéance). */
    public Optional<Active> active(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        var now = clock.instant();
        var hash = Hashes.sha256(token);
        var emails = jdbc.queryForList("SELECT email FROM parent_session WHERE token_hash = ? AND revoked_at IS NULL "
                + "AND expires_at > ?", String.class, hash, Timestamp.from(now));
        if (emails.isEmpty()) {
            return Optional.empty();
        }
        var email = emails.getFirst();
        var end = sessionEnd(email, now).filter(e -> e.isAfter(now));
        if (end.isEmpty()) {
            return Optional.empty();
        }
        jdbc.update("UPDATE parent_session SET last_seen_at = ?, expires_at = ? WHERE token_hash = ?", Timestamp.from(now),
                Timestamp.from(end.get()), hash);
        return Optional.of(new Active(email, end.get()));
    }

    private java.sql.Date today() {
        return java.sql.Date.valueOf(LocalDate.now(clock.withZone(TimeConfig.NOUMEA)));
    }

    @Transactional
    public void logout(String token) {
        if (token != null) {
            jdbc.update("UPDATE parent_session SET revoked_at = ? WHERE token_hash = ?", Timestamp.from(clock.instant()),
                    Hashes.sha256(token));
        }
    }
}
