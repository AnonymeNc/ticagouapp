package nc.ticagou.console.billing;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;

import nc.ticagou.console.ConsoleProperties;
import nc.ticagou.console.audit.AuditService;
import nc.ticagou.console.billing.ContractRepository.Contract;
import nc.ticagou.console.billing.InvoiceRepository.Invoice;
import nc.ticagou.console.billing.InvoiceRepository.Line;
import nc.ticagou.console.common.ApiException;
import nc.ticagou.console.parc.ParcRepository;

/**
 * Contrats d'abonnement : signature au tarif du barème du jour, avenant (nouvelle version et complément au prorata),
 * résiliation, abonnement attendu de l'instance, et brouillons de renouvellement préparés avant chaque période (45 jours
 * pour l'annuel, 10 pour le mensuel), à valider par l'éditeur.
 */
@Service
public class ContractService {

    private static final Logger log = LoggerFactory.getLogger(ContractService.class);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    public record Input(String plan, int places, Set<String> modules, int extraDesktops, int extraTablets, boolean premiumSupport,
                        int engagementYears, String rhythm, boolean extraStructure, int freeDiscount, String freeDiscountReason,
                        LocalDate startsOn, LocalDate signedOn, LocalDate changeOn) {

        Pricing.Terms terms() {
            return new Pricing.Terms(plan, places, modules, extraDesktops, extraTablets, premiumSupport, engagementYears, extraStructure,
                    freeDiscount, freeDiscountReason);
        }
    }

    /** Abonnement que l'instance devrait porter, et ses écarts avec ce que l'hôte lit dans sa base. */
    public record Expected(String plan, Set<String> modules, int places, int desktops, int tablets, LocalDate validUntil,
                           List<String> differences) {
    }

    public record NextPeriod(LocalDate from, LocalDate to, LocalDate preparedFrom, boolean due) {
    }

    public record View(Contract current, List<Contract> history, String priceListLabel, Pricing.Quote monthly, Pricing.Quote period,
                       Expected expected, NextPeriod next) {
    }

    /** Résultat d'un enregistrement : brouillon de complément créé, ou montant à rendre par avoir. */
    public record Saved(View contract, UUID complementInvoiceId, long toCredit, String toCreditInvoiceNumber, List<Pricing.Line> amendment) {
    }

