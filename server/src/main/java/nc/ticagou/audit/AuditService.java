package nc.ticagou.audit;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import nc.ticagou.auth.CurrentUser;
import nc.ticagou.common.ClientIp;

/**
 * Écrit le journal d'audit. Appelé dans la transaction de l'opération tracée : une modification
 * annulée n'est pas journalisée.
 */
@Service
public class AuditService {

    private final AuditEventRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuditService(AuditEventRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Action de l'utilisateur authentifié courant. */
    @Transactional
    public void record(AuditAction action, String targetType, Object targetId, Map<String, ?> details) {
        var user = CurrentUser.get();
        record(new Actor(user.structureId(), user.userId(), user.displayName()), action, targetType, targetId, details);
    }

    /** Action d'un acteur explicite (connexion, premier lancement : pas encore de jeton). */
    @Transactional
    public void record(Actor actor, AuditAction action, String targetType, Object targetId, Map<String, ?> details) {
        repository.save(new AuditEvent(
                actor.structureId(),
                clock.instant(),
                actor.userId(),
                actor.name(),
                action,
                targetType,
                targetId == null ? null : targetId.toString(),
                toJson(details),
                ClientIp.current()));
    }

    private String toJson(Map<String, ?> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Détails d'audit non sérialisables", e);
        }
    }

    /** Auteur d'une action ; {@code structureId} nul seulement pour un identifiant de connexion inconnu. */
    public record Actor(UUID structureId, UUID userId, String name) {

        public static Actor anonymous() {
            return new Actor(null, null, null);
        }
    }
}
