package nc.ticagou.console.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** Liste blanche des ordres : rien ne sort de l'alphabet sûr que creche.sh accepte. */
class OrderRulesTest {

    @Test
    void abonnementControleEtLigneDeLHote() {
        var params = OrderRules.check("ABONNEMENT", "lagon-bleu", Map.of("numero", "C-1A2B3C4D", "offre", "gestion", "places", "12", "pc", "1",
                "tablettes", "2", "jusqu_au", "2027-10-31", "modules", "REPAS", "intrus", "rm -rf /"));
        assertThat(params).as("seules les clés attendues, dans l'ordre").containsExactly(Map.entry("numero", "C-1A2B3C4D"),
                Map.entry("offre", "gestion"), Map.entry("places", "12"), Map.entry("pc", "1"), Map.entry("tablettes", "2"),
                Map.entry("jusqu_au", "2027-10-31"), Map.entry("modules", "REPAS"));
        assertThat(OrderRules.line("id", "ABONNEMENT", "lagon-bleu", params))
                .isEqualTo("id\tABONNEMENT\tlagon-bleu\tnumero=C-1A2B3C4D;offre=gestion;places=12;pc=1;tablettes=2;jusqu_au=2027-10-31;modules=REPAS");
    }

    @Test
    void valeursHorsAlphabetRefusees() {
        assertThatThrownBy(() -> OrderRules.check("RM", "lagon-bleu", Map.of())).hasMessageContaining("Ordre inconnu");
        assertThatThrownBy(() -> OrderRules.check("SUSPENDRE", "Lagon Bleu", Map.of())).hasMessageContaining("Identifiant");
        assertThatThrownBy(() -> OrderRules.check("SUSPENDRE", "a;b", Map.of())).hasMessageContaining("Identifiant");
        assertThatThrownBy(() -> OrderRules.check("ABONNEMENT", "x1", Map.of("numero", "1; reboot", "offre", "gestion", "places", "1", "pc", "1",
                "tablettes", "1", "jusqu_au", "2027-01-01"))).hasMessageContaining("Numéro");
        assertThatThrownBy(() -> OrderRules.check("ABONNEMENT", "x1", Map.of("numero", "1", "offre", "premium", "places", "1", "pc", "1",
                "tablettes", "1", "jusqu_au", "2027-01-01"))).hasMessageContaining("Offre");
        assertThatThrownBy(() -> OrderRules.check("ABONNEMENT", "x1", Map.of("numero", "1", "offre", "gestion", "places", "0", "pc", "1",
                "tablettes", "1", "jusqu_au", "2027-01-01"))).hasMessageContaining("places");
        assertThatThrownBy(() -> OrderRules.check("ABONNEMENT", "x1", Map.of("numero", "1", "offre", "gestion", "places", "1", "pc", "1",
                "tablettes", "1", "jusqu_au", "2027-01-01", "modules", "REPAS,$(id)"))).hasMessageContaining("Module inconnu");
        assertThatThrownBy(() -> OrderRules.check("METTRE_A_JOUR", "x1", Map.of("version", "1.5.0 && reboot"))).hasMessageContaining("Version");
        assertThatThrownBy(() -> OrderRules.check("CREER", "x1", Map.of("domaine", "a b.nc"))).hasMessageContaining("Domaine");
        assertThatThrownBy(() -> OrderRules.check("DEMO", "x1", Map.of("formule", "Premium"))).hasMessageContaining("Formule");
    }

    @Test
    void ordresSansParametre() {
        assertThat(OrderRules.check("SUSPENDRE", "niaoulis", Map.of("bruit", "x"))).isEmpty();
        assertThat(OrderRules.check("DEMO", "demo-1", null)).containsExactly(Map.entry("formule", "Complet"));
        assertThat(OrderRules.check("CREER", "tiare", Map.of("domaine", "creche-tiare.nc"))).containsEntry("domaine", "creche-tiare.nc");
    }
}