    private final ContractRepository contracts;
    private final CatalogueRepository catalogue;
    private final InvoiceRepository invoices;
    private final InvoiceService invoiceService;
    private final ParcRepository parc;
    private final AuditService audit;
    private final ConsoleProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ContractService(ContractRepository contracts, CatalogueRepository catalogue, InvoiceRepository invoices,
                           InvoiceService invoiceService, ParcRepository parc, AuditService audit, ConsoleProperties properties,
                           TransactionTemplate transactions, Clock clock) {
        this.contracts = contracts;
        this.catalogue = catalogue;
        this.invoices = invoices;
        this.invoiceService = invoiceService;
        this.parc = parc;
        this.audit = audit;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    // --- Lecture

    @Transactional(readOnly = true)
    public View view(String slug) {
        var creche = creche(slug);
        var today = LocalDate.now(clock);
        var current = contracts.current(creche.id(), today).orElse(null);
        if (current == null) {
            return new View(null, List.of(), null, null, null, null, null);
        }
        var cat = catalogue.catalogue(current.priceListId());
        var months = Pricing.months(current.rhythm());
        return new View(current, contracts.forCreche(creche.id()), cat.list().label(), Pricing.quote(cat, current.terms(), 1),
                Pricing.quote(cat, current.terms(), months), expected(creche, current, cat.list()), next(creche.id(), today));
    }

    /** Devis sans rien enregistrer : prix mensuel et de la période, au barème qui s'appliquerait. */
    @Transactional(readOnly = true)
    public List<Pricing.Quote> quote(String slug, Input input) {
        var creche = creche(slug);
        var current = contracts.current(creche.id(), LocalDate.now(clock)).orElse(null);
        var cat = catalogue.catalogue(current != null && contracts.invoiced(current.id()) ? current.priceListId()
                : priceListAt(input.signedOn()).id());
        try {
            return List.of(Pricing.quote(cat, input.terms(), 1), Pricing.quote(cat, input.terms(), Pricing.months(input.rhythm())));
        } catch (IllegalArgumentException e) {
            throw ApiException.rule("devis", e.getMessage());
        }
    }

    // --- Écriture

    @Transactional
    public Saved save(String slug, Input input) {
        validate(input);
        var creche = creche(slug);
        var now = clock.instant();
        var today = LocalDate.now(clock);
        var open = contracts.forCreche(creche.id()).stream().filter(c -> c.endsOn() == null).findFirst().orElse(null);
        if (open == null) {
            var list = priceListAt(input.signedOn());
            var contract = contract(UUID.randomUUID(), creche, list.id(), input, input.startsOn(), null);
            check(contract);
            contracts.insert(contract, now);
            parc.becomesClient(creche.id(), input.startsOn(), now);
            audit.record("CONTRAT_SIGNE", "creche:" + slug, Map.of("formule", input.plan(), "debut", input.startsOn().toString(),
                    "bareme", list.label()));
            return new Saved(view(slug), null, 0, null, List.of());
        }
        if (!contracts.invoiced(open.id())) {
            // Pas encore facturé : on corrige la version en place (le barème suit la date de signature).
            var contract = contract(open.id(), creche, priceListAt(input.signedOn()).id(), input, input.startsOn(), null);
            check(contract);
            contracts.update(contract, now);
            audit.record("CONTRAT_MODIFIE", "creche:" + slug, Map.of("formule", input.plan(), "debut", input.startsOn().toString()));
            return new Saved(view(slug), null, 0, null, List.of());
        }
        // Avenant : la version en cours s'arrête la veille du changement, au même barème (tarif figé à la signature).
        var change = input.changeOn() == null ? today : input.changeOn();
        if (!change.isAfter(open.startsOn())) {
            throw ApiException.rule("avenant_date", "L’avenant doit prendre effet après le début de la version en cours (%s)."
                    .formatted(Dates.format(open.startsOn())));
        }
        var amended = contract(UUID.randomUUID(), creche, open.priceListId(), input, change, null);
        check(amended);
        contracts.close(open.id(), change.minusDays(1), now);
        contracts.insert(amended, now);
        var cat = catalogue.catalogue(open.priceListId());
        var covering = invoices.subscriptionCovering(creche.id(), change).orElse(null);
        UUID complement = null;
        long toCredit = 0;
        String creditNumber = null;
        var lines = List.<Pricing.Line>of();
        if (covering != null) {
            int months = (int) ChronoUnit.MONTHS.between(covering.periodFrom(), covering.periodTo().plusDays(1));
            months = Math.max(months, 1);
            lines = Pricing.amendment(Pricing.quote(cat, open.terms(), months), Pricing.quote(cat, amended.terms(), months),
                    covering.periodFrom(), covering.periodTo(), change);
            var positive = lines.stream().filter(l -> l.amount() > 0).toList();
            toCredit = -lines.stream().filter(l -> l.amount() < 0).mapToLong(Pricing.Line::amount).sum();
            creditNumber = toCredit > 0 ? covering.number() : null;
            if (!positive.isEmpty()) {
                complement = draft(creche, amended, "COMPLEMENT", "Complément d’abonnement du " + Dates.format(change),
                        change, covering.periodTo(), positive.stream().map(ContractService::line).toList());
            }
        }
        audit.record("CONTRAT_AVENANT", "creche:" + slug, Map.of("effet", change.toString(), "formule", input.plan(),
                "complement", complement != null, "aRendre", toCredit));
        return new Saved(view(slug), complement, toCredit, creditNumber, lines);
    }

    @Transactional
    public View terminate(String slug, LocalDate endsOn) {
        var creche = creche(slug);
        var open = contracts.forCreche(creche.id()).stream().filter(c -> c.endsOn() == null).findFirst()
                .orElseThrow(() -> ApiException.rule("pas_de_contrat", "Aucun contrat en cours."));
        if (endsOn.isBefore(open.startsOn().minusDays(1))) {
            throw ApiException.rule("fin", "La fin ne peut pas précéder le début du contrat.");
        }
        contracts.close(open.id(), endsOn, clock.instant());
        audit.record("CONTRAT_RESILIE", "creche:" + slug, Map.of("fin", endsOn.toString()));
        return view(slug);
    }

    // --- Renouvellements

    /** Chaque jour à 6 h : brouillon de la période suivante pour chaque contrat qui arrive à l'échéance de préparation. */
    @Scheduled(cron = "0 0 6 * * *", zone = "Pacific/Noumea")
    public void scheduled() {
        if (properties.scheduling()) {
            int prepared = prepareRenewals();
            if (prepared > 0) {
                log.info("Renouvellements : {} brouillon(s) préparé(s)", prepared);
            }
        }
    }

    public int prepareRenewals() {
        var today = LocalDate.now(clock);
        int count = 0;
        var crecheIds = new LinkedHashSet<UUID>();
        contracts.running(today).forEach(c -> crecheIds.add(c.crecheId()));
        for (var crecheId : crecheIds) {
            var prepared = transactions.execute(s -> renewIfDue(crecheId, today, false));
            if (prepared != null) {
                count++;
            }
        }
        return count;
    }

    /** « Préparer la prochaine facture » depuis la fiche : sans attendre l'échéance de préparation. */
    @Transactional
    public UUID prepareNext(String slug) {
        var creche = creche(slug);
        var id = renewIfDue(creche.id(), LocalDate.now(clock), true);
        if (id == null) {
            throw ApiException.rule("rien_a_facturer", "Rien à facturer : pas de contrat en cours pour la période suivante.");
        }
        return id;
    }

    private UUID renewIfDue(UUID crecheId, LocalDate today, boolean now) {
        var next = next(crecheId, today);
        if (next == null || (!now && !next.due())) {
            return null;
        }
        var contract = contracts.current(crecheId, next.from()).filter(c -> c.covers(next.from())).orElse(null);
        if (contract == null) {
            return null;
        }
        var creche = parc.findById(crecheId).orElseThrow();
        var cat = catalogue.catalogue(contract.priceListId());
        int months = Pricing.months(contract.rhythm());
        var full = Pricing.periodEnd(next.from(), months);
        var quote = Pricing.quote(cat, contract.terms(), months);
        long days = ChronoUnit.DAYS.between(next.from(), full) + 1;
        long billed = ChronoUnit.DAYS.between(next.from(), next.to()) + 1;
        var period = Dates.period(next.from(), next.to());
        var lines = new ArrayList<Line>();
        for (var l : quote.lines()) {
            boolean shortened = billed < days;
            long amount = shortened ? Xpf.prorata(l.amount(), billed, days) : l.amount();
            lines.add(new Line(null, 0, l.label(), period + " · " + (shortened ? "prorata %d / %d jours".formatted(billed, days) : l.detail()),
                    shortened ? 1 : l.quantity(), shortened ? amount : l.unitPrice(), amount, l.tgcNature(), 0, null));
        }
        var subject = months == 1 ? "Abonnement " + MONTH.format(next.from())
                : "Abonnement %s – %s".formatted(MONTH.format(next.from()), MONTH.format(next.to()));
        var id = draft(creche, contract, "ABONNEMENT", subject, next.from(), next.to(), lines);
        if (now) {
            audit.record("RENOUVELLEMENT_PREPARE", "creche:" + creche.slug(), Map.of("periode", period));
        } else {
            audit.record(null, null, "RENOUVELLEMENT_PREPARE", "creche:" + creche.slug(), Map.of("periode", period));
        }
        return id;
    }

    /** Période suivante à facturer : après la dernière période d'abonnement facturée, ou au début du contrat. */
    NextPeriod next(UUID crecheId, LocalDate today) {
        var all = contracts.forCreche(crecheId);
        if (all.isEmpty()) {
            return null;
        }
        var from = invoices.lastSubscriptionPeriodEnd(crecheId).map(d -> d.plusDays(1))
                .orElse(all.stream().map(Contract::startsOn).min(LocalDate::compareTo).orElseThrow());
        var contract = all.stream().filter(c -> c.covers(from)).findFirst().orElse(null);
        if (contract == null) {
            return null;
        }
        int months = Pricing.months(contract.rhythm());
        var to = Pricing.periodEnd(from, months);
        // Résiliation prévue dans la période : la dernière version du contrat (la plus récente) porte la fin.
        var lastEnd = all.getFirst().endsOn();
        var end = lastEnd != null && !lastEnd.isBefore(from) && lastEnd.isBefore(to) ? lastEnd : to;
        int lead = months == 12 ? properties.renewalLeadDays() : properties.monthlyLeadDays();
        var preparedFrom = from.minusDays(lead);
        return new NextPeriod(from, end, preparedFrom, !today.isBefore(preparedFrom));
    }

    private UUID draft(ParcRepository.Creche creche, Contract contract, String kind, String subject, LocalDate from, LocalDate to,
                       List<Line> lines) {
        var id = UUID.randomUUID();
        invoices.insert(new Invoice(id, "FACTURE", null, "BROUILLON", kind, creche.clientId(), creche.id(), contract.id(), null, subject,
                from, to, null, null, 0, 0, 0, null, null, null, null, null, null, false, null, null, null), clock.instant());
        invoiceService.writeLines(id, lines.stream().map(l -> new InvoiceService.LineInput(l.label(), l.detail(), l.quantity(),
                l.unitPrice(), l.tgcNature())).toList());
        return id;
    }

    private static Line line(Pricing.Line l) {
        return new Line(null, 0, l.label(), l.detail(), l.quantity(), l.unitPrice(), l.amount(), l.tgcNature(), 0, null);
    }

    // --- Abonnement attendu

    private Expected expected(ParcRepository.Creche creche, Contract contract, Pricing.PriceList list) {
        var cat = catalogue.catalogue(list.id());
        var tier = Pricing.tier(list, contract.places());
        var included = cat.find("FORMULE", contract.plan(), tier).map(Pricing.PriceItem::included).orElse(Set.of());
        var modules = new LinkedHashSet<String>(included);
        modules.addAll(contract.modules());
        var desktops = list.includedDesktops() + contract.extraDesktops();
        var tablets = list.includedTablets() + contract.extraTablets();
        var validUntil = invoices.paidThrough(creche.id()).orElse(null);
        var differences = new ArrayList<String>();
        JsonNode sub = creche.host() == null ? null : creche.host().get("subscription");
        if (sub == null || sub.isNull()) {
            differences.add(creche.host() == null ? "Abonnement de l’instance pas encore lu (rapport de l’hôte)" : "L’instance n’a pas d’abonnement");
        } else {
            var plan = sub.path("plan").asText("");
            var label = contract.plan().charAt(0) + contract.plan().substring(1).toLowerCase();
            if (!plan.toLowerCase().startsWith(label.toLowerCase())) {
                differences.add("Formule : %s dans l’instance, %s au contrat".formatted(plan.isBlank() ? "?" : plan.split("\\s+[—–-]\\s+")[0], label));
            }
            compare(differences, "Places", sub.path("places"), contract.places());
            compare(differences, "PC", sub.path("desktops"), desktops);
            compare(differences, "Tablettes", sub.path("tablets"), tablets);
            var actual = new LinkedHashSet<String>();
            for (var m : sub.path("modules").asText("").split(",")) {
                if (!m.isBlank()) {
                    actual.add(m.strip());
                }
            }
            if (!actual.equals(modules)) {
                var missing = new LinkedHashSet<>(modules);
                missing.removeAll(actual);
                var extra = new LinkedHashSet<>(actual);
                extra.removeAll(modules);
                differences.add("Modules :%s%s".formatted(missing.isEmpty() ? "" : " manquent " + String.join(", ", missing),
                        extra.isEmpty() ? "" : " en trop " + String.join(", ", extra)));
            }
            var until = sub.path("validUntil").asText(null);
            if (validUntil != null && until != null && !until.startsWith(validUntil.toString())) {
                differences.add("Échéance : %s dans l’instance, %s payée au contrat".formatted(Dates.format(LocalDate.parse(until.substring(0, 10))),
                        Dates.format(validUntil)));
            }
        }
        return new Expected(contract.plan(), modules, contract.places(), desktops, tablets, validUntil, differences);
    }

    private static void compare(List<String> differences, String label, JsonNode actual, int expected) {
        if (actual.isNumber() && actual.asInt() != expected) {
            differences.add("%s : %d dans l’instance, %d au contrat".formatted(label, actual.asInt(), expected));
        }
    }

    // --- Outils

    private Contract contract(UUID id, ParcRepository.Creche creche, UUID priceListId, Input in, LocalDate startsOn, LocalDate endsOn) {
        var modules = new LinkedHashSet<String>();
        Pricing.MODULES.stream().filter(m -> in.modules() != null && in.modules().contains(m)).forEach(modules::add);
        return new Contract(id, creche.id(), creche.clientId(), priceListId, in.plan(), in.places(), modules, in.extraDesktops(),
                in.extraTablets(), in.premiumSupport(), in.engagementYears(), in.rhythm(), in.extraStructure(), in.freeDiscount(),
                in.freeDiscount() > 0 ? in.freeDiscountReason().strip() : null, startsOn, endsOn, in.signedOn());
    }

    /** Le barème doit pouvoir chiffrer le contrat. */
    private void check(Contract contract) {
        try {
            Pricing.quote(catalogue.catalogue(contract.priceListId()), contract.terms(), 1);
        } catch (IllegalArgumentException e) {
            throw ApiException.rule("devis", e.getMessage());
        }
    }

    private static void validate(Input in) {
        if (!Pricing.PLANS.contains(in.plan())) {
            throw ApiException.rule("formule", "Formule inconnue.");
        }
        if (in.modules() != null && !Pricing.MODULES.containsAll(in.modules())) {
            throw ApiException.rule("module", "Module inconnu.");
        }
        if (in.freeDiscount() > 0 && (in.freeDiscountReason() == null || in.freeDiscountReason().isBlank())) {
            throw ApiException.rule("remise_motif", "Une remise libre doit être motivée.");
        }
    }

    private Pricing.PriceList priceListAt(LocalDate day) {
        return catalogue.priceListAt(day).orElseThrow(() -> ApiException.rule("bareme_absent",
                "Aucun barème en vigueur au %s (Paramètres › Barème).".formatted(Dates.format(day))));
    }

    private ParcRepository.Creche creche(String slug) {
        return parc.find(slug).orElseThrow(() -> ApiException.notFound("creche_inconnue", "Crèche inconnue : " + slug));
    }
}
