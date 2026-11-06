package nc.ticagou.auth;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.audit.AuditService.Actor;
import nc.ticagou.auth.AuthDtos.MeResponse;
import nc.ticagou.auth.AuthDtos.PinUser;
import nc.ticagou.auth.AuthDtos.SessionResponse;
import nc.ticagou.common.ApiException;
import nc.ticagou.common.ClientIp;
import nc.ticagou.config.TiCagouProperties;
import nc.ticagou.device.DeviceService;
import nc.ticagou.license.DesktopClientService;

@Service
public class AuthService {

    private final AppUserRepository users;
    private final DeviceService devices;
    private final DesktopClientService desktops;
    private final PasswordEncoder encoder;
    private final TokenService tokens;
    private final AuditService audit;
    private final TiCagouProperties.Security settings;
    private final Clock clock;
    private final LoginThrottle throttle;
    /** Haché factice : un identifiant inconnu coûte autant qu'un mauvais mot de passe (pas d'énumération par le temps). */
    private final String dummyHash;

    public AuthService(AppUserRepository users, DeviceService devices, DesktopClientService desktops,
                       PasswordEncoder encoder, TokenService tokens, AuditService audit, TiCagouProperties properties,
                       Clock clock, LoginThrottle throttle) {
        this.throttle = throttle;
        this.users = users;
        this.devices = devices;
        this.desktops = desktops;
        this.encoder = encoder;
        this.tokens = tokens;
        this.audit = audit;
        this.settings = properties.security();
        this.clock = clock;
        this.dummyHash = encoder.encode("ticagou-dummy-password");
    }

