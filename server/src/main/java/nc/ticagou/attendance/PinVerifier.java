package nc.ticagou.attendance;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.audit.AuditService.Actor;
import nc.ticagou.auth.AppUser;
import nc.ticagou.auth.AppUserRepository;
import nc.ticagou.auth.Role;
import nc.ticagou.common.ApiException;
import nc.ticagou.config.TiCagouProperties;
import nc.ticagou.device.DeviceRepository;

/**
 * Vérification des codes PIN du pointage. Les échecs sont enregistrés dans leur propre transaction, pour que
 * le blocage tienne même si l'opération appelante est refusée.
 */
@Service
public class PinVerifier {

    /** Blocage du pointage par PIN seul sur une tablette, après 5 codes inconnus. */
    static final Duration DEVICE_LOCK = Duration.ofMinutes(2);

    private final AppUserRepository users;
    private final DeviceRepository devices;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final TiCagouProperties.Security settings;
    private final Clock clock;

    public PinVerifier(AppUserRepository users, DeviceRepository devices, PasswordEncoder encoder, AuditService audit,
                       TiCagouProperties properties, Clock clock) {
        this.users = users;
        this.devices = devices;
        this.encoder = encoder;
        this.audit = audit;
        this.settings = properties.security();
        this.clock = clock;
    }

    /**
     * Code personnel d'un utilisateur précis (validation d'un départ par la directrice). Échecs comptés sur
     * le compte, comme à la connexion : 5 erreurs le bloquent 15 minutes.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = ApiException.class)
    public AppUser verify(UUID structureId, UUID userId, String pin, Set<Role> roles) {
        var now = clock.instant();
        var user = users.findByIdAndStructureId(userId, structureId)
                .filter(u -> u.isActive() && u.getPinHash() != null && roles.contains(u.getRole()))
                .orElseThrow(() -> ApiException.rule("validator_unknown",
                        "Seule une personne de la direction peut valider ce départ."));
        if (user.isPinLocked(now)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "locked",
                    "Trop d’essais incorrects. Réessayez dans quelques minutes.");
        }
        if (!encoder.matches(pin, user.getPinHash())) {
            user.pinFailed(now, settings.maxFailedAttempts(), settings.lockDuration());
            audit.record(actor(user), AuditAction.PIN_LOGIN_FAILED, "user", user.getId(), Map.of("usage", "validation"));
            throw ApiException.rule("bad_director_pin", "Code incorrect : le départ reste bloqué.");
        }
        user.pinSucceeded(now);
        return user;
    }

    /** Vérification après coup d'un code saisi hors connexion : aucun compteur, la tablette n'a rien débloqué. */
    @Transactional(readOnly = true)
    public boolean matches(UUID structureId, UUID userId, String pin) {
        return pin != null && users.findByIdAndStructureId(userId, structureId)
                .filter(u -> u.isActive() && u.getPinHash() != null)
                .map(u -> encoder.matches(pin, u.getPinHash()))
                .orElse(false);
    }

    /**
     * Personne reconnue à son seul code parmi le personnel à PIN de la structure (pointage du personnel).
     * 5 codes inconnus bloquent ce pointage 2 minutes sur la tablette.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = ApiException.class)
    public AppUser identify(UUID structureId, UUID deviceId, String pin) {
        var now = clock.instant();
        var device = devices.findById(deviceId).orElseThrow();
        if (device.isPinLocked(now)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "locked",
                    "Trop de codes incorrects. Le pointage par code reprend dans 2 minutes.");
        }
        var matching = users.findByStructureIdAndActiveTrueAndPinHashIsNotNullOrderByFirstNameAscLastNameAsc(structureId)
                .stream().filter(u -> encoder.matches(pin, u.getPinHash())).toList();
        if (matching.isEmpty()) {
            device.pinFailed(now, settings.maxFailedAttempts(), DEVICE_LOCK);
            audit.record(new Actor(structureId, null, device.getName()), AuditAction.STAFF_PIN_FAILED, "device",
                    deviceId, Map.of());
            if (device.isPinLocked(now)) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "locked",
                        "Trop de codes incorrects. Le pointage par code reprend dans 2 minutes.");
            }
            throw new ApiException(HttpStatus.UNAUTHORIZED, "bad_pin", "Code inconnu, réessayez.");
        }
        if (matching.size() > 1) {
            audit.record(new Actor(structureId, null, device.getName()), AuditAction.STAFF_PIN_AMBIGUOUS, "device",
                    deviceId, Map.of("users", matching.size()));
            throw ApiException.conflict("pin_ambiguous",
                    "Ce code est partagé par plusieurs personnes : touchez votre nom, puis demandez à la direction d’en changer.");
        }
        device.pinSucceeded();
        return matching.getFirst();
    }

    public Optional<AppUser> find(UUID structureId, UUID userId) {
        return users.findByIdAndStructureId(userId, structureId).filter(AppUser::isActive);
    }

    private static Actor actor(AppUser user) {
        return new Actor(user.getStructureId(), user.getId(), user.displayName());
    }
}
