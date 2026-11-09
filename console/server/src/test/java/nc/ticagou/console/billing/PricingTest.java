package nc.ticagou.console.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import nc.ticagou.console.billing.Pricing.Catalogue;
import nc.ticagou.console.billing.Pricing.PriceItem;
import nc.ticagou.console.billing.Pricing.PriceList;
import nc.ticagou.console.billing.Pricing.Terms;

/** Prix d'un abonnement selon le barème (docs/commercial/bareme-tarifaire.md, exemples du § 6), remises, prorata, TGC. */
class PricingTest {

    static final PriceList LIST = new PriceList(UUID.randomUUID(), "Barème 2026", LocalDate.of(2026, 1, 1), null, 15, 40, 80, 1, 2, 100, 150);
    static final String GESTION = "INSCRIPTIONS,FACTURATION,PORTAIL_PARENTS";

    static final Catalogue CATALOGUE = new Catalogue(LIST, List.of(
            item("FORMULE", "ESSENTIEL", "MICRO", 6500, ""), item("FORMULE", "ESSENTIEL", "STRUCTURE", 9500, ""),
            item("FORMULE", "ESSENTIEL", "GRANDE", 14000, ""),
            item("FORMULE", "GESTION", "MICRO", 11500, GESTION), item("FORMULE", "GESTION", "STRUCTURE", 16500, GESTION),
            item("FORMULE", "GESTION", "GRANDE", 23500, GESTION),
            item("FORMULE", "COMPLET", "GRANDE", 36500, String.join(",", Pricing.MODULES)),
            item("MODULE", "REPAS", "MICRO", 2000, null), item("MODULE", "REPAS", "STRUCTURE", 2500, null),
            item("MODULE", "PORTAIL_PARENTS", "MICRO", 3500, null), item("MODULE", "PERSONNEL", "MICRO", 2500, null),
            item("EQUIPEMENT", "PC", null, 1500, null), item("EQUIPEMENT", "TABLETTE", null, 1000, null),
            item("SUPPORT", "PREMIUM", null, 4000, null)));

    static PriceItem item(String kind, String code, String tier, long price, String includes) {
        // Comme le barème : la formule porte sa tranche (« Gestion Micro »), un module non (« Repas »).
        var label = code.charAt(0) + code.substring(1).toLowerCase()
                + ("FORMULE".equals(kind) ? " " + tier.charAt(0) + tier.substring(1).toLowerCase() : "");
        return new PriceItem(kind, code, tier, label, price, null, includes, "ABONNEMENT");
    }

    static Terms terms(String plan, int places, Set<String> modules) {
        return new Terms(plan, places, modules, 0, 0, false, 1, false, 0, null);
    }

    @Test
    void trancheSelonLesPlaces() {
        assertThat(Pricing.tier(LIST, 15)).isEqualTo("MICRO");
        assertThat(Pricing.tier(LIST, 16)).isEqualTo("STRUCTURE");
        assertThat(Pricing.tier(LIST, 40)).isEqualTo("STRUCTURE");
        assertThat(Pricing.tier(LIST, 41)).isEqualTo("GRANDE");
        assertThat(Pricing.quote(CATALOGUE, terms("GESTION", 80, Set.of()), 12).onQuote()).isFalse();
        assertThat(Pricing.quote(CATALOGUE, terms("GESTION", 81, Set.of()), 12).onQuote()).as("au-delà : sur devis").isTrue();
    }

    @Test
    void microCrecheGestionEtRepas() {
        // Exemple du barème : Gestion Micro 11 500 F + Repas 2 000 F par mois, 162 000 F par an.
        var quote = Pricing.quote(CATALOGUE, terms("GESTION", 12, Set.of("REPAS", "PORTAIL_PARENTS")), 12);
        assertThat(quote.tier()).isEqualTo("MICRO");
        assertThat(quote.lines()).extracting(Pricing.Line::code).as("le portail est compris dans Gestion").containsExactly("GESTION", "REPAS");
        assertThat(quote.lines()).extracting(Pricing.Line::amount).containsExactly(138_000L, 24_000L);
        assertThat(quote.lines().getFirst().quantity()).isEqualTo(12);
        assertThat(quote.lines().getFirst().unitPrice()).isEqualTo(11_500);
        assertThat(quote.total()).isEqualTo(162_000);
        assertThat(Pricing.quote(CATALOGUE, terms("GESTION", 12, Set.of("REPAS")), 1).total()).as("un mois").isEqualTo(13_500);
    }

    @Test
    void grandeCrecheCompletEquipementEtSupport() {
        // 48 places, Complet, 2 PC et 5 tablettes (1 PC et 2 tablettes compris), support Premium : 45 000 F par mois.
        var quote = Pricing.quote(CATALOGUE, new Terms("COMPLET", 48, Set.of("REPAS"), 1, 3, true, 1, false, 0, null), 12);
        assertThat(quote.lines()).extracting(Pricing.Line::code).containsExactly("COMPLET", "PC", "TABLETTE", "PREMIUM");
        assertThat(quote.lines().get(2).unitPrice()).as("3 tablettes à 1 000 F").isEqualTo(3_000);
        assertThat(quote.total()).isEqualTo(540_000);
    }