    // Un PC refusé par la licence (ApiException) est tout de même tracé dans le journal.
    @Transactional(noRollbackFor = {AuthenticationFailedException.class, ApiException.class})
    public SessionResponse login(String login, String password, String code, String clientId, String clientName) {
        var now = clock.instant();
        var address = String.valueOf(ClientIp.current());
        if (throttle.isBlocked(address)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "login_throttled",
                    "Trop de connexions manquées depuis cette adresse. Patientez quelques minutes.");
        }
        var user = users.findByLoginIgnoreCase(login.trim()).orElse(null);
        if (user == null || user.getPasswordHash() == null || !user.isActive()) {
            encoder.matches(password, dummyHash);
            throttle.failed(address);
            audit.record(user == null ? Actor.anonymous() : actor(user), AuditAction.LOGIN_FAILED, "user",
                    user == null ? null : user.getId(), Map.of("login", login));
            throw AuthenticationFailedException.badCredentials();
        }
        if (user.isPasswordLocked(now)) {
            audit.record(actor(user), AuditAction.LOGIN_LOCKED, "user", user.getId(), Map.of());
            throw AuthenticationFailedException.locked(user.getLockedUntil());
        }
        if (!encoder.matches(password, user.getPasswordHash())) {
            throttle.failed(address);
            user.passwordFailed(now, settings.maxFailedAttempts(), settings.lockDuration());
            audit.record(actor(user), AuditAction.LOGIN_FAILED, "user", user.getId(), Map.of("login", login));
            if (user.isPasswordLocked(now)) {
                throw AuthenticationFailedException.locked(user.getLockedUntil());
            }
            throw AuthenticationFailedException.badCredentials();
        }
        if (user.isTotpEnabled()) {
            checkTotp(user, code, address);
        }
        throttle.succeeded(address);
        desktops.admit(user.getStructureId(), clientId, clientName, user.displayName());
        user.passwordSucceeded(now);
        audit.record(actor(user), AuditAction.LOGIN_SUCCEEDED, "user", user.getId(), Map.of());
        return session(tokens.issue(user, AuthMethod.PASSWORD), user, AuthMethod.PASSWORD);
    }

    /** Sans code : le mot de passe est bon, le navigateur demande le code. Un code faux compte comme un échec. */
    private void checkTotp(AppUser user, String code, String address) {
        if (code == null || code.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "mfa_required", "Saisissez le code de votre application.");
        }
        var now = clock.instant();
        var step = Totp.match(user.getTotpSecret(), code.strip(), now, user.getTotpLastStep());
        if (step.isEmpty()) {
            throttle.failed(address);
            user.passwordFailed(now, settings.maxFailedAttempts(), settings.lockDuration());
            audit.record(actor(user), AuditAction.LOGIN_FAILED, "user", user.getId(), Map.of("mfa", true));
            throw new ApiException(HttpStatus.UNAUTHORIZED, "mfa_invalid", "Ce code est incorrect ou a déjà servi.");
        }
        user.totpUsed(step.getAsLong());
    }

    private SessionResponse session(TokenService.TokenPair pair, AppUser user, AuthMethod method) {
        return SessionResponse.of(pair, user, method, settings.mfaRequired());
    }

    @Transactional(noRollbackFor = AuthenticationFailedException.class)
    public SessionResponse loginWithPin(UUID userId, String pin) {
        var now = clock.instant();
        var device = devices.requireCurrent();
        var user = users.findById(userId).filter(u -> u.getStructureId().equals(device.getStructureId())).orElse(null);
        if (user == null || user.getPinHash() == null || !user.isActive()) {
            encoder.matches(pin, dummyHash);
            throw AuthenticationFailedException.badPin();
        }
        if (user.isPinLocked(now)) {
            audit.record(actor(user), AuditAction.PIN_LOCKED, "user", user.getId(), Map.of());
            throw AuthenticationFailedException.locked(user.getPinLockedUntil());
        }
        if (!encoder.matches(pin, user.getPinHash())) {
            user.pinFailed(now, settings.maxFailedAttempts(), settings.lockDuration());
            audit.record(actor(user), AuditAction.PIN_LOGIN_FAILED, "user", user.getId(), Map.of());
            if (user.isPinLocked(now)) {
                throw AuthenticationFailedException.locked(user.getPinLockedUntil());
            }
            throw AuthenticationFailedException.badPin();
        }
        user.pinSucceeded(now);
        audit.record(actor(user), AuditAction.PIN_LOGIN_SUCCEEDED, "user", user.getId(),
                Map.of("device", device.getName()));
        return session(tokens.issue(user, AuthMethod.PIN), user, AuthMethod.PIN);
    }

    @Transactional(noRollbackFor = {AuthenticationFailedException.class, ApiException.class})
    public SessionResponse refresh(String refreshToken, String clientId, String clientName) {
        var rotation = tokens.consume(refreshToken);
        if (rotation.reused()) {
            // Jeton déjà utilisé : vol probable, toute la session est révoquée.
            audit.record(new Actor(rotation.structureId(), rotation.userId(), null),
                    AuditAction.REFRESH_TOKEN_REUSED, "user", rotation.userId(), Map.of());
            throw AuthenticationFailedException.sessionExpired();
        }
        var previous = rotation.token().orElseThrow(AuthenticationFailedException::sessionExpired);
        var user = users.findById(previous.getUserId()).filter(AppUser::isActive)
                .orElseThrow(AuthenticationFailedException::sessionExpired);
        if (previous.getAuthMethod() == AuthMethod.PASSWORD) {
            // PC du bureau : un PC retiré par la direction perd sa session au prochain renouvellement.
            desktops.admit(user.getStructureId(), clientId, clientName, user.displayName());
        }
        return session(tokens.rotate(user, previous), user, previous.getAuthMethod());
    }

    @Transactional
    public void logout(String refreshToken) {
        tokens.revokeSession(refreshToken)
                .flatMap(token -> users.findById(token.getUserId()))
                .ifPresent(user -> audit.record(actor(user), AuditAction.LOGOUT, "user", user.getId(), Map.of()));
    }

    @Transactional(readOnly = true)
    public MeResponse me() {
        var current = CurrentUser.get();
        var user = users.findById(current.userId()).orElseThrow(AuthenticationFailedException::sessionExpired);
        return MeResponse.of(user, current.method(), settings.mfaRequired());
    }

    @Transactional
    public void changeOwnPassword(String currentPassword, String newPassword) {
        var current = CurrentUser.get();
        var user = users.findById(current.userId()).orElseThrow(AuthenticationFailedException::sessionExpired);
        if (user.getPasswordHash() == null || !encoder.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.rule("bad_current_password", "Le mot de passe actuel est incorrect.");
        }
        PasswordRules.checkPassword(newPassword);
        user.changePassword(user.getLogin(), encoder.encode(newPassword));
        audit.record(AuditAction.PASSWORD_CHANGED, "user", user.getId(), Map.of());
    }

    /** Personnes pouvant se connecter par PIN sur la tablette appairée de la requête (sa structure). */
    @Transactional(readOnly = true)
    public List<PinUser> pinUsers() {
        var device = devices.requireCurrent();
        return users.findByStructureIdAndActiveTrueAndPinHashIsNotNullOrderByFirstNameAscLastNameAsc(
                        device.getStructureId())
                .stream().map(PinUser::of).toList();
    }

    private static Actor actor(AppUser user) {
        return new Actor(user.getStructureId(), user.getId(), user.displayName());
    }
}
