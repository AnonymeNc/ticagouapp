package nc.ticagou.console.billing;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nc.ticagou.console.audit.AuditService;
import nc.ticagou.console.billing.CatalogueRepository.Issuer;
import nc.ticagou.console.billing.CatalogueRepository.TgcRate;
import nc.ticagou.console.billing.Pricing.PriceItem;
import nc.ticagou.console.billing.Pricing.PriceList;
import nc.ticagou.console.common.ApiException;

/** Paramètres de la facturation : barème (une nouvelle version, jamais de retouche du passé), taux de TGC, mentions. */
@Service
public class CatalogueService {

    public record Version(PriceList list, List<PriceItem> items) {
    }

    public record Settings(List<Version> versions, List<TgcRate> tgcRates, Issuer issuer, List<String> missingMentions) {
    }

    private final CatalogueRepository repository;
    private final AuditService audit;
    private final Clock clock;

    public CatalogueService(CatalogueRepository repository, AuditService audit, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Settings settings() {
        var versions = repository.priceLists().stream().map(l -> new Version(l, repository.items(l.id()))).toList();
        var issuer = repository.issuer();
        return new Settings(versions, repository.tgcRates(), issuer, missingMentions(issuer));
    }

    /** Mentions sans lesquelles une facture ne peut pas être validée. */
    static List<String> missingMentions(Issuer issuer) {
        var missing = new java.util.ArrayList<String>();
        if (blank(issuer.name())) {
            missing.add("raison sociale");
        }
        if (blank(issuer.ridet())) {
            missing.add("RIDET");
        }
        if (blank(issuer.address())) {
            missing.add("adresse");
        }
        if (blank(issuer.iban())) {
            missing.add("coordonnées bancaires");
        }
        return missing;
    }

    /** Nouvelle version du barème, à partir d'une date postérieure à la dernière version. */
    @Transactional
    public Settings newVersion(PriceList list, List<PriceItem> items) {
        if (repository.priceListStartsOn(list.validFrom())) {
            throw ApiException.rule("bareme_date", "Une version du barème commence déjà à cette date ou après : choisissez une date plus tardive.");
        }
        var keys = new HashSet<String>();
        for (var item : items) {
            if (!keys.add(item.kind() + "/" + item.code() + "/" + Objects.toString(item.tier(), ""))) {
                throw ApiException.rule("bareme_doublon", "Prix en double : %s %s.".formatted(item.label(), Objects.toString(item.tier(), "")));
            }
        }
        for (var plan : Pricing.PLANS) {
            for (var tier : List.of("MICRO", "STRUCTURE", "GRANDE")) {
                if (!keys.contains("FORMULE/" + plan + "/" + tier)) {
                    throw ApiException.rule("bareme_incomplet", "Il manque le prix de la formule %s %s.".formatted(plan, tier));
                }
            }
        }
        var id = UUID.randomUUID();
        repository.insertPriceList(new PriceList(id, list.label(), list.validFrom(), null, list.microMax(), list.structureMax(),
                list.grandeMax(), list.includedDesktops(), list.includedTablets(), list.engagement3Discount(),
                list.extraStructureDiscount()), items, clock.instant());
        audit.record("BAREME_VERSION", "bareme:" + id, Map.of("libelle", list.label(), "debut", list.validFrom().toString()));
        return settings();
    }

    @Transactional
    public Settings newRate(String nature, int rate, LocalDate from, String note) {
        if (repository.rateStartsOnOrAfter(nature, from)) {
            throw ApiException.rule("tgc_date", "Un taux de cette nature commence déjà à cette date ou après.");
        }
        repository.insertRate(nature, rate, from, note);
        audit.record("TGC_TAUX", "tgc:" + nature, Map.of("taux", Xpf.rate(rate), "debut", from.toString()));
        return settings();
    }

    @Transactional
    public Settings updateIssuer(Issuer issuer) {
        var before = repository.issuer();
        repository.updateIssuer(issuer, clock.instant());
        var changes = new LinkedHashMap<String, Object>();
        var fields = List.of("name", "legalForm", "ridet", "tgcNumber", "address", "email", "phone", "bank", "iban", "paymentTerms",
                "latePenalties", "footer", "copyEmail");
        var old = List.of(n(before.name()), n(before.legalForm()), n(before.ridet()), n(before.tgcNumber()), n(before.address()),
                n(before.email()), n(before.phone()), n(before.bank()), n(before.iban()), n(before.paymentTerms()),
                n(before.latePenalties()), n(before.footer()), n(before.copyEmail()));
        var now = List.of(n(issuer.name()), n(issuer.legalForm()), n(issuer.ridet()), n(issuer.tgcNumber()), n(issuer.address()),
                n(issuer.email()), n(issuer.phone()), n(issuer.bank()), n(issuer.iban()), n(issuer.paymentTerms()),
                n(issuer.latePenalties()), n(issuer.footer()), n(issuer.copyEmail()));
        for (int i = 0; i < fields.size(); i++) {
            if (!old.get(i).equals(now.get(i))) {
                changes.put(fields.get(i), Map.of("avant", old.get(i), "apres", now.get(i)));
            }
        }
        if (!changes.isEmpty()) {
            audit.record("EDITEUR_MODIFIE", "editeur", changes);
        }
        return settings();
    }

    private static String n(String value) {
        return value == null ? "" : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
