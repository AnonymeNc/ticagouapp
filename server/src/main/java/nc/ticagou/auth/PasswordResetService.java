package nc.ticagou.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.audit.AuditService.Actor;
import nc.ticagou.common.ApiException;

/**
 * Mot de passe oublié par la direction : l'éditeur crée un lien à usage unique, valable 24 heures, depuis la console
 * (ordre MOT_DE_PASSE, {@code creche.sh mot-de-passe}). La personne choisit elle-même son nouveau mot de passe ; ni
 * l'éditeur ni la console ne le connaissent. La double authentification reste demandée à la connexion.
 */
@Service
public class PasswordResetService {

    public record Account(String login, String name) {
    }

    private record Pending(UUID userId) {
    }

    private static final String EXPIRED = "Ce lien a expiré ou a déjà servi. Demandez-en un nouveau à l’éditeur de Ti’Cagou.";

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final TokenService tokens;
    private final AuditService audit;
    private final Clock clock;

    public PasswordResetService(JdbcTemplate jdbc, AppUserRepository users, PasswordEncoder encoder, TokenService tokens, AuditService audit,
                                Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.encoder = encoder;
        this.tokens = tokens;
        this.audit = audit;
        this.clock = clock;
    }

    /** Compte visé par le lien, pour que la personne vérifie que c'est bien le sien. */
    @Transactional(readOnly = true)
    public Account check(String token) {
        var user = users.findById(pending(token, false).userId()).filter(AppUser::isActive)
                .orElseThrow(() -> ApiException.rule("reset_expired", EXPIRED));
        return new Account(user.getLogin(), user.displayName());
    }

    @Transactional
    public void reset(String token, String password) {
        // Le mot de passe est contrôlé avant de consommer le lien : un mot de passe trop simple ne le gâche pas.
        PasswordRules.checkPassword(password);
        var user = users.findById(pending(token, true).userId()).filter(AppUser::isActive)
                .orElseThrow(() -> ApiException.rule("reset_expired", EXPIRED));
        user.changePassword(user.getLogin(), encoder.encode(password));
        users.saveAndFlush(user);
        // Les autres liens de ce compte ne servent plus ; les sessions ouvertes sont fermées.
        jdbc.update("UPDATE password_reset SET used_at = ? WHERE user_id = ? AND used_at IS NULL", Timestamp.from(clock.instant()), user.getId());
        tokens.revokeAll(user.getId());
        audit.record(new Actor(user.getStructureId(), user.getId(), user.displayName()), AuditAction.USER_PASSWORD_RESET_BY_LINK, "user",
                user.getId(), Map.of());
    }

    private Pending pending(String token, boolean consume) {
        if (token == null || !token.matches("[0-9a-f]{32,128}")) {
            throw ApiException.rule("reset_expired", EXPIRED);
        }
        var now = Timestamp.from(clock.instant());
        var hash = TokenService.hash(token);
        var found = consume
                ? jdbc.query("UPDATE password_reset SET used_at = ? WHERE token_hash = ? AND used_at IS NULL AND expires_at > ? RETURNING user_id",
                        (rs, i) -> new Pending(rs.getObject(1, UUID.class)), now, hash, now)
                : jdbc.query("SELECT user_id FROM password_reset WHERE token_hash = ? AND used_at IS NULL AND expires_at > ?",
                        (rs, i) -> new Pending(rs.getObject(1, UUID.class)), hash, now);
        return found.stream().findFirst().orElseThrow(() -> ApiException.rule("reset_expired", EXPIRED));
    }
}
