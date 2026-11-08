package nc.ticagou.console.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import nc.ticagou.console.ConsoleProperties;
import nc.ticagou.console.audit.AuditService;
import nc.ticagou.console.common.ApiException;

/**
 * Connexion à la console (ADR 0017) : premier compte par jeton, puis mot de passe et code TOTP obligatoire. Le secret
 * TOTP est proposé à la première connexion et enregistré seulement quand un premier code est juste.
 */
@Service
public class AuthService {

    static final int MAX_ATTEMPTS = 5;
    static final Duration LOCK = Duration.ofMinutes(15);
    static final int MIN_PASSWORD = 12;
    private static final Pattern LOGIN = Pattern.compile("[a-z0-9._-]{2,60}");

    public record OperatorDto(UUID id, String login, String displayName) {
    }

    /** Étape de connexion : {@code MOT_DE_PASSE}, {@code TOTP}, {@code TOTP_ENROLL} (secret et adresse fournis) ou {@code CONNECTE}. */
    public record LoginStep(String step, String secret, String uri) {
    }

    public record State(boolean setupRequired, LoginStep login, OperatorDto operator) {
    }

    private final OperatorRepository operators;
    private final PasswordEncoder encoder;
    private final SecretBox box;
    private final LoginThrottle throttle;
    private final AuditService audit;
    private final ConsoleProperties properties;
    private final SecurityContextRepository contexts;
    private final Clock clock;
    private final String dummyHash;

    public AuthService(OperatorRepository operators, PasswordEncoder encoder, SecretBox box, LoginThrottle throttle, AuditService audit,
                       ConsoleProperties properties, SecurityContextRepository contexts, Clock clock) {
        this.operators = operators;
        this.encoder = encoder;
        this.box = box;
        this.throttle = throttle;
        this.audit = audit;
        this.properties = properties;
        this.contexts = contexts;
        this.clock = clock;
        // Un identifiant inconnu coûte le même temps qu'un mauvais mot de passe.
        this.dummyHash = encoder.encode("identifiant-inconnu");
    }

    public State state(HttpServletRequest request) {
        var operator = CurrentOperator.find().map(p -> new OperatorDto(p.id(), p.login(), p.displayName()));
        LoginStep step;
        if (operator.isPresent()) {
            step = new LoginStep("CONNECTE", null, null);
        } else {
            var pending = pending(request.getSession(false));
            step = pending.map(this::stepOf).orElse(new LoginStep("MOT_DE_PASSE", null, null));
        }
        return new State(operators.count() == 0, step, operator.orElse(null));
    }

    @Transactional
    public OperatorDto setup(String token, String login, String displayName, String password, HttpServletRequest request) {
        var address = request.getRemoteAddr();
        throttle.check(address);
        var expected = properties.setupToken();
        if (expected == null || expected.isBlank() || token == null
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
            throttle.fail(address);
            throw ApiException.forbidden("setup_token", "Lien de création invalide : utilisez celui de socle.env (CONSOLE_SETUP_TOKEN).");
        }
        operators.lockTable();
        if (operators.count() > 0) {
            throw ApiException.forbidden("setup_done", "Un compte existe déjà : connectez-vous.");
        }
        var cleanLogin = checkLogin(login);
        var name = displayName == null || displayName.isBlank() ? cleanLogin : displayName.strip();
        checkPassword(password, cleanLogin);
        var id = UUID.randomUUID();
        operators.insert(id, cleanLogin, name.length() > 120 ? name.substring(0, 120) : name, encoder.encode(password), clock.instant());
        audit.record(id, cleanLogin, "PREMIER_COMPTE", cleanLogin, null);
        return new OperatorDto(id, cleanLogin, name);
    }

    public LoginStep login(String login, String password, HttpServletRequest request) {
        var address = request.getRemoteAddr();
        throttle.check(address);
        var now = clock.instant();
        Optional<Operator> found = login == null || login.isBlank() ? Optional.empty() : operators.findByLogin(login.strip());
        if (found.isPresent() && found.get().lockedAt(now)) {
            throw ApiException.locked(found.get().lockedUntil());
        }
        var matches = encoder.matches(password == null ? "" : password, found.map(Operator::passwordHash).orElse(dummyHash));
        if (!matches || found.isEmpty() || found.get().disabledAt() != null) {
            throttle.fail(address);
            found.ifPresent(o -> operators.fail(o.id(), now, MAX_ATTEMPTS, LOCK));
            audit.record(found.map(Operator::id).orElse(null), truncate(login), "CONNEXION_ECHEC", null, Map.of("etape", "mot de passe"));
            throw ApiException.unauthorized("bad_credentials", "Identifiant ou mot de passe incorrect.");
        }
        var operator = found.get();
        var pending = new SessionKeys.Pending(operator.id(), now, operator.totpEnabled() ? null : Totp.newSecret());
        request.getSession(true).setAttribute(SessionKeys.PENDING, pending);
        return stepOf(pending);
    }

