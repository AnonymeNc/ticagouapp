package nc.ticagou.auth;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.UserDtos.CreateUserRequest;
import nc.ticagou.auth.UserDtos.UpdateUserRequest;
import nc.ticagou.auth.UserDtos.UserDto;
import nc.ticagou.common.ApiException;
import nc.ticagou.staffing.StaffingChanged;
import nc.ticagou.structure.SectionRepository;

/** Paramètres › Utilisateurs et rôles (direction uniquement). */
@Service
public class UserService {

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final TokenService tokens;
    private final AuditService audit;
    private final SectionRepository sections;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public UserService(AppUserRepository users, PasswordEncoder encoder, TokenService tokens, AuditService audit,
                       SectionRepository sections, ApplicationEventPublisher events, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.tokens = tokens;
        this.audit = audit;
        this.sections = sections;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<UserDto> list() {
        var now = clock.instant();
        return users.findByStructureIdOrderByLastNameAscFirstNameAsc(CurrentUser.get().structureId())
                .stream().map(u -> UserDto.of(u, now)).toList();
    }

    /** Création d'un compte, sans contrôle de rôle : utilisée aussi par l'assistant de premier lancement. */
    @Transactional
    public AppUser createAccount(UUID structureId, CreateUserRequest request) {
        var login = normalizeLogin(request.login());
        if (login != null && users.existsByLoginIgnoreCase(login)) {
            throw ApiException.conflict("login_taken", "Cet identifiant est déjà utilisé.");
        }
        var user = new AppUser(structureId, request.firstName().trim(), request.lastName().trim(), request.role());
        if (request.password() != null) {
            PasswordRules.checkPassword(request.password());
            user.changePassword(login, encoder.encode(request.password()));
        } else {
            user.updateProfile(user.getFirstName(), user.getLastName(), user.getRole(), login, true);
        }
        if (request.pin() != null) {
            PasswordRules.checkPin(request.pin());
            ensurePinAvailable(structureId, request.pin(), null);
            user.changePin(encoder.encode(request.pin()));
        }
        user.updateStaffing(blankToNull(request.jobTitle()), checkSection(structureId, request.homeSectionId()),
                request.countsInRatio() != null ? request.countsInRatio() : request.role() == Role.EDUCATRICE);
        return users.saveAndFlush(user);
    }

    @Transactional
    public UserDto create(CreateUserRequest request) {
        var user = createAccount(CurrentUser.get().structureId(), request);
        audit.record(AuditAction.USER_CREATED, "user", user.getId(), describe(user));
        return UserDto.of(user, clock.instant());
    }

    @Transactional
    public UserDto update(UUID id, UpdateUserRequest request) {
        var current = CurrentUser.get();
        var user = load(id);
        var login = normalizeLogin(request.login());
        if (login == null && user.getPasswordHash() != null) {
            throw ApiException.rule("login_required", "Un identifiant est nécessaire pour se connecter avec un mot de passe.");
        }
        if (login != null && users.existsByLoginIgnoreCaseAndIdNot(login, id)) {
            throw ApiException.conflict("login_taken", "Cet identifiant est déjà utilisé.");
        }
        boolean losesDirection = user.getRole() == Role.DIRECTION && user.isActive()
                && (request.role() != Role.DIRECTION || !request.active());
        if (losesDirection) {
            if (id.equals(current.userId())) {
                throw ApiException.rule("self_demotion", "Vous ne pouvez pas retirer vos propres droits de direction.");
            }
            if (users.countByStructureIdAndRoleAndActiveTrue(current.structureId(), Role.DIRECTION) <= 1) {
                throw ApiException.rule("last_director", "La structure doit garder au moins un compte direction actif.");
            }
        }
        boolean deactivated = user.isActive() && !request.active();
        boolean roleChanged = user.getRole() != request.role();
        user.updateProfile(request.firstName().trim(), request.lastName().trim(), request.role(), login,
                request.active());
        user.updateStaffing(blankToNull(request.jobTitle()), checkSection(current.structureId(), request.homeSectionId()),
                request.countsInRatio() != null ? request.countsInRatio() : user.isCountsInRatio());
        users.saveAndFlush(user);
        events.publishEvent(new StaffingChanged(current.structureId()));
        if (deactivated || roleChanged) {
            // Le rôle est porté par le jeton : les sessions ouvertes sont fermées.
            tokens.revokeAll(id);
        }
        audit.record(AuditAction.USER_UPDATED, "user", id, describe(user));
        return UserDto.of(user, clock.instant());
    }

    @Transactional
    public void resetPassword(UUID id, String password) {
        var user = load(id);
        if (user.getLogin() == null) {
            throw ApiException.rule("login_required", "Définissez d’abord un identifiant pour cet utilisateur.");
        }
        PasswordRules.checkPassword(password);
        user.changePassword(user.getLogin(), encoder.encode(password));
        users.saveAndFlush(user);
        tokens.revokeAll(id);
        audit.record(AuditAction.USER_PASSWORD_RESET, "user", id, Map.of());
    }

    @Transactional
    public void resetPin(UUID id, String pin) {
        var user = load(id);
        PasswordRules.checkPin(pin);
        ensurePinAvailable(user.getStructureId(), pin, id);
        user.changePin(encoder.encode(pin));
        users.saveAndFlush(user);
        audit.record(AuditAction.USER_PIN_RESET, "user", id, Map.of());
    }

    /**
     * Le pointage du personnel reconnaît la personne à son seul PIN : deux personnes actives d'une structure
     * ne peuvent pas avoir le même code. La comparaison Argon2 passe par tous les codes (une vingtaine au plus).
     */
    private void ensurePinAvailable(UUID structureId, String pin, UUID exceptUserId) {
        for (var other : users.findByStructureIdAndActiveTrueAndPinHashIsNotNullOrderByFirstNameAscLastNameAsc(structureId)) {
            if (!other.getId().equals(exceptUserId) && encoder.matches(pin, other.getPinHash())) {
                throw ApiException.conflict("pin_taken",
                        "Ce code PIN est déjà utilisé par une autre personne. Choisissez-en un autre.");
            }
        }
    }

    private UUID checkSection(UUID structureId, UUID sectionId) {
        if (sectionId != null && sections.findByIdAndStructureId(sectionId, structureId).isEmpty()) {
            throw ApiException.rule("section_unknown", "La section habituelle n’existe pas.");
        }
        return sectionId;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private AppUser load(UUID id) {
        return users.findByIdAndStructureId(id, CurrentUser.get().structureId())
                .orElseThrow(() -> ApiException.notFound("Utilisateur introuvable."));
    }

    private static String normalizeLogin(String login) {
        return login == null || login.isBlank() ? null : login.trim().toLowerCase();
    }

    private static Map<String, Object> describe(AppUser user) {
        var details = new LinkedHashMap<String, Object>();
        details.put("name", user.displayName());
        details.put("role", user.getRole().name());
        details.put("active", user.isActive());
        details.put("countsInRatio", user.isCountsInRatio());
        if (user.getLogin() != null) {
            details.put("login", user.getLogin());
        }
        return details;
    }
}
