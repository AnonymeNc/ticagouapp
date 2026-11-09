package nc.ticagou.console.billing;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import nc.ticagou.console.ConsoleProperties;
import nc.ticagou.console.audit.AuditService;
import nc.ticagou.console.billing.InvoiceService.Summary;
import nc.ticagou.console.billing.ReminderPlanner.Candidate;
import nc.ticagou.console.billing.ReminderPlanner.Rule;
import nc.ticagou.console.billing.ReminderRepository.ClientRule;
import nc.ticagou.console.common.ApiException;
import nc.ticagou.console.parc.ParcRepository;

/**
 * Relances (plan console, phase 4) : chaque jour ouvré à 6 h, les relances dues sont <em>préparées</em> dans la boîte
 * d'envoi, un message par client même s'il a plusieurs factures en retard ; rien ne part sans clic. Une relance par
 * niveau et par facture (contrainte d'unicité) ; jamais de suspension automatique.
 */
@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    public record Settings(List<ReminderRepository.RuleSet> sets, Map<UUID, List<Rule>> rules, List<ReminderRepository.Holiday> holidays,
                           List<ClientRule> clients, List<String> variables) {
    }

    private final ReminderRepository repository;
    private final InvoiceService invoices;
    private final OutboxRepository outbox;
    private final CatalogueRepository catalogue;
    private final ParcRepository parc;
    private final AuditService audit;
    private final ConsoleProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ReminderService(ReminderRepository repository, InvoiceService invoices, OutboxRepository outbox, CatalogueRepository catalogue,
                           ParcRepository parc, AuditService audit, ConsoleProperties properties, TransactionTemplate transactions,
                           Clock clock) {
        this.repository = repository;
        this.invoices = invoices;
        this.outbox = outbox;
        this.catalogue = catalogue;
        this.parc = parc;
        this.audit = audit;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    // --- Préparation

    @Scheduled(cron = "0 0 6 * * *", zone = "Pacific/Noumea")
    public void scheduled() {
        if (!properties.scheduling()) {
            return;
        }
        var today = LocalDate.now(clock);
        if (!ReminderPlanner.workingDay(today, repository.holidayDays())) {
            return;
        }
        int prepared = prepare(today);
        if (prepared > 0) {
            log.info("Relances : {} message(s) préparé(s)", prepared);
        }
    }

    /** Prépare les relances dues ce jour ; rend le nombre de messages préparés (un par client). */
    public int prepare(LocalDate today) {
        var rules = repository.rulesAt(today);
        if (rules.isEmpty()) {
            return 0;
        }
        var holidays = repository.holidayDays();
        var done = repository.doneLevels();
        var clients = repository.clientRules().stream().collect(Collectors.toMap(ClientRule::clientId, c -> c));
        var due = new LinkedHashMap<UUID, List<Map.Entry<Summary, Rule>>>();
        for (var s : invoices.list()) {
            if (!"FACTURE".equals(s.type()) || !"VALIDEE".equals(s.status()) || s.remaining() <= 0) {
                continue;
            }
            var client = clients.get(s.clientId());
            var candidate = new Candidate(s.dueOn(), s.remaining(), s.remindersPaused(), client != null && client.paused(),
                    client == null ? 0 : client.shiftDays(), done.getOrDefault(s.id(), Set.of()));
            ReminderPlanner.due(candidate, rules, today, holidays)
                    .ifPresent(rule -> due.computeIfAbsent(s.clientId(), k -> new ArrayList<>()).add(Map.entry(s, rule)));
        }
        int count = 0;
        for (var entry : due.entrySet()) {
            var group = entry.getValue();
            var created = transactions.execute(t -> prepareFor(entry.getKey(), group, rules, today));
            if (Boolean.TRUE.equals(created)) {
                count++;
            }
        }
        return count;
    }

    /** Un message pour le client : le niveau le plus élevé du groupe donne le ton, chaque facture garde son niveau. */
    private boolean prepareFor(UUID clientId, List<Map.Entry<Summary, Rule>> group, List<Rule> rules, LocalDate today) {
        // Relu dans la transaction : une préparation concurrente ne crée pas de doublon (et la contrainte d'unicité veille).
        var done = repository.doneLevels();
        var items = group.stream().filter(e -> !done.getOrDefault(e.getKey().id(), Set.of()).contains(e.getValue().level()))
                .sorted(Comparator.comparing((Map.Entry<Summary, Rule> e) -> e.getKey().dueOn())).toList();
        if (items.isEmpty()) {
            return false;
        }
        var rule = items.stream().map(Map.Entry::getValue).max(Comparator.comparingInt(Rule::level)).orElseThrow();
        var client = parc.client(clientId);
        var issuer = catalogue.issuer();
        var first = items.getFirst().getKey();
        var values = values(items.stream().map(Map.Entry::getKey).toList(), client, issuer, today);
        var to = firstNonBlank(client.reminderEmail(), client.billingEmail(), client.directorEmail());
        var cc = rule.copyToIssuer() ? blankToNull(issuer.copyEmail()) : null;
        var now = clock.instant();
        var messageId = UUID.randomUUID();
        var crecheId = first.crecheSlug() == null ? null : parc.find(first.crecheSlug()).map(ParcRepository.Creche::id).orElse(null);
        outbox.insert(new OutboxRepository.Message(messageId, "RELANCE", first.id(), clientId, crecheId, to, cc,
                ReminderPlanner.render(rule.subject(), values), ReminderPlanner.render(rule.body(), values), "PREPARE", now, now, null, null, null,
                null, null, null, rule.level(), rule.attachPdf()));
        for (var item : items) {
            var s = item.getKey();
            repository.insert(s.id(), item.getValue().level(), messageId, today, (int) Math.max(0, ChronoUnit.DAYS.between(s.dueOn(), today)), now);
        }
        audit.record(null, null, "RELANCE_PREPAREE", first.crecheSlug() == null ? "client:" + clientId : "creche:" + first.crecheSlug(),
                Map.of("niveau", rule.label(), "factures", items.stream().map(e -> e.getKey().number()).toList()));
        return true;
    }

    /** Variables des modèles : {bonjour}, {client}, {creche}, {numero}, {factures}, {total}, {retard}, {echeance}, {paiement}, {editeur}. */
    static Map<String, String> values(List<Summary> items, ParcRepository.Client client, CatalogueRepository.Issuer issuer, LocalDate today) {
        var values = new LinkedHashMap<String, String>();
        values.put("bonjour", client.directorName() == null || client.directorName().isBlank() ? "Bonjour," : "Bonjour " + client.directorName() + ",");
        values.put("client", client.name());
        values.put("creche", String.join(", ", items.stream().map(Summary::crecheName).filter(n -> n != null).distinct().toList()));
        values.put("numero", String.join(", ", items.stream().map(Summary::number).toList()));
        values.put("factures", String.join("\n", items.stream().map(s -> "- facture %s du %s, échue le %s : %s restants".formatted(s.number(),
                Dates.format(s.issuedOn()), Dates.format(s.dueOn()), Xpf.format(s.remaining()))).toList()));
        values.put("total", Xpf.format(items.stream().mapToLong(Summary::remaining).sum()));
        values.put("retard", Long.toString(items.stream().mapToLong(s -> Math.max(0, ChronoUnit.DAYS.between(s.dueOn(), today))).max().orElse(0)));
        values.put("echeance", Dates.format(items.stream().map(Summary::dueOn).min(LocalDate::compareTo).orElse(null)));
        var bank = issuer.iban() == null || issuer.iban().isBlank() ? "virement"
                : "%sIBAN %s, référence %s".formatted(issuer.bank() == null ? "" : issuer.bank() + ", ", issuer.iban(), values.get("numero"));
        values.put("paiement", bank);
        values.put("editeur", issuer.name() == null ? "Ti'Cagou" : issuer.name());
        return values;
    }

    // --- Paramètres

    @Transactional(readOnly = true)
    public Settings settings() {
        var sets = repository.sets();
        var rules = new LinkedHashMap<UUID, List<Rule>>();
        sets.forEach(s -> rules.put(s.id(), repository.rules(s.id())));
        return new Settings(sets, rules, repository.holidays(), repository.clientRules(), ReminderPlanner.VARIABLES);
    }

    @Transactional
    public Settings newVersion(String label, LocalDate validFrom, List<Rule> rules) {
        if (validFrom.isBefore(LocalDate.now(clock))) {
            throw ApiException.rule("relances_date", "Une nouvelle version commence aujourd’hui ou plus tard.");
        }
        if (repository.setStartsOnOrAfter(validFrom)) {
            throw ApiException.rule("relances_date", "Une version commence déjà à cette date ou après : choisissez une date plus tardive.");
        }
        var levels = rules.stream().map(Rule::level).collect(Collectors.toSet());
        if (levels.size() != rules.size() || !levels.equals(Set.of(0, 1, 2, 3))) {
            throw ApiException.rule("relances_niveaux", "Il faut les quatre niveaux, de 0 à 3, une fois chacun.");
        }
        var ordered = rules.stream().sorted(Comparator.comparingInt(Rule::level)).toList();
        for (int i = 1; i < ordered.size(); i++) {
            if (ordered.get(i).days() <= ordered.get(i - 1).days()) {
                throw ApiException.rule("relances_delais", "Chaque niveau vient après le précédent : délais croissants.");
            }
        }
        var id = UUID.randomUUID();
        repository.insertSet(id, label.strip(), validFrom, ordered, clock.instant());
        audit.record("RELANCES_VERSION", "relances:" + id, Map.of("libelle", label.strip(), "debut", validFrom.toString()));
        return settings();
    }

    @Transactional
    public Settings clientRule(UUID clientId, int shiftDays, boolean paused, String note) {
        if (paused && (note == null || note.isBlank())) {
            throw ApiException.rule("motif", "Indiquez pourquoi les relances de ce client sont suspendues.");
        }
        if (repository.updateClientRule(clientId, shiftDays, paused, blankToNull(note)) == 0) {
            throw ApiException.notFound("client_inconnu", "Client introuvable.");
        }
        audit.record(paused ? "RELANCES_CLIENT_SUSPENDUES" : "RELANCES_CLIENT", "client:" + clientId,
                Map.of("decalage", shiftDays, "suspendues", paused, "motif", note == null ? "" : note));
        return settings();
    }

    @Transactional
    public Settings addHoliday(LocalDate day, String label) {
        repository.addHoliday(day, label.strip());
        audit.record("FERIE_AJOUTE", "ferie:" + day, Map.of("libelle", label.strip()));
        return settings();
    }

    @Transactional
    public Settings removeHoliday(LocalDate day) {
        if (repository.removeHoliday(day) > 0) {
            audit.record("FERIE_RETIRE", "ferie:" + day, Map.of());
        }
        return settings();
    }

    /** « Préparer maintenant » : même calcul que la tâche de 6 h, un jour ouvré ou non. */
    public int prepareNow() {
        int count = prepare(LocalDate.now(clock));
        audit.record("RELANCES_PREPAREES", "relances", Map.of("messages", count));
        return count;
    }

    private static String firstNonBlank(String... values) {
        for (var v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
