package nc.ticagou.console.parc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;

import nc.ticagou.console.audit.AuditService;
import nc.ticagou.console.auth.CurrentOperator;
import nc.ticagou.console.common.ApiException;
import nc.ticagou.console.parc.ParcRepository.Alert;
import nc.ticagou.console.parc.ParcRepository.Client;
import nc.ticagou.console.parc.ParcRepository.Creche;
import nc.ticagou.console.parc.ParcViews.AlertView;
import nc.ticagou.console.parc.ParcViews.Detail;
import nc.ticagou.console.parc.ParcViews.Parc;
import nc.ticagou.console.parc.ParcViews.Point;
import nc.ticagou.console.parc.ParcViews.Row;
import nc.ticagou.console.parc.ParcViews.SearchHit;
import nc.ticagou.console.parc.ParcViews.Sources;
import nc.ticagou.console.parc.ParcViews.Summary;

/** Vue du parc, fiche crèche, recherche ; fiche client, notes et prospects. */
@Service
public class ParcService {

    /** L'hôte est réputé envoyer ses rapports s'il l'a fait dans l'heure. */
    static final Duration HOST_SILENCE = Duration.ofHours(1);
    static final Duration HISTORY = Duration.ofDays(90);

    public record ClientForm(String name, String kind, String ridet, String address, String directorName, String directorEmail,
                             String directorPhone, String billingEmail, String reminderEmail, int paymentDays) {
    }

    public record FicheForm(String name, String city, String lifecycle, LocalDate clientSince, ClientForm client) {
    }

    private final ParcRepository repository;
    private final AuditService audit;
    private final nc.ticagou.console.billing.BillingStats billing;
    private final Clock clock;