    public OperatorDto verify(String code, HttpServletRequest request, HttpServletResponse response) {
        var address = request.getRemoteAddr();
        throttle.check(address);
        var now = clock.instant();
        var session = request.getSession(false);
        var pending = pending(session).orElseThrow(() -> ApiException.unauthorized("login_expired", "Reprenez la connexion depuis le début."));
        var operator = operators.findById(pending.operatorId()).filter(o -> o.disabledAt() == null)
                .orElseThrow(() -> ApiException.unauthorized("login_expired", "Reprenez la connexion depuis le début."));
        if (operator.lockedAt(now)) {
            session.removeAttribute(SessionKeys.PENDING);
            throw ApiException.locked(operator.lockedUntil());
        }
        var enrolling = pending.enrollSecret() != null;
        var step = enrolling
                ? Totp.match(pending.enrollSecret(), code, now, null)
                : Totp.match(box.open(operator.totpSecret()), code, now, operator.totpLastStep());
        if (step.isEmpty()) {
            codeFailed(operator, address, now, session);
        }
        if (enrolling) {
            operators.enableTotp(operator.id(), box.seal(pending.enrollSecret()), step.getAsLong(), now);
            audit.record(operator.id(), operator.login(), "TOTP_ENREGISTRE", operator.login(), null);
        } else {
            operators.useStep(operator.id(), step.getAsLong());
        }
        operators.loggedIn(operator.id(), now);
        session.removeAttribute(SessionKeys.PENDING);
        request.changeSessionId();
        session.setAttribute(SessionKeys.LOGIN_AT, now);
        session.setAttribute(SessionKeys.CONFIRMED_AT, now);
        var principal = new CurrentOperator.Principal(operator.id(), operator.login(), operator.displayName());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        audit.record(operator.id(), operator.login(), "CONNEXION", null, null);
        return new OperatorDto(operator.id(), operator.login(), operator.displayName());
    }

    /** Code redemandé avant une action sensible ({@link SensitiveAction}). */
    public void confirm(String code, HttpServletRequest request) {
        var principal = CurrentOperator.get();
        var address = request.getRemoteAddr();
        throttle.check(address);
        var now = clock.instant();
        var operator = operators.findById(principal.id()).orElseThrow(() -> ApiException.unauthorized("unauthenticated", "Connectez-vous."));
        OptionalLong step = Totp.match(box.open(operator.totpSecret()), code, now, operator.totpLastStep());
        if (step.isEmpty()) {
            codeFailed(operator, address, now, null);
        }
        operators.useStep(operator.id(), step.getAsLong());
        request.getSession().setAttribute(SessionKeys.CONFIRMED_AT, now);
        audit.record("CONFIRMATION", null, null);
    }

    public void logout(HttpServletRequest request) {
        if (CurrentOperator.find().isPresent()) {
            audit.record("DECONNEXION", null, null);
        }
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    private void codeFailed(Operator operator, String address, Instant now, HttpSession session) {
        throttle.fail(address);
        var until = operators.fail(operator.id(), now, MAX_ATTEMPTS, LOCK);
        audit.record(operator.id(), operator.login(), "CONNEXION_ECHEC", null, Map.of("etape", "code"));
        if (until != null) {
            if (session != null) {
                session.removeAttribute(SessionKeys.PENDING);
            }
            throw ApiException.locked(until);
        }
        throw ApiException.rule("totp_invalid", "Code incorrect. Vérifiez l’heure du téléphone et réessayez.");
    }

    private Optional<SessionKeys.Pending> pending(HttpSession session) {
        if (session == null || !(session.getAttribute(SessionKeys.PENDING) instanceof SessionKeys.Pending pending)) {
            return Optional.empty();
        }
        if (pending.at().plus(SessionKeys.PENDING_TTL).isBefore(clock.instant())) {
            session.removeAttribute(SessionKeys.PENDING);
            return Optional.empty();
        }
        return Optional.of(pending);
    }

    private LoginStep stepOf(SessionKeys.Pending pending) {
        if (pending.enrollSecret() == null) {
            return new LoginStep("TOTP", null, null);
        }
        var login = operators.findById(pending.operatorId()).map(Operator::login).orElse("console");
        return new LoginStep("TOTP_ENROLL", pending.enrollSecret(), Totp.uri(login, pending.enrollSecret()));
    }

    static String checkLogin(String login) {
        var clean = login == null ? "" : login.strip().toLowerCase();
        if (!LOGIN.matcher(clean).matches()) {
            throw ApiException.rule("login_invalid", "Identifiant : 2 à 60 caractères, minuscules, chiffres, point, tiret.");
        }
        return clean;
    }

    static void checkPassword(String password, String login) {
        if (password == null || password.length() < MIN_PASSWORD) {
            throw ApiException.rule("password_weak", "Mot de passe : %d caractères au moins.".formatted(MIN_PASSWORD));
        }
        if (password.toLowerCase().contains(login)) {
            throw ApiException.rule("password_weak", "Le mot de passe ne doit pas contenir l’identifiant.");
        }
    }

    private static String truncate(String login) {
        return login == null ? null : login.length() > 60 ? login.substring(0, 60) : login;
    }
}
