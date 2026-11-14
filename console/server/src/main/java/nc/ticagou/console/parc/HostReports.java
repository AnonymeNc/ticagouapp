package nc.ticagou.console.parc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import nc.ticagou.console.ConsoleProperties;
import nc.ticagou.console.audit.AuditService;
import nc.ticagou.console.common.ApiException;

/**
 * Rapport de l'hôte ({@code creche.sh rapport-console}, cron toutes les 5 minutes) : conteneur, image et abonnement de
 * chaque crèche, disque du serveur. Reçu sur le réseau interne avec le jeton {@code CONSOLE_HOST_TOKEN} ; Caddy ne publie
 * pas {@code /interne}.
 */
@Service
public class HostReports {

    static final String HOTE = "HOTE";
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{1,40}$");
    private static final List<String> SUBSCRIPTION = List.of("number", "plan", "places", "desktops", "tablets", "validUntil");

    private final ParcRepository repository;
    private final AuditService audit;
    private final ConsoleProperties properties;
    private final ObjectMapper json;
    private final Clock clock;

    public HostReports(ParcRepository repository, AuditService audit, ConsoleProperties properties, ObjectMapper json, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    public void receive(String authorization, JsonNode report) {
        var expected = properties.hostToken();
        if (expected == null || expected.isBlank() || authorization == null
                || !MessageDigest.isEqual(authorization.getBytes(StandardCharsets.UTF_8),
                        ("Bearer " + expected).getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.unauthorized("host_token", "Jeton de l’hôte requis.");
        }
        if (report == null || !report.path("creches").isArray()) {
            throw ApiException.rule("host_report", "Rapport illisible : la liste « creches » manque.");
        }
        var now = clock.instant();
        var reported = new ArrayList<String>();
        for (var entry : report.get("creches")) {
            var slug = entry.path("slug").asText("");
            if (!SLUG.matcher(slug).matches()) {
                continue;
            }
            if (repository.ensure(slug, slug, "CLIENT", now)) {
                audit.record(null, null, "CRECHE_ENREGISTREE", "creche:" + slug, Map.of("source", "hote"));
            }
            repository.host(slug, now, keep(entry));
            reported.add(slug);
        }
        repository.hostAbsent(reported);
        var data = json.createObjectNode();
        data.put("creches", reported.size());
        var images = report.path("images");
        if (images.isArray()) {
            var list = data.putArray("images");
            images.forEach(i -> {
                if (i.isTextual() && i.asText().matches("[0-9A-Za-z][0-9A-Za-z._-]{0,29}")) {
                    list.add(i.asText());
                }
            });
        }
        var disk = report.path("disk");
        if (disk.path("freeBytes").isNumber() && disk.path("totalBytes").isNumber()) {
            data.putObject("disk").put("freeBytes", disk.get("freeBytes").asLong()).put("totalBytes", disk.get("totalBytes").asLong());
        }
        repository.collected(HOTE, now, null, data);
    }

    /** Champs connus seulement : l'hôte ne transmet ni secret ni donnée de crèche. */
    private ObjectNode keep(JsonNode entry) {
        var kept = json.createObjectNode();
        for (var field : List.of("container", "image", "domain")) {
            var value = entry.path(field);
            if (value.isTextual()) {
                kept.put(field, value.asText().length() > 200 ? value.asText().substring(0, 200) : value.asText());
            }
        }
        // Rubriques masquées à la demande de la crèche (ADR 0022) : des noms en majuscules, rien d'autre.
        var hidden = entry.path("hiddenSections");
        if (hidden.isTextual() && hidden.asText().matches("[A-Z_,]{0,200}")) {
            kept.put("hiddenSections", hidden.asText());
        }
        // Code de l’annuaire des applications (ADR 0025) : six caractères, rien d’autre.
        var code = entry.path("directoryCode");
        if (code.isTextual() && code.asText().matches("[A-Z0-9]{6}")) {
            kept.put("directoryCode", code.asText());
        }
        var subscription = entry.path("subscription");
        if (subscription.isObject()) {
            var s = kept.putObject("subscription");
            SUBSCRIPTION.forEach(field -> {
                if (subscription.has(field) && !subscription.get(field).isNull()) {
                    s.set(field, subscription.get(field));
                }
            });
        }
        return kept;
    }

    @RestController
    @RequestMapping("/interne/v1")
    static class HostController {

        private final HostReports reports;

        HostController(HostReports reports) {
            this.reports = reports;
        }

        @PostMapping("/hote")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void report(@RequestHeader(value = "Authorization", required = false) String authorization,
                    @RequestBody(required = false) JsonNode report) {
            reports.receive(authorization, report);
        }
    }
}
