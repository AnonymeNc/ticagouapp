package nc.ticagou.audit;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.auth.CurrentUser;
import nc.ticagou.common.ClientIp;

/**
 * Journal des consultations de dossiers enfants. À appeler par chaque endpoint qui renvoie
 * la fiche, la santé, les documents ou l'export d'un enfant.
 */
@Service
public class ChildAccessLogger {

    private final ChildRecordAccessRepository repository;
    private final Clock clock;

    public ChildAccessLogger(ChildRecordAccessRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public void log(UUID childId, ChildAccessContext context) {
        var user = CurrentUser.get();
        log(user.structureId(), childId, user.userId(), user.displayName(), context);
    }

    /** Consultation par un auteur explicite (opération envoyée par une tablette). */
    @Transactional
    public void log(UUID structureId, UUID childId, UUID userId, String userName, ChildAccessContext context) {
        repository.save(new ChildRecordAccess(structureId, childId, userId, userName, clock.instant(), context,
                ClientIp.current()));
    }
}
