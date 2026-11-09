package nc.ticagou.console.orders;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import nc.ticagou.console.audit.AuditService;
import nc.ticagou.console.auth.CurrentOperator;
import nc.ticagou.console.billing.ContractService;
import nc.ticagou.console.common.ApiException;
import nc.ticagou.console.orders.OrderRepository.Order;
import nc.ticagou.console.parc.ParcRepository;

/**
 * Ordres aux instances (ADR 0017, phase 5) : l'éditeur les donne (code redemandé) ou la console les propose (abonnement
 * payé) et il les confirme ; `creche.sh appliquer` les prend une seule fois, les exécute et rend compte. Créer une crèche
 * et lancer une démonstration sont des ordres comme les autres. Supprimer une crèche passe par un export complet
 * (EXPORTER, suite « supprimer ») : l'ordre SUPPRIMER n'est créé qu'une fois l'export lu en entier et reçu par le
 * navigateur de l'éditeur, et il nomme cet export, que l'hôte revérifie.
 */
@Service
public class OrderService {

    /** Ordre en cours depuis plus longtemps : l'hôte s'est sans doute arrêté pendant l'exécution. */
    static final Duration STALE = Duration.ofMinutes(30);
    private static final Pattern SETUP_LINK = Pattern.compile("https?://\\S+/setup\\?jeton=[A-Za-z0-9]+");
    private static final Pattern DEMO_URL = Pattern.compile("Démonstration prête : (https?://\\S+)");
    private static final Pattern EXPORT_READY = Pattern.compile("Export prêt : (\\S+\\.tar\\.gz\\.enc) \\((\\d+) octets\\)");
    private static final DateTimeFormatter DEMO_SLUG = DateTimeFormatter.ofPattern("'demo-'yyyyMMdd-HHmm");

    public record View(UUID id, String type, String slug, String crecheName, Map<String, String> params, String status, String reason,
                       String proposedBy, String confirmedBy, java.time.Instant createdAt, java.time.Instant confirmedAt,
                       java.time.Instant startedAt, java.time.Instant finishedAt, String output, JsonNode result, boolean stale) {
    }

    public record Misaligned(String slug, String name, List<String> differences) {
    }

