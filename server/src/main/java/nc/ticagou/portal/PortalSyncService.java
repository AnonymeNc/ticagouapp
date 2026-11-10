package nc.ticagou.portal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.audit.AuditService.Actor;
import nc.ticagou.license.LicenseService;
import nc.ticagou.portal.PortalClient.PortalException;
import nc.ticagou.portal.SyncDtos.Batch;
import nc.ticagou.portal.SyncDtos.BatchItem;

/**
 * Synchronisation avec le portail parents (ADR 0010), lancée chaque minute et à la demande : envoi de la file par
 * lots (200 éléments, 8 Mo au plus), puis lecture et acquittement de ce que les parents ont envoyé. Après un échec,
 * l'attente double jusqu'à 30 minutes. Une structure en cours d'effacement n'envoie que l'ordre d'effacement.
 */
@Service
public class PortalSyncService {

    private static final Logger log = LoggerFactory.getLogger(PortalSyncService.class);

    static final int MAX_ITEMS = 200;
    static final long MAX_BYTES = 8L * 1024 * 1024;
    static final Duration FIRST_RETRY = Duration.ofMinutes(1);
    static final Duration MAX_RETRY = Duration.ofMinutes(30);
    private static final byte[] BATCH_START = "{\"items\":[".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BATCH_END = "]}".getBytes(StandardCharsets.UTF_8);

    /** Résultat d'un passage, affiché dans Paramètres › Portail parents. */
    public record SyncResult(int sent, int received, String error) {
    }

