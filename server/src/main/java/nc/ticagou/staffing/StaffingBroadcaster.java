package nc.ticagou.staffing;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import nc.ticagou.staffing.StaffingDtos.StaffingDto;

/**
 * Diffusion en temps réel (SSE) vers le tableau de bord et les tablettes d'une structure. Recalcule après
 * chaque modification validée, et chaque minute (anniversaires, changement de règle, nouvelle journée).
 */
@Component
public class StaffingBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(StaffingBroadcaster.class);

    private final StaffingService staffing;
    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Map<UUID, StaffingDto> last = new ConcurrentHashMap<>();

    /** Désactivé dans les tests d'intégration : le recalcul périodique concurrencerait la remise à zéro de la base. */
    private final boolean scheduled;

    public StaffingBroadcaster(StaffingService staffing,
                               @Value("${ticagou.scheduling.enabled:true}") boolean scheduled) {
        this.staffing = staffing;
        this.scheduled = scheduled;
    }

    public SseEmitter subscribe(UUID structureId) {
        var emitter = new SseEmitter(0L);
        var list = emitters.computeIfAbsent(structureId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        emitter.onCompletion(() -> list.remove(emitter));
        emitter.onTimeout(() -> list.remove(emitter));
        emitter.onError(e -> list.remove(emitter));
        var snapshot = staffing.snapshot(structureId);
        last.put(structureId, snapshot);
        send(structureId, emitter, "ratio", snapshot);
        return emitter;
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onChange(StaffingChanged event) {
        try {
            refresh(event.structureId(), true);
        } catch (RuntimeException e) {
            // La modification est validée : un échec de diffusion ne doit pas la faire paraître refusée.
            log.warn("Diffusion de l'encadrement impossible pour {}", event.structureId(), e);
        }
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void tick() {
        if (!scheduled) {
            return;
        }
        for (var structureId : staffing.structureIds()) {
            try {
                refresh(structureId, false);
            } catch (RuntimeException e) {
                log.warn("Recalcul de l'encadrement impossible pour {}", structureId, e);
            }
        }
    }

    @Scheduled(fixedDelay = 25_000, initialDelay = 25_000)
    public void ping() {
        emitters.forEach((structureId, list) -> list.forEach(e -> send(structureId, e, "ping", "")));
    }

    void refresh(UUID structureId, boolean attendanceChanged) {
        var snapshot = staffing.snapshot(structureId);
        var alerts = staffing.track(structureId, snapshot);
        var previous = last.put(structureId, snapshot);
        var list = emitters.getOrDefault(structureId, List.of());
        boolean changed = previous == null || !previous.global().equals(snapshot.global())
                || !previous.sections().equals(snapshot.sections()) || !previous.day().equals(snapshot.day());
        for (var emitter : list) {
            if (changed || attendanceChanged) {
                send(structureId, emitter, "ratio", snapshot);
            }
            if (attendanceChanged) {
                send(structureId, emitter, "attendance", Map.of("at", snapshot.at().toString()));
            }
            for (var alert : alerts) {
                send(structureId, emitter, "alert", alert);
            }
        }
    }

    private void send(UUID structureId, SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            emitters.getOrDefault(structureId, List.of()).remove(emitter);
        }
    }
}