    private final OrderRepository repository;
    private final ParcRepository parc;
    private final ContractService contracts;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    public OrderService(OrderRepository repository, ParcRepository parc, ContractService contracts, AuditService audit, ObjectMapper json,
                        Clock clock) {
        this.repository = repository;
        this.parc = parc;
        this.contracts = contracts;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    // --- Lecture

    @Transactional(readOnly = true)
    public List<View> latest() {
        var names = new LinkedHashMap<String, String>();
        parc.all().forEach(c -> names.put(c.slug(), c.name()));
        var now = clock.instant();
        return repository.latest(200).stream().map(o -> view(o, names.get(o.slug()), now)).toList();
    }

    @Transactional(readOnly = true)
    public List<View> forSlug(String slug) {
        var name = parc.find(slug).map(ParcRepository.Creche::name).orElse(null);
        var now = clock.instant();
        return repository.forSlug(slug).stream().map(o -> view(o, name, now)).toList();
    }

    private static View view(Order o, String name, java.time.Instant now) {
        boolean stale = "EN_COURS".equals(o.status()) && o.startedAt() != null && o.startedAt().plus(STALE).isBefore(now);
        return new View(o.id(), o.type(), o.slug(), name, o.params(), o.status(), o.reason(), o.proposedBy(), o.confirmedBy(), o.createdAt(),
                o.confirmedAt(), o.startedAt(), o.finishedAt(), o.output(), o.result(), stale);
    }

    /** Crèches dont l'abonnement lu dans l'instance diffère du contrat (alerte « abonnement non aligné »). */
    @Transactional(readOnly = true)
    public List<Misaligned> misaligned() {
        var result = new ArrayList<Misaligned>();
        for (var creche : parc.all()) {
            if (!"CLIENT".equals(creche.lifecycle()) || creche.host() == null) {
                continue;
            }
            var view = contracts.view(creche.slug());
            if (view.current() != null && view.expected() != null && view.expected().validUntil() != null
                    && !view.expected().differences().isEmpty()) {
                result.add(new Misaligned(creche.slug(), creche.name(), view.expected().differences()));
            }
        }
        return result;
    }

    // --- Ordres de l'éditeur

    /** Ordre donné par l'éditeur (le contrôleur a exigé le code) : confirmé d'emblée, l'hôte le prendra à la minute. */
    @Transactional
    public View give(String type, String slug, Map<String, String> params, String reason) {
        var now = clock.instant();
        if ("DEMO".equals(type) && (slug == null || slug.isBlank())) {
            slug = DEMO_SLUG.format(LocalDateTime.ofInstant(now, clock.getZone()));
        }
        Map<String, String> checked;
        try {
            checked = OrderRules.check(type, slug, params);
        } catch (OrderRules.Invalid e) {
            throw ApiException.rule("ordre_invalide", e.getMessage());
        }
        var creche = parc.find(slug).orElse(null);
        switch (type) {
            case "CREER" -> {
                if (creche != null && creche.host() != null && !"absent".equals(creche.host().path("container").asText())) {
                    throw ApiException.rule("deja_creee", "Cette crèche a déjà une instance sur le serveur.");
                }
                parc.ensure(slug, params != null && params.get("nom") != null ? params.get("nom").strip() : slug, "PROSPECT", now);
            }
            case "DEMO" -> {
                if (creche != null) {
                    throw ApiException.conflict("slug_pris", "Une crèche porte déjà l’identifiant « %s ».".formatted(slug));
                }
                parc.ensure(slug, "Démonstration " + checked.get("formule"), "DEMO", now);
            }
            case "SUPPRIMER_DEMO" -> {
                if (creche == null || !"DEMO".equals(creche.lifecycle())) {
                    throw ApiException.rule("pas_une_demo", "Ce n’est pas une démonstration : utilisez « Supprimer la crèche… », qui exporte "
                            + "d’abord ses données.");
                }
            }
            case "EXPORTER" -> {
                if (creche == null) {
                    throw ApiException.notFound("creche_inconnue", "Crèche inconnue : " + slug);
                }
                if (creche.host() == null || "absent".equals(creche.host().path("container").asText())) {
                    throw ApiException.rule("pas_d_instance", "Cette crèche n’a pas d’instance sur le serveur : rien à exporter.");
                }
                if (checked.containsKey("suite")) {
                    if ("DEMO".equals(creche.lifecycle())) {
                        throw ApiException.rule("demo", "Une démonstration se supprime directement, sans export.");
                    }
                    // Seconde mise en garde : l'identifiant retapé, revérifié ici.
                    if (params == null || !slug.equals(params.get("confirmation"))) {
                        throw ApiException.rule("confirmation_identifiant", "Retapez exactement l’identifiant de la crèche : « %s ».".formatted(slug));
                    }
                }
            }
            case "SUPPRIMER" -> throw ApiException.rule("export_d_abord",
                    "Une crèche se supprime après le téléchargement de son export : « Supprimer la crèche… » depuis sa fiche.");
            default -> {
                if (creche == null) {
                    throw ApiException.notFound("creche_inconnue", "Crèche inconnue : " + slug);
                }
            }
        }
        var id = UUID.randomUUID();
        var operator = CurrentOperator.get().login();
        try {
            repository.insert(id, type, slug, checked, "EN_ATTENTE", blank(reason), operator, operator, now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("ordre_ouvert", "Un ordre de ce type attend déjà pour cette crèche : confirmez-le ou annulez-le.");
        }
        audit.record("ORDRE_DONNE", "creche:" + slug, Map.of("ordre", type, "parametres", checked));
        return view(repository.lock(id).orElseThrow(), parc.find(slug).map(ParcRepository.Creche::name).orElse(null), now);
    }

    /**
     * Ordre « abonnement » tiré du contrat : formule, modules ajoutés, places, PC, tablettes et échéance payée. Sans
     * période d'abonnement validée, il n'y a rien à reporter dans l'instance.
     */
    public Map<String, String> subscriptionParams(String slug) {
        var view = contracts.view(slug);
        if (view.current() == null || view.expected() == null) {
            throw ApiException.rule("pas_de_contrat", "Pas de contrat : saisissez-le dans l’onglet Abonnement.");
        }
        if (view.expected().validUntil() == null) {
            throw ApiException.rule("rien_de_paye", "Aucune période d’abonnement validée : rien à reporter dans l’instance.");
        }
        var c = view.current();
        var params = new LinkedHashMap<String, String>();
        params.put("numero", "C-" + c.id().toString().substring(0, 8).toUpperCase());
        params.put("offre", c.plan().toLowerCase());
        params.put("places", Integer.toString(c.places()));
        params.put("pc", Integer.toString(view.expected().desktops()));
        params.put("tablettes", Integer.toString(view.expected().tablets()));
        params.put("jusqu_au", view.expected().validUntil().toString());
        params.put("modules", String.join(",", c.modules()));
        return params;
    }

    /** Proposition de la console (paiement reçu…) : elle attend la confirmation de l'éditeur, avec son code. */
    @Transactional
    public void propose(String type, String slug, Map<String, String> params, String reason) {
        Map<String, String> checked;
        try {
            checked = OrderRules.check(type, slug, params);
        } catch (OrderRules.Invalid e) {
            return;
        }
        var open = repository.open(slug, type);
        if (open.isPresent()) {
            return;
        }
        try {
            repository.insert(UUID.randomUUID(), type, slug, checked, "A_CONFIRMER", reason, "console", null, clock.instant());
        } catch (DuplicateKeyException e) {
            return;
        }
        audit.record(null, null, "ORDRE_PROPOSE", "creche:" + slug, Map.of("ordre", type, "motif", reason == null ? "" : reason));
    }

    /**
     * Facture d'abonnement soldée (D6) : l'ordre qui prolonge l'abonnement de l'instance est proposé, à confirmer. Rien
     * si l'instance porte déjà ce qui est payé.
     */
    @Transactional
    public void proposeAfterPayment(String slug, String invoiceNumber) {
        try {
            var view = contracts.view(slug);
            if (view.expected() == null || view.expected().differences().isEmpty()) {
                return;
            }
            propose("ABONNEMENT", slug, subscriptionParams(slug), "Après paiement de " + invoiceNumber);
        } catch (ApiException e) {
            // Pas de contrat ou rien de payé : rien à proposer.
        }
    }

    @Transactional
    public View confirm(UUID id) {
        var order = repository.lock(id).orElseThrow(OrderService::unknown);
        if (repository.confirm(id, CurrentOperator.get().login(), clock.instant()) == 0) {
            throw ApiException.rule("ordre_traite", "Cet ordre n’est plus à confirmer.");
        }
        audit.record("ORDRE_CONFIRME", "creche:" + order.slug(), Map.of("ordre", order.type()));
        return view(repository.lock(id).orElseThrow(), null, clock.instant());
    }

    @Transactional
    public View cancel(UUID id) {
        var order = repository.lock(id).orElseThrow(OrderService::unknown);
        var now = clock.instant();
        boolean running = "EN_COURS".equals(order.status());
        if (running && (order.startedAt() == null || !order.startedAt().plus(STALE).isBefore(now))) {
            throw ApiException.rule("ordre_en_cours", "L’hôte exécute cet ordre : attendez son compte rendu.");
        }
        if (repository.cancel(id, CurrentOperator.get().login(), now) == 0) {
            throw ApiException.rule("ordre_traite", "Cet ordre est déjà terminé.");
        }
        if ("DEMO".equals(order.type()) || "CREER".equals(order.type())) {
            parc.find(order.slug()).filter(c -> c.host() == null && "DEMO".equals(c.lifecycle())).ifPresent(c -> parc.deleteDemo(c.slug()));
        }
        audit.record("ORDRE_ANNULE", "creche:" + order.slug(), Map.of("ordre", order.type()));
        return view(repository.lock(id).orElseThrow(), null, now);
    }

    // --- Hôte (creche.sh appliquer)

    /** Ordres confirmés, une ligne chacun, pour le script. */
    @Transactional(readOnly = true)
    public String pendingLines() {
        var lines = new StringBuilder();
        for (var o : repository.waiting()) {
            // Revalidés à la sortie (et remis dans un ordre stable : JSONB ne garde pas celui des clés).
            Map<String, String> params;
            try {
                params = OrderRules.check(o.type(), o.slug(), o.params());
            } catch (OrderRules.Invalid e) {
                continue;
            }
            lines.append(OrderRules.line(o.id().toString(), o.type(), o.slug(), params)).append('\n');
        }
        return lines.toString();
    }

    @Transactional
    public boolean claim(UUID id) {
        return repository.claim(id, clock.instant()) == 1;
    }

    /** Compte rendu : sortie de la commande ; pour une création, le lien de démarrage à remettre à la directrice. */
    @Transactional
    public boolean finish(UUID id, boolean ok, String output) {
        var order = repository.lock(id).orElse(null);
        if (order == null || !"EN_COURS".equals(order.status())) {
            return false;
        }
        var text = output == null ? "" : output.length() > 20_000 ? output.substring(output.length() - 20_000) : output;
        JsonNode result = null;
        if ("DEMO".equals(order.type())) {
            // Le lien de démarrage d'une démonstration a déjà servi au chargement : on garde son adresse.
            var demo = DEMO_URL.matcher(text);
            if (demo.find()) {
                result = json.createObjectNode().put("url", demo.group(1));
            }
        } else if ("EXPORTER".equals(order.type())) {
            var export = EXPORT_READY.matcher(text);
            if (export.find()) {
                var file = OrderRules.EXPORT_FILE.matcher(export.group(1));
                if (file.matches() && file.group(1).equals(order.slug())) {
                    result = json.createObjectNode().put("exportFile", export.group(1)).put("size", Long.parseLong(export.group(2)));
                }
            }
            if (result == null && ok) {
                ok = false;
                text = text + "\nExport introuvable dans le compte rendu.";
            }
        } else {
            var link = SETUP_LINK.matcher(text);
            if (link.find()) {
                result = json.createObjectNode().put("setupLink", link.group());
            }
        }
        var now = clock.instant();
        repository.finish(id, ok, text, result, now);
        if (ok) {
            switch (order.type()) {
                case "CREER" -> parc.find(order.slug()).filter(c -> "PROSPECT".equals(c.lifecycle()))
                        .ifPresent(c -> parc.lifecycle(c.slug(), "CLIENT", now));
                case "SUPPRIMER_DEMO" -> parc.deleteDemo(order.slug());
                // La fiche, le contrat et les factures de l'éditeur restent : la crèche passe en résiliée.
                case "SUPPRIMER" -> parc.lifecycle(order.slug(), "RESILIE", now);
                default -> {
                }
            }
        }
        audit.record(null, null, ok ? "ORDRE_FAIT" : "ORDRE_ECHEC", "creche:" + order.slug(), Map.of("ordre", order.type()));
        return true;
    }

    // --- Export d'une crèche, puis suppression

    /** Nom du fichier d'un export prêt (ordre EXPORTER fait). */
    @Transactional
    public String exportFile(UUID id) {
        var order = repository.lock(id).orElseThrow(OrderService::unknown);
        if (!"EXPORTER".equals(order.type()) || !"FAIT".equals(order.status()) || order.result() == null
                || !order.result().hasNonNull("exportFile")) {
            throw ApiException.rule("pas_d_export", "Cet ordre n’a pas produit d’export.");
        }
        return order.result().get("exportFile").asText();
    }

    /** L'export a été lu en entier par le navigateur : condition de la suppression qui suit. */
    @Transactional
    public void exportStreamed(UUID id) {
        var order = repository.lock(id).orElseThrow(OrderService::unknown);
        var result = ((com.fasterxml.jackson.databind.node.ObjectNode) order.result()).put("streamedAt", clock.instant().toString());
        repository.result(id, result);
        audit.record("EXPORT_TELECHARGE", "creche:" + order.slug(), Map.of("fichier", order.result().path("exportFile").asText()));
    }

    /**
     * Le navigateur a reçu l'export et l'a enregistré : pour un export « puis supprimer », l'ordre SUPPRIMER est créé,
     * confirmé (le code a été donné pour l'export), avec le nom de l'export que l'hôte revérifie. Une seule fois.
     */
    @Transactional
    public View exportReceived(UUID id) {
        var order = repository.lock(id).orElseThrow(OrderService::unknown);
        var file = exportFile(id);
        var result = (com.fasterxml.jackson.databind.node.ObjectNode) order.result();
        if (!result.hasNonNull("streamedAt")) {
            throw ApiException.rule("export_non_lu", "L’export n’a pas été téléchargé en entier : relancez le téléchargement.");
        }
        var now = clock.instant();
        var operator = CurrentOperator.get().login();
        if (!result.hasNonNull("receivedAt")) {
            result.put("receivedAt", now.toString()).put("receivedBy", operator);
        }
        if ("supprimer".equals(order.params().get("suite")) && !result.hasNonNull("deletionOrder")) {
            var deletion = UUID.randomUUID();
            var params = OrderRules.check("SUPPRIMER", order.slug(), Map.of("export", file));
            try {
                repository.insert(deletion, "SUPPRIMER", order.slug(), params, "EN_ATTENTE", "Après téléchargement de " + file, order.confirmedBy(),
                        operator, now);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("ordre_ouvert", "Une suppression attend déjà pour cette crèche.");
            }
            result.put("deletionOrder", deletion.toString());
            audit.record("ORDRE_DONNE", "creche:" + order.slug(), Map.of("ordre", "SUPPRIMER", "parametres", params));
        }
        repository.result(id, result);
        return view(repository.lock(id).orElseThrow(), parc.find(order.slug()).map(ParcRepository.Creche::name).orElse(null), now);
    }

    static ApiException unknown() {
        return ApiException.notFound("ordre_inconnu", "Ordre introuvable.");
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
