package nc.ticagou.console.parc;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import nc.ticagou.console.ConsoleProperties;
import nc.ticagou.console.audit.AuditService;

/**
 * Lecture de la supervision (ADR 0012) dans le portail, toutes les 5 minutes, par l'API d'administration du réseau
 * interne : état de chaque crèche (rapprochée par son slug), un instantané par état reçu, et l'historique des alertes
 * depuis la lecture précédente.
 */
@Service
public class SupervisionCollector {

    private static final Logger log = LoggerFactory.getLogger(SupervisionCollector.class);
    static final String SUPERVISION = "SUPERVISION";
    static final String ALERTES = "ALERTES";

    private final ParcRepository repository;
    private final AuditService audit;
    private final ConsoleProperties properties;
    private final ObjectMapper json;
    private final Clock clock;
    private final RestClient http;

    public SupervisionCollector(ParcRepository repository, AuditService audit, ConsoleProperties properties, ObjectMapper json,
                                Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 20_000)
    public void scheduled() {
        if (properties.scheduling()) {
            collect();
        }
    }

    /** Une lecture complète ; une panne du portail est notée sans rien effacer de ce qui a été lu avant. */
    public synchronized void collect() {
        var now = clock.instant();
        var token = properties.portalAdminToken();
        if (token == null || token.isBlank()) {
            repository.failed(SUPERVISION, now, "Jeton CONSOLE_PORTAL_ADMIN_TOKEN absent : supervision non lue.");
            return;
        }
        try {
            readSites(now);
        } catch (RestClientException | IllegalStateException e) {
            log.warn("Supervision illisible : {}", e.getMessage());
            repository.failed(SUPERVISION, now, "Portail injoignable ou en erreur : " + e.getMessage());
            return;
        } catch (RuntimeException e) {
            log.error("Lecture de la supervision interrompue", e);
            repository.failed(SUPERVISION, now, "Lecture interrompue : " + e.getMessage());
            return;
        }
        try {
            readAlerts(now);
        } catch (RestClientException | IllegalStateException e) {
            log.warn("Alertes illisibles : {}", e.getMessage());
            repository.failed(ALERTES, now, "Portail injoignable ou en erreur : " + e.getMessage());
        } catch (RuntimeException e) {
            log.error("Lecture des alertes interrompue", e);
            repository.failed(ALERTES, now, "Lecture interrompue : " + e.getMessage());
        }
    }

    private void readSites(Instant now) {
        var sites = get(URI.create(base() + "/admin/v1/supervision"));
        var unlinked = new ArrayList<String>();
        var rejected = new ArrayList<String>();
        int read = 0;
        for (var site : sites) {
            var slug = Json.text(site, "/slug");
            var name = Json.text(site, "/name");
            if (slug == null) {
                unlinked.add(name == null ? "?" : name);
                continue;
            }
            // Un site illisible n'empêche pas de lire les autres.
            try {
                readSite(site, slug, name, now);
                read++;
            } catch (RuntimeException e) {
                log.warn("Site de supervision {} illisible : {}", slug, e.getMessage());
                rejected.add(slug);
            }
        }
        var data = json.createObjectNode().put("sites", read);
        data.set("unlinked", json.valueToTree(unlinked));
        data.set("rejected", json.valueToTree(rejected));
        repository.collected(SUPERVISION, now, null, data);
    }

    private void readSite(JsonNode site, String slug, String name, Instant now) {
        if (!slug.matches("^[a-z0-9][a-z0-9-]{1,40}$")) {
            throw new IllegalArgumentException("slug invalide");
        }
        var siteId = Json.text(site, "/id");
        var id = siteId == null ? null : java.util.UUID.fromString(siteId);
        if (repository.ensure(slug, name == null ? slug : name, "CLIENT", now)) {
            audit.record(null, null, "CRECHE_ENREGISTREE", "creche:" + slug, Map.of("source", "supervision"));
        }
        if (name != null) {
            repository.nameFromSupervision(slug, name);
        }
        var creche = repository.find(slug).orElseThrow();
        var seenAt = Json.instant(site, "/lastSeenAt");
        var status = site.get("status");
        if (seenAt != null && status != null && !status.isNull() && !seenAt.equals(creche.supervisionSeenAt())) {
            repository.snapshot(creche.id(), seenAt, Snapshots.compact(json, status));
        }
        repository.supervision(slug, id, name, seenAt, status, site.path("suspended").asBoolean(false));
    }

    private void readAlerts(Instant now) {
        var cursor = repository.collector(ALERTES).cursorAt();
        var since = cursor == null ? Instant.EPOCH : cursor;
        var alerts = get(UriComponentsBuilder.fromUriString(base() + "/admin/v1/supervision/alerts")
                .queryParam("since", since.toString()).build().toUri());
        for (var alert : alerts) {
            var slug = Json.text(alert, "/slug");
            var openedAt = Json.instant(alert, "/openedAt");
            var creche = slug == null ? null : repository.find(slug).orElse(null);
            if (creche == null || openedAt == null || !alert.path("id").isNumber()) {
                continue;
            }
            repository.alert(alert.get("id").asLong(), creche.id(), alert.path("kind").asText(), alert.path("detail").asText(""),
                    openedAt, Json.instant(alert, "/closedAt"));
        }
        // Une minute de recouvrement : une alerte écrite pendant la lecture n'est pas perdue (l'écriture est idempotente).
        repository.collected(ALERTES, now, now.minus(Duration.ofMinutes(1)), null);
    }

    private JsonNode get(URI uri) {
        var body = http.get().uri(uri).header("Authorization", "Bearer " + properties.portalAdminToken())
                .retrieve().body(JsonNode.class);
        if (body == null || !body.isArray()) {
            throw new IllegalStateException("réponse inattendue");
        }
        return body;
    }

    private String base() {
        var url = properties.portalUrl();
        return url == null ? "" : url.replaceAll("/+$", "");
    }
}