    public ParcService(ParcRepository repository, AuditService audit, nc.ticagou.console.billing.BillingStats billing, Clock clock) {
        this.billing = billing;
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    // --- Lecture

    @Transactional(readOnly = true)
    public Parc parc() {
        var context = context();
        var rows = context.creches().stream().map(c -> row(c, context)).toList();
        return new Parc(rows, summary(rows, context.latest()), sources());
    }

    @Transactional(readOnly = true)
    public Detail detail(String slug) {
        var context = context();
        var creche = context.creches().stream().filter(c -> c.slug().equals(slug)).findFirst()
                .orElseThrow(() -> ApiException.notFound("creche_inconnue", "Crèche inconnue : " + slug));
        var row = row(creche, context);
        var now = clock.instant();
        var history = repository.alerts(creche.id(), now.minus(HISTORY)).stream().map(ParcService::view).toList();
        var points = repository.daily(creche.id(), now.minus(HISTORY)).stream().map(s -> new Point(
                LocalDate.ofInstant(s.at(), clock.getZone()), diskPercent(Json.number(s.data(), "/dataFree"), Json.number(s.data(), "/dataTotal")),
                Json.number(s.data(), "/dataFree"), Json.integer(s.data(), "/activeChildren"), Json.integer(s.data(), "/attendances7d"),
                Json.integer(s.data(), "/activeUsers30d"))).toList();
        return new Detail(row, Json.text(creche.host(), "/domain"), creche.clientSince(), creche.createdAt(), creche.supervisionName(),
                creche.hostSeenAt(), repository.client(creche.clientId()), creche.supervisionStatus(),
                creche.host() == null ? null : creche.host().get("subscription"), history, points,
                repository.notes(creche.id()), audit.forTarget(target(slug), 100));
    }

    @Transactional(readOnly = true)
    public List<SearchHit> search(String query) {
        var q = query == null ? "" : normalize(query);
        if (q.length() < 2) {
            return List.of();
        }
        var context = context();
        return context.creches().stream()
                .filter(c -> Stream.of(c.slug(), c.name(), c.city(), context.clientNames().get(c.clientId()))
                        .filter(Objects::nonNull).anyMatch(text -> normalize(text).contains(q)))
                .limit(8)
                .map(c -> new SearchHit(c.slug(), c.name(), c.city(), context.clientNames().get(c.clientId()),
                        row(c, context).health().level()))
                .toList();
    }

    // --- Écriture

    @Transactional
    public Detail createProspect(String slug, String name, String city) {
        var now = clock.instant();
        if (!repository.ensure(slug, name.strip(), "PROSPECT", now)) {
            throw ApiException.conflict("slug_pris", "Une crèche porte déjà l’identifiant « %s ».".formatted(slug));
        }
        var creche = repository.find(slug).orElseThrow();
        repository.updateCreche(creche.id(), name.strip(), blank(city), "PROSPECT", null, now);
        audit.record("PROSPECT_CREE", target(slug), Map.of("nom", name.strip()));
        return detail(slug);
    }

    @Transactional
    public Detail update(String slug, FicheForm form) {
        var creche = repository.find(slug).orElseThrow(() -> ApiException.notFound("creche_inconnue", "Crèche inconnue : " + slug));
        var before = repository.client(creche.clientId());
        var c = form.client();
        var after = new Client(before.id(), c.name().strip(), c.kind(), blank(c.ridet()), blank(c.address()), blank(c.directorName()),
                blank(c.directorEmail()), blank(c.directorPhone()), blank(c.billingEmail()), blank(c.reminderEmail()), c.paymentDays());
        var changes = new LinkedHashMap<String, Object>();
        diff(changes, "nom", creche.name(), form.name().strip());
        diff(changes, "commune", creche.city(), blank(form.city()));
        diff(changes, "cycle", creche.lifecycle(), form.lifecycle());
        diff(changes, "clientDepuis", creche.clientSince(), form.clientSince());
        diff(changes, "raisonSociale", before.name(), after.name());
        diff(changes, "type", before.kind(), after.kind());
        diff(changes, "ridet", before.ridet(), after.ridet());
        diff(changes, "adresse", before.address(), after.address());
        diff(changes, "direction", before.directorName(), after.directorName());
        diff(changes, "emailDirection", before.directorEmail(), after.directorEmail());
        diff(changes, "telephone", before.directorPhone(), after.directorPhone());
        diff(changes, "emailFacturation", before.billingEmail(), after.billingEmail());
        diff(changes, "emailRelance", before.reminderEmail(), after.reminderEmail());
        diff(changes, "delaiPaiement", before.paymentDays(), after.paymentDays());
        if (!changes.isEmpty()) {
            var now = clock.instant();
            repository.updateCreche(creche.id(), form.name().strip(), blank(form.city()), form.lifecycle(), form.clientSince(), now);
            repository.updateClient(after, now);
            audit.record("FICHE_MODIFIEE", target(slug), changes);
        }
        return detail(slug);
    }

    @Transactional
    public Detail note(String slug, String kind, String text, Integer minutes) {
        var creche = repository.find(slug).orElseThrow(() -> ApiException.notFound("creche_inconnue", "Crèche inconnue : " + slug));
        var operator = CurrentOperator.get();
        repository.note(creche.id(), clock.instant(), operator.login(), kind, text.strip(), minutes);
        audit.record("APPEL".equals(kind) ? "APPEL_NOTE" : "NOTE_AJOUTEE", target(slug), Map.of());
        return detail(slug);
    }

    // --- Calculs

    private record Context(List<Creche> creches, Map<UUID, List<Alert>> open, Map<UUID, String> clientNames,
                           Map<UUID, Instant> calls, String latest, boolean hostReporting,
                           Map<UUID, nc.ticagou.console.billing.BillingStats.Balance> balances) {
    }

    private Context context() {
        var creches = repository.all();
        var host = repository.collector(HostReports.HOTE);
        var hostReporting = host.lastSuccessAt() != null && host.lastSuccessAt().isAfter(clock.instant().minus(HOST_SILENCE));
        var latest = Versions.latest(creches.stream()
                .flatMap(c -> Stream.of(Json.text(c.supervisionStatus(), "/version"), Json.text(c.host(), "/image"))).toList());
        return new Context(creches, repository.openAlerts(), repository.clientNames(), repository.lastCalls(), latest, hostReporting,
                billing.balances());
    }

    private Row row(Creche c, Context context) {
        var status = c.supervisionStatus();
        var host = c.host();
        var subscription = host == null ? null : host.get("subscription");
        var alerts = context.open().getOrDefault(c.id(), List.of()).stream().map(ParcService::view).toList();
        var balance = context.balances().getOrDefault(c.id(), new nc.ticagou.console.billing.BillingStats.Balance(0, 0, null));
        var health = Health.evaluate(new Health.Input(c.lifecycle(), c.supervisionSeenAt(), c.supervisionSuspended(), status, host,
                alerts.stream().map(AlertView::label).toList(), context.latest(), context.hostReporting(), balance.daysLate()));
        var licenseState = Json.text(status, "/license/state");
        var version = Json.text(status, "/version");
        var places = Json.integer(subscription, "/places");
        var validUntil = Json.date(status, "/license/validUntil");
        return new Row(c.slug(), c.name(), c.city(), context.clientNames().get(c.clientId()), c.lifecycle(), health,
                c.supervisionSeenAt(), version != null ? version : Json.text(host, "/image"), plan(subscription, licenseState),
                modules(subscription), places != null ? places : Json.integer(status, "/usage/places"),
                Json.integer(status, "/usage/activeChildren"), licenseState,
                validUntil != null ? validUntil : Json.date(subscription, "/validUntil"), Json.date(status, "/license/deadline"),
                Json.integer(status, "/license/desktops"), Json.integer(status, "/license/desktopLimit"),
                Json.integer(status, "/tablets/paired"), Json.integer(subscription, "/tablets"),
                Json.instant(status, "/backup/lastSuccessAt"), status != null && status.at("/backup/stale").asBoolean(false),
                diskPercent(Json.number(status, "/disk/dataFreeBytes"), Json.number(status, "/disk/dataTotalBytes")),
                Json.text(host, "/container"), alerts, context.calls().get(c.id()), balance.due(), balance.daysLate());
    }

    private Summary summary(List<Row> rows, String latest) {
        var today = LocalDate.now(clock);
        int ok = 0, warn = 0, bad = 0, off = 0, evaluations = 0, renewals = 0, behind = 0, unpaid = 0;
        for (var row : rows) {
            switch (row.health().level()) {
                case OK -> ok++;
                case WARN -> warn++;
                case BAD -> bad++;
                case OFF -> off++;
            }
            if ("EVALUATION".equals(row.licenseState()) && row.deadline() != null && !row.deadline().isAfter(today.plusDays(7))) {
                evaluations++;
            }
            if ("CLIENT".equals(row.lifecycle()) && ("A_RENOUVELER".equals(row.licenseState()) || "EXPIREE_GRACE".equals(row.licenseState())
                    || (!"EVALUATION".equals(row.licenseState()) && row.validUntil() != null
                        && !row.validUntil().isBefore(today) && !row.validUntil().isAfter(today.plusDays(30))))) {
                renewals++;
            }
            if (row.daysLate() > 0) {
                unpaid++;
            }
            if (latest != null && Versions.readable(row.version()) && Versions.compare(row.version(), latest) < 0) {
                behind++;
            }
        }
        return new Summary(rows.size(), ok, warn, bad, off, evaluations, renewals, behind, latest, unpaid);
    }

    private Sources sources() {
        var supervision = repository.collector(SupervisionCollector.SUPERVISION);
        var host = repository.collector(HostReports.HOTE);
        return new Sources(supervision.lastSuccessAt(), supervision.lastAttemptAt(), supervision.lastError(), host.lastSuccessAt(),
                host.lastError(), texts(supervision.data(), "unlinked"), texts(supervision.data(), "rejected"),
                Json.number(host.data(), "/disk/freeBytes"), Json.number(host.data(), "/disk/totalBytes"));
    }

    private static List<String> texts(JsonNode data, String field) {
        var result = new ArrayList<String>();
        var list = data == null ? null : data.get(field);
        if (list != null && list.isArray()) {
            list.forEach(n -> result.add(n.asText()));
        }
        return result;
    }

    static AlertView view(Alert a) {
        return new AlertView(a.id(), a.kind(), label(a.kind()), a.detail(), a.openedAt(), a.closedAt());
    }

    /** Mêmes libellés que la supervision (AlertRules du portail). */
    static String label(String kind) {
        return switch (kind) {
            case "SILENCE" -> "Serveur muet";
            case "SAUVEGARDE" -> "Sauvegarde";
            case "DISQUE" -> "Espace disque";
            case "LICENCE" -> "Licence";
            case "TABLETTES" -> "Tablettes";
            case "PORTAIL" -> "Portail parents";
            default -> kind;
        };
    }

    /** « Gestion — jusqu'à 24 places » → « Gestion » ; en évaluation, sans contrat : « Évaluation ». */
    static String plan(JsonNode subscription, String licenseState) {
        var plan = Json.text(subscription, "/plan");
        if (plan != null) {
            return plan.split("\\s+[—–-]\\s+", 2)[0].strip();
        }
        return "EVALUATION".equals(licenseState) || "EVALUATION_TERMINEE".equals(licenseState) ? "Évaluation" : null;
    }

    static List<String> modules(JsonNode subscription) {
        var modules = Json.at(subscription, "/modules");
        if (modules == null || modules.isNull() || modules.isMissingNode()) {
            return List.of();
        }
        if (modules.isArray()) {
            var list = new ArrayList<String>();
            modules.forEach(m -> list.add(m.asText()));
            return list;
        }
        return Arrays.stream(modules.asText().split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    static Integer diskPercent(Long free, Long total) {
        if (free == null || total == null || total <= 0) {
            return null;
        }
        return (int) Math.round(100.0 * (total - free) / total);
    }

    static String target(String slug) {
        return "creche:" + slug;
    }

    private static void diff(Map<String, Object> changes, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            var change = new LinkedHashMap<String, Object>();
            change.put("avant", before);
            change.put("apres", after);
            changes.put(field, change);
        }
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String normalize(String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace('’', '\'').toLowerCase(Locale.ROOT).strip();
    }
}
