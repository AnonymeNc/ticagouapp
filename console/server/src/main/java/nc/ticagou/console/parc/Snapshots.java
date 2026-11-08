package nc.ticagou.console.parc;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import nc.ticagou.console.ConsoleProperties;

/**
 * Instantanés des crèches : un par état de supervision reçu (toutes les 15 min), réduits à un par jour au-delà de
 * 30 jours. Ils servent aux courbes de la fiche (disque, usage).
 */
@Service
public class Snapshots {

    private static final Logger log = LoggerFactory.getLogger(Snapshots.class);
    static final Duration DETAIL = Duration.ofDays(30);

    private final ParcRepository repository;
    private final ConsoleProperties properties;
    private final Clock clock;

    public Snapshots(ParcRepository repository, ConsoleProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /** Les seuls nombres gardés d'un état : version, disques, usage. */
    static ObjectNode compact(ObjectMapper json, JsonNode status) {
        var data = json.createObjectNode();
        copyText(data, "version", status, "/version");
        copy(data, "dataFree", status, "/disk/dataFreeBytes");
        copy(data, "dataTotal", status, "/disk/dataTotalBytes");
        copy(data, "backupFree", status, "/disk/backupFreeBytes");
        copy(data, "backupTotal", status, "/disk/backupTotalBytes");
        copy(data, "tablets", status, "/tablets/paired");
        copy(data, "desktops", status, "/license/desktops");
        copy(data, "activeChildren", status, "/usage/activeChildren");
        copy(data, "attendances7d", status, "/usage/attendances7d");
        copy(data, "activeUsers30d", status, "/usage/activeUsers30d");
        return data;
    }

    private static void copy(ObjectNode target, String name, JsonNode status, String pointer) {
        var value = status.at(pointer);
        if (value.isNumber()) {
            target.put(name, value.asLong());
        }
    }

    private static void copyText(ObjectNode target, String name, JsonNode status, String pointer) {
        var value = status.at(pointer);
        if (value.isTextual()) {
            target.put(name, value.asText());
        }
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Pacific/Noumea")
    public void scheduled() {
        if (properties.scheduling()) {
            reduce();
        }
    }

    public int reduce() {
        int removed = repository.reduce(clock.instant().minus(DETAIL));
        if (removed > 0) {
            log.info("Instantanés : {} réduits à un par jour", removed);
        }
        return removed;
    }
}