    @Test
    void remisesCalculeesSurLeSousTotalEtAdditionnees() {
        var quote = Pricing.quote(CATALOGUE, new Terms("GESTION", 12, Set.of("REPAS"), 0, 0, false, 3, true, 25, "Crèche pilote"), 12);
        assertThat(quote.subtotal()).isEqualTo(162_000);
        assertThat(quote.lines()).extracting(Pricing.Line::code)
                .containsExactly("GESTION", "REPAS", "REMISE_ENGAGEMENT", "REMISE_STRUCTURE", "REMISE_LIBRE");
        assertThat(quote.lines()).extracting(Pricing.Line::amount).endsWith(-16_200L, -24_300L, -4_050L);
        assertThat(quote.total()).isEqualTo(162_000 - 16_200 - 24_300 - 4_050);
        assertThat(quote.lines().getLast().label()).isEqualTo("Remise : Crèche pilote");
        assertThat(Pricing.quote(CATALOGUE, new Terms("GESTION", 12, Set.of(), 0, 0, false, 1, false, 1000, "Offert"), 12).total())
                .as("100 % : zéro, jamais négatif").isZero();
        assertThatThrownBy(() -> Pricing.quote(CATALOGUE, new Terms("GESTION", 12, Set.of(), 0, 0, false, 3, true, 1000, "Trop"), 12))
                .hasMessageContaining("dépassent");
    }

    @Test
    void prixAbsentDuBaremeRefuse() {
        assertThatThrownBy(() -> Pricing.quote(CATALOGUE, terms("COMPLET", 12, Set.of()), 12))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("COMPLET MICRO");
        assertThatThrownBy(() -> Pricing.quote(CATALOGUE, terms("PREMIUM", 12, Set.of()), 12)).hasMessageContaining("Formule inconnue");
    }

    @Test
    void periodesEtProrataAuJour() {
        var from = LocalDate.of(2026, 11, 1);
        var to = Pricing.periodEnd(from, 12);
        assertThat(to).isEqualTo(LocalDate.of(2027, 10, 31));
        assertThat(Pricing.months("ANNUEL")).isEqualTo(12);
        assertThat(Pricing.months("MENSUEL")).isEqualTo(1);
        assertThat(Pricing.remainingDays(from, to, LocalDate.of(2027, 5, 1))).isEqualTo(184);

        // Repas ajouté le 01/05/2027 : 24 000 F × 184 / 365 = 12 098,6 → 12 099 F.
        var before = Pricing.quote(CATALOGUE, terms("GESTION", 12, Set.of()), 12);
        var after = Pricing.quote(CATALOGUE, terms("GESTION", 12, Set.of("REPAS")), 12);
        var lines = Pricing.amendment(before, after, from, to, LocalDate.of(2027, 5, 1));
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().amount()).isEqualTo(12_099);
        assertThat(lines.getFirst().label()).isEqualTo("Complément : Module Repas");
        assertThat(lines.getFirst().detail()).isEqualTo("prorata 184 / 365 jours, du 01/05/2027");

        // Passage à 20 places (Structure) et retrait du Repas le même jour : formule plus chère, module en moins.
        var bigger = Pricing.quote(CATALOGUE, terms("GESTION", 20, Set.of()), 12);
        var change = Pricing.amendment(after, bigger, from, to, LocalDate.of(2027, 5, 1));
        assertThat(change).extracting(Pricing.Line::amount).containsExactly(30_247L, -12_099L);
        assertThat(change.get(1).label()).startsWith("Réduction");
        assertThat(Pricing.amendment(before, after, from, to, LocalDate.of(2027, 11, 1))).as("après la période : rien").isEmpty();
    }

    @Test
    void tgcParTauxSurLeTotalHorsTaxe() {
        // Exemple de la maquette : 138 000 + 24 000 F HT, TGC 11 % = 17 820 F, 179 820 F TTC.
        var result = Totals.of(List.of(new Totals.Line(138_000, 110), new Totals.Line(24_000, 110)));
        assertThat(result.ht()).isEqualTo(162_000);
        assertThat(result.tgc()).isEqualTo(17_820);
        assertThat(result.ttc()).isEqualTo(179_820);
        // Arrondi sur le total du taux, pas ligne par ligne : 3 × 1 005 F à 11 % = 331,65 → 332 F (et non 3 × 111).
        assertThat(Totals.of(List.of(new Totals.Line(1005, 110), new Totals.Line(1005, 110), new Totals.Line(1005, 110))).tgc())
                .isEqualTo(332);
        var mixed = Totals.of(List.of(new Totals.Line(1000, 110), new Totals.Line(1000, 0), new Totals.Line(-200, 110)));
        assertThat(mixed.rates()).extracting(Totals.RateTotal::rate).containsExactly(0, 110);
        assertThat(mixed.tgc()).isEqualTo(88);
        assertThat(mixed.ttc()).isEqualTo(1888);
    }

    @Test
    void arrondisEtFormats() {
        assertThat(Xpf.divide(5, 2)).isEqualTo(3);
        assertThat(Xpf.divide(-5, 2)).isEqualTo(-3);
        assertThat(Xpf.percent(162_000, 110)).isEqualTo(17_820);
        assertThat(Xpf.format(179_820)).isEqualTo("179 820 F");
        assertThat(Xpf.format(-4_860)).isEqualTo("-4 860 F");
        assertThat(Xpf.format(0)).isEqualTo("0 F");
        assertThat(Xpf.rate(110)).isEqualTo("11 %");
        assertThat(Xpf.rate(55)).isEqualTo("5,5 %");
    }
}
