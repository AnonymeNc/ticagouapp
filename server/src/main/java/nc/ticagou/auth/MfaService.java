package nc.ticagou.auth;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.AuthDtos.MfaSetup;
import nc.ticagou.common.ApiException;

/**
 * Double authentification (ADR 0013). La personne scanne le code, puis saisit un premier code pour l'activer. Elle
 * est obligatoire pour la direction et la comptabilité (voir {@link MfaGuard}) ; la direction peut la réinitialiser
 * pour une autre personne qui a perdu son téléphone, et l'éditeur pour la direction.
 */
@Service
public class MfaService {

    private final AppUserRepository users;
    private final TokenService tokens;
    private final AuditService audit;
    private final Clock clock;

    public MfaService(AppUserRepository users, TokenService tokens, AuditService audit, Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public MfaSetup start() {
        var user = current();
        if (user.isTotpEnabled()) {
            throw ApiException.conflict("mfa_already_enabled", "La double authentification est déjà active sur ce compte.");
        }
        var secret = Totp.newSecret();
        user.startTotp(secret);
        return new MfaSetup(secret, Totp.uri(user.getLogin(), secret));
    }

    @Transactional
    public void confirm(String code) {
        var user = current();
        if (user.isTotpEnabled()) {
            throw ApiException.conflict("mfa_already_enabled", "La double authentification est déjà active sur ce compte.");
        }
        if (user.getTotpSecret() == null) {
            throw ApiException.rule("mfa_not_started", "Scannez d’abord le code avec votre application.");
        }
        var now = clock.instant();
        var step = Totp.match(user.getTotpSecret(), code, now, null);
        if (step.isEmpty()) {
            throw ApiException.rule("mfa_invalid", "Ce code est incorrect ou a expiré : saisissez le code affiché maintenant.");
        }
        user.enableTotp(now, step.getAsLong());
        audit.record(AuditAction.MFA_ENABLED, "user", user.getId(), Map.of());
    }

    /** Téléphone perdu : la personne enregistrera un nouveau téléphone à sa prochaine connexion. */
    @Transactional
    public void reset(UUID userId) {
        var user = users.findById(userId).filter(u -> u.getStructureId().equals(CurrentUser.get().structureId()))
                .orElseThrow(() -> ApiException.notFound("Utilisateur introuvable."));
        user.resetTotp();
        tokens.revokeAll(user.getId());
        audit.record(AuditAction.MFA_RESET, "user", user.getId(), Map.of());
    }

    private AppUser current() {
        var current = CurrentUser.get();
        if (current.method() != AuthMethod.PASSWORD) {
            throw ApiException.forbidden("mfa_password_only", "La double authentification se règle depuis une session par mot de passe.");
        }
        return users.findById(current.userId()).orElseThrow(AuthenticationFailedException::sessionExpired);
    }
}
