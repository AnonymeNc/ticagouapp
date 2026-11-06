package nc.ticagou.setup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.audit.AuditService.Actor;
import nc.ticagou.auth.AuthDtos.SessionResponse;
import nc.ticagou.auth.AuthService;
import nc.ticagou.auth.Role;
import nc.ticagou.auth.UserDtos.CreateUserRequest;
import nc.ticagou.auth.UserService;
import nc.ticagou.billing.BillingSettingsService;
import nc.ticagou.common.ApiException;
import nc.ticagou.common.ClientIp;
import nc.ticagou.config.TiCagouProperties;
import nc.ticagou.enrollment.EnrollmentCriterionService;
import nc.ticagou.setup.SetupDtos.SetupRequest;
import nc.ticagou.meal.DietService;
import nc.ticagou.staffing.RatioRuleService;
import nc.ticagou.structure.StructureService;

/**
 * Assistant de premier lancement : crée la structure, ses sections, les critères d'inscription et les règles
 * d'encadrement et les régimes par défaut et le compte de la direction, puis ouvre la session de la directrice.
 * Possible une seule fois, avec le jeton remis par l'éditeur à la création de l'instance (ADR 0013).
 */
@Service
public class SetupService {

    private final JdbcTemplate jdbc;
    private final StructureService structures;
    private final UserService users;
    private final AuthService auth;
    private final AuditService audit;
    private final EnrollmentCriterionService criteria;
    private final RatioRuleService ratioRules;
    private final DietService diets;
    private final BillingSettingsService billingSettings;
    private final String setupToken;
    private final Clock clock;

    public SetupService(JdbcTemplate jdbc, StructureService structures, UserService users, AuthService auth,
                        AuditService audit, EnrollmentCriterionService criteria, RatioRuleService ratioRules,
                        DietService diets, BillingSettingsService billingSettings, TiCagouProperties properties, Clock clock) {
        this.setupToken = properties.setup().token();
        this.diets = diets;
        this.billingSettings = billingSettings;
        this.jdbc = jdbc;
        this.structures = structures;
        this.users = users;
        this.auth = auth;
        this.audit = audit;
        this.criteria = criteria;
        this.ratioRules = ratioRules;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SetupDtos.SetupStatus status() {
        boolean initialized = Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM installation)", Boolean.class));
        // Nom affiché seulement s'il n'y a qu'une structure (l'appairage des tablettes précisera laquelle).
        var names = jdbc.queryForList("SELECT name FROM structure LIMIT 2", String.class);
        return new SetupDtos.SetupStatus(initialized, names.size() == 1 ? names.getFirst() : null);
    }

    /** Sans jeton configuré (développement), l'assistant n'est ouvert que depuis ce poste. */
    private void requireInstallAccess(String token) {
        var allowed = setupToken.isBlank() ? ClientIp.isLoopback(ClientIp.current())
                : token != null && MessageDigest.isEqual(setupToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
        if (!allowed) {
            throw ApiException.forbidden("setup_token", "Ouvrez le lien de démarrage reçu de Ti’Cagou pour créer votre crèche.");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM installation)", Boolean.class))) {
            throw alreadyInitialized();
        }
    }

    @Transactional
    public SessionResponse complete(SetupRequest request, String token, String clientId, String clientName) {
        requireInstallAccess(token);
        var admin = request.admin();
        try {
            // Clé primaire unique : deux assistants lancés en même temps ne peuvent pas aboutir tous les deux.
            jdbc.update("INSERT INTO installation (id) VALUES (1)");
        } catch (DuplicateKeyException e) {
            throw alreadyInitialized();
        }
        if (jdbc.queryForObject("SELECT count(*) FROM structure", Integer.class) > 0) {
            throw alreadyInitialized();
        }

        var structure = structures.create(request.structure(), request.sections());
        criteria.createDefaults(structure.getId(), LocalDate.now(clock));
        ratioRules.createDefaults(structure.getId(), LocalDate.now(clock));
        diets.createDefaults(structure.getId());
        billingSettings.createDefaults(structure.getId(), LocalDate.now(clock));
        var director = users.createAccount(structure.getId(), new CreateUserRequest(
                admin.firstName(), admin.lastName(), Role.DIRECTION, admin.login(), admin.password(), null));
        audit.record(new Actor(structure.getId(), director.getId(), director.displayName()),
                AuditAction.SETUP_COMPLETED, "structure", structure.getId(),
                Map.of("structure", structure.getName(), "sections", request.sections().size()));

        return auth.login(admin.login(), admin.password(), null, clientId, clientName);
    }

    private static ApiException alreadyInitialized() {
        return ApiException.conflict("already_initialized", "Cette crèche est déjà créée.");
    }
}
