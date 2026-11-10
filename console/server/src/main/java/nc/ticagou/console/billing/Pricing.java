package nc.ticagou.console.billing;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Prix d'un contrat d'abonnement, sans Spring ni base : barème (version datée), tranche de places, formule, modules
 * ajoutés, équipement au-delà de ce que la formule comprend, support, remises (engagement 3 ans, structure
 * supplémentaire, remise libre motivée), période et prorata. Tous les arrondis passent par {@link Xpf}.
 */
public final class Pricing {

    private Pricing() {
    }

    /**
     * La formule unique, tout compris (ADR 0021), sous son code historique : seule proposée aux nouveaux contrats et
     * avenants, et seule exigée dans un barème.
     */
    public static final String OFFER = "COMPLET";
    /** Formules connues, dont les anciennes : les contrats signés avant l'ADR 0021 se facturent jusqu'à leur avenant. */
    public static final List<String> PLANS = List.of("ESSENTIEL", "GESTION", "COMPLET");
    /** Modules à la carte des anciens contrats : plus proposés, encore chiffrés tant que ces contrats courent. */
    public static final List<String> MODULES = List.of("INSCRIPTIONS", "FACTURATION", "COMPTABILITE", "PERSONNEL", "REPAS",
            "PORTAIL_PARENTS");

    public record PriceList(UUID id, String label, LocalDate validFrom, LocalDate validTo, int microMax, int structureMax,
                            int grandeMax, int includedDesktops, int includedTablets, int engagement3Discount,
                            int extraStructureDiscount) {

        public boolean covers(LocalDate day) {
            return !day.isBefore(validFrom) && (validTo == null || !day.isAfter(validTo));
        }
    }