    private final PortalSettingsRepository settingsRepository;
    private final PortalOutboxRepository outbox;
    private final PortalProjections projections;
    private final PortalClient client;
    private final PortalKeys keys;
    private final InboxProcessor inbox;
    private final LicenseService licenses;
    private final AuditService audit;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    public PortalSyncService(PortalSettingsRepository settingsRepository, PortalOutboxRepository outbox,
                             PortalProjections projections, PortalClient client, PortalKeys keys, InboxProcessor inbox,
                             LicenseService licenses, AuditService audit, ObjectMapper json,
                             PlatformTransactionManager transactionManager, Clock clock) {
        this.settingsRepository = settingsRepository;
        this.outbox = outbox;
        this.projections = projections;
        this.client = client;
        this.keys = keys;
        this.inbox = inbox;
        this.licenses = licenses;
        this.audit = audit;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Toutes les structures dont le portail est actif ou en cours d'effacement, en respectant l'attente. */
    public void syncAll() {
        for (var settings : settingsRepository.findByStateNot(PortalSettings.State.DESACTIVE)) {
            if (due(settings, clock.instant())) {
                sync(settings.getStructureId());
            }
        }
    }

    /** Délai avant une nouvelle tentative : 1, 2, 4… minutes après les échecs successifs, 30 au plus. */
    static Duration retryDelay(int failures) {
        if (failures <= 0) {
            return Duration.ZERO;
        }
        var delay = FIRST_RETRY.multipliedBy(1L << Math.min(failures - 1, 10));
        return delay.compareTo(MAX_RETRY) > 0 ? MAX_RETRY : delay;
    }

    static boolean due(PortalSettings settings, Instant now) {
        return settings.getLastAttemptAt() == null
                || !now.isBefore(settings.getLastAttemptAt().plus(retryDelay(settings.getFailures())));
    }

    public SyncResult sync(UUID structureId) {
        if (!lock.tryLock()) {
            return new SyncResult(0, 0, "Synchronisation déjà en cours.");
        }
        try {
            var settings = settingsRepository.findById(structureId).orElse(null);
            if (settings == null || settings.getState() == PortalSettings.State.DESACTIVE) {
                return new SyncResult(0, 0, null);
            }
            if (settings.getState() == PortalSettings.State.PURGE_EN_COURS) {
                return purge(settings);
            }
            try {
                settings = announceAccess(structureId);
                int sent = sendPending(settings);
                int received = inbox.pull(settings, keys.privateKey(structureId));
                record(structureId, null);
                return new SyncResult(sent, received, null);
            } catch (PortalException | IllegalStateException e) {
                log.warn("Synchronisation du portail parents en échec : {}", e.getMessage());
                record(structureId, e.getMessage());
                return new SyncResult(0, 0, e.getMessage());
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Fin d'accès des parents (fin d'abonnement plus la grâce, ou fin d'évaluation) : quand elle change, après un
     * renouvellement par exemple, la structure repart au portail, qui allonge ou coupe les sessions des parents.
     */
    private PortalSettings announceAccess(UUID structureId) {
        return transactions.execute(s -> {
            var current = settingsRepository.findById(structureId).orElseThrow();
            var accessUntil = licenses.lastWritableDay();
            if (!accessUntil.equals(current.getAccessUntilSent())) {
                if (!outbox.existsByStructureIdAndTypeAndEntityIdAndSentAtIsNull(structureId, PortalItemType.STRUCTURE,
                        structureId)) {
                    outbox.save(new PortalOutboxItem(structureId, PortalItemType.STRUCTURE, structureId, clock.instant()));
                }
                current.accessUntilSent(accessUntil);
                settingsRepository.save(current);
            }
            return current;
        });
    }

    /** Passe la structure en effacement : la file est vidée, seul l'ordre PURGE_STRUCTURE partira. */
    void startPurge(UUID structureId, String reason) {
        var settings = settingsRepository.findById(structureId).orElseThrow();
        outbox.deleteByStructureId(structureId);
        settings.startPurge(clock.instant());
        settingsRepository.save(settings);
        audit.record(new Actor(structureId, null, "Ti'Cagou"), AuditAction.PORTAL_DEACTIVATED, "portal_settings",
                structureId, java.util.Map.of("raison", reason));
    }

    private int sendPending(PortalSettings settings) {
        var key = keys.privateKey(settings.getStructureId());
        int total = 0;
        while (true) {
            var pending = outbox.findByStructureIdAndSentAtIsNullOrderBySeqAsc(settings.getStructureId(),
                    Limit.of(MAX_ITEMS));
            if (pending.isEmpty()) {
                return total;
            }
            // Chaque élément est sérialisé une seule fois : sa taille décide du lot, ses octets forment le corps.
            var body = new ByteArrayOutputStream();
            body.writeBytes(BATCH_START);
            var included = new ArrayList<PortalOutboxItem>();
            for (var item : pending) {
                var projection = projections.build(settings, item.getType(), item.getEntityId());
                var bytes = serialize(new BatchItem(item.getSeq(), item.getType(), item.getEntityId(), projection.deleted(),
                        projection.payload()));
                if (!included.isEmpty() && body.size() + bytes.length + 1 > MAX_BYTES) {
                    break;
                }
                if (!included.isEmpty()) {
                    body.write(',');
                }
                body.writeBytes(bytes);
                included.add(item);
            }
            body.writeBytes(BATCH_END);
            var response = client.batch(settings.getPortalUrl(), settings.getPortalStructureId(), key, body.toByteArray());
            long accepted = response == null ? 0 : response.acceptedUpTo();
            var now = clock.instant();
            var done = included.stream().map(PortalOutboxItem::getSeq).filter(seq -> seq <= accepted).toList();
            transactions.executeWithoutResult(s -> outbox.markSent(done, now));
            total += done.size();
            if (done.size() < included.size()) {
                throw new PortalException("Le portail n’a accepté qu’une partie de l’envoi.", 0);
            }
        }
    }

    private SyncResult purge(PortalSettings settings) {
        var structureId = settings.getStructureId();
        try {
            if (settings.getPortalStructureId() != null) {
                var item = new BatchItem(Long.MAX_VALUE, PortalItemType.PURGE_STRUCTURE, structureId, true, null);
                client.batch(settings.getPortalUrl(), settings.getPortalStructureId(), keys.privateKey(structureId),
                        new Batch(List.of(item)));
            }
            transactions.executeWithoutResult(s -> {
                var current = settingsRepository.findById(structureId).orElseThrow();
                current.disabled(clock.instant());
                settingsRepository.save(current);
                outbox.deleteByStructureId(structureId);
                keys.forget(structureId);
                audit.record(new Actor(structureId, null, "Ti'Cagou"), AuditAction.PORTAL_PURGED, "portal_settings",
                        structureId, java.util.Map.of());
            });
            return new SyncResult(1, 0, null);
        } catch (PortalException | IllegalStateException e) {
            log.warn("Effacement du portail parents en échec : {}", e.getMessage());
            record(structureId, e.getMessage());
            return new SyncResult(0, 0, e.getMessage());
        }
    }

    private void record(UUID structureId, String error) {
        transactions.executeWithoutResult(s -> settingsRepository.findById(structureId).ifPresent(current -> {
            if (error == null) {
                current.succeeded(clock.instant());
            } else {
                current.failed(error, clock.instant());
            }
        }));
    }

    private byte[] serialize(BatchItem item) {
        try {
            return json.writeValueAsBytes(item);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