    public record PriceItem(String kind, String code, String tier, String label, long price, String unit, String includes,
                            String tgcNature) {

        public Set<String> included() {
            return includes == null || includes.isBlank() ? Set.of()
                    : new LinkedHashSet<>(Arrays.stream(includes.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList());
        }
    }

    public record Catalogue(PriceList list, List<PriceItem> items) {

        public Optional<PriceItem> find(String kind, String code, String tier) {
            return items.stream().filter(i -> i.kind().equals(kind) && i.code().equals(code) && java.util.Objects.equals(i.tier(), tier))
                    .findFirst();
        }

        PriceItem require(String kind, String code, String tier) {
            return find(kind, code, tier).orElseThrow(() -> new IllegalArgumentException(
                    "Prix absent du barème « %s » : %s %s%s".formatted(list.label(), kind, code, tier == null ? "" : " " + tier)));
        }
    }

    /** Ce que la crèche a souscrit. Remises en dixièmes de pour cent. */
    public record Terms(String plan, int places, Set<String> modules, int extraDesktops, int extraTablets, boolean premiumSupport,
                        int engagementYears, boolean extraStructure, int freeDiscount, String freeDiscountReason) {
    }

    /** Ligne de prix : {@code monthly} prix mensuel (nul pour une remise), {@code amount} montant de la période. */
    public record Line(String code, String label, String detail, int quantity, long unitPrice, long amount, String tgcNature) {
    }

    public record Quote(String tier, boolean onQuote, List<Line> lines, long subtotal, long total) {
    }

    /** Tranche selon les places agréées ; au-delà de la plus grande, on reste en Grande et le prix se fait sur devis. */
    public static String tier(PriceList list, int places) {
        if (places <= list.microMax()) {
            return "MICRO";
        }
        return places <= list.structureMax() ? "STRUCTURE" : "GRANDE";
    }

    /**
     * Prix de {@code months} mois d'abonnement. Les lignes d'abonnement valent prix mensuel × mois ; chaque remise est
     * calculée sur le sous-total de ces lignes (elles s'additionnent, elles ne se composent pas).
     */
    public static Quote quote(Catalogue catalogue, Terms terms, int months) {
        if (!PLANS.contains(terms.plan())) {
            throw new IllegalArgumentException("Formule inconnue : " + terms.plan());
        }
        var list = catalogue.list();
        var tier = tier(list, terms.places());
        var lines = new ArrayList<Line>();
        var formula = catalogue.require("FORMULE", terms.plan(), tier);
        lines.add(recurring(formula, formula.label(), months, 1));
        var included = formula.included();
        for (var module : MODULES) {
            if (terms.modules().contains(module) && !included.contains(module)) {
                var item = catalogue.require("MODULE", module, tier);
                lines.add(recurring(item, "Module " + item.label(), months, 1));
            }
        }
        if (terms.extraDesktops() > 0) {
            var item = catalogue.require("EQUIPEMENT", "PC", null);
            lines.add(recurring(item, item.label() + (terms.extraDesktops() > 1 ? " (%d)".formatted(terms.extraDesktops()) : ""),
                    months, terms.extraDesktops()));
        }
        if (terms.extraTablets() > 0) {
            var item = catalogue.require("EQUIPEMENT", "TABLETTE", null);
            lines.add(recurring(item, item.label() + (terms.extraTablets() > 1 ? " (%d)".formatted(terms.extraTablets()) : ""),
                    months, terms.extraTablets()));
        }
        if (terms.premiumSupport()) {
            var item = catalogue.require("SUPPORT", "PREMIUM", null);
            lines.add(recurring(item, item.label(), months, 1));
        }
        long subtotal = lines.stream().mapToLong(Line::amount).sum();
        if (terms.engagementYears() == 3 && list.engagement3Discount() > 0) {
            lines.add(discount("REMISE_ENGAGEMENT", "Remise engagement 3 ans", list.engagement3Discount(), subtotal));
        }
        if (terms.extraStructure() && list.extraStructureDiscount() > 0) {
            lines.add(discount("REMISE_STRUCTURE", "Remise structure supplémentaire", list.extraStructureDiscount(), subtotal));
        }
        if (terms.freeDiscount() > 0) {
            lines.add(discount("REMISE_LIBRE", "Remise : " + terms.freeDiscountReason(), terms.freeDiscount(), subtotal));
        }
        long total = lines.stream().mapToLong(Line::amount).sum();
        if (total < 0) {
            throw new IllegalArgumentException("Les remises dépassent le prix de l’abonnement.");
        }
        return new Quote(tier, terms.places() > list.grandeMax(), List.copyOf(lines), subtotal, total);
    }

    private static Line recurring(PriceItem item, String label, int months, int count) {
        long monthly = item.price() * count;
        return new Line(item.code(), label, months == 1 ? "1 mois" : "%d mois à %s".formatted(months, Xpf.format(monthly)),
                months, monthly, monthly * months, item.tgcNature());
    }

    private static Line discount(String code, String label, int tenths, long subtotal) {
        long amount = -Xpf.percent(subtotal, tenths);
        return new Line(code, label, "%s de %s".formatted(Xpf.rate(tenths), Xpf.format(subtotal)), 1, amount, amount, "ABONNEMENT");
    }

    // --- Périodes ---------------------------------------------------------------------------------------------------

    /** Mois facturés d'un coup : 12 pour un paiement annuel d'avance, 1 pour un paiement mensuel. */
    public static int months(String rhythm) {
        return "ANNUEL".equals(rhythm) ? 12 : 1;
    }

    /** Dernier jour d'une période qui commence le {@code from} : 01/11/2026 sur 12 mois → 31/10/2027. */
    public static LocalDate periodEnd(LocalDate from, int months) {
        return from.plusMonths(months).minusDays(1);
    }

    /** Jours restants, {@code change} compris, dans la période [from, to]. */
    public static long remainingDays(LocalDate from, LocalDate to, LocalDate change) {
        if (change.isAfter(to)) {
            return 0;
        }
        var start = change.isBefore(from) ? from : change;
        return ChronoUnit.DAYS.between(start, to) + 1;
    }

    /**
     * Avenant en cours de période : pour chaque ligne qui change, la différence entre le nouveau et l'ancien prix de la
     * période, au prorata des jours restants (jour du changement compris). Positif : à facturer ; négatif : à rendre
     * par avoir.
     */
    public static List<Line> amendment(Quote before, Quote after, LocalDate from, LocalDate to, LocalDate change) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        long remaining = remainingDays(from, to, change);
        var amounts = new LinkedHashMap<String, Long>();
        var labels = new LinkedHashMap<String, Line>();
        before.lines().forEach(l -> {
            amounts.merge(l.code(), -l.amount(), Long::sum);
            labels.putIfAbsent(l.code(), l);
        });
        after.lines().forEach(l -> {
            amounts.merge(l.code(), l.amount(), Long::sum);
            labels.put(l.code(), l);
        });
        var result = new ArrayList<Line>();
        for (Map.Entry<String, Long> entry : amounts.entrySet()) {
            if (entry.getValue() == 0) {
                continue;
            }
            long amount = Xpf.prorata(entry.getValue(), remaining, days);
            if (amount == 0) {
                continue;
            }
            var line = labels.get(entry.getKey());
            result.add(new Line(entry.getKey(), (amount > 0 ? "Complément : " : "Réduction : ") + line.label(),
                    "prorata %d / %d jours, du %s".formatted(remaining, days, Dates.format(change.isBefore(from) ? from : change)),
                    1, amount, amount, line.tgcNature()));
        }
        return result;
    }
}
