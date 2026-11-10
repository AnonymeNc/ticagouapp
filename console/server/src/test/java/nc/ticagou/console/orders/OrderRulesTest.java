package nc.ticagou.console.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** Liste blanche des ordres : rien ne sort de l'alphabet sûr que creche.sh accepte. */
class OrderRulesTest {

    @Test
    void abonnementControleEtLigneDeLHote() {
        // Une seule formule (ADR 0021) : l'offre et les modules d'un ancien ordre ne sont plus transmis.
        var params = OrderRules.check("ABONNEMENT", "lagon-bleu", Map.of("numero", "C-1A2B3C4D", "offre", "gestion", "places", "12", "pc", "1",
                "tablettes", "2", "jusqu_au", "2027-10-31", "modules", "REPAS", "intrus", "rm -rf /"));
        assertThat(params).as("seules les clés attendues, dans l'ordre").containsExactly(Map.entry("numero", "C-1A2B3C4D"),
                Map.entry("places", "12"), Map.entry("pc", "1"), Map.entry("tablettes", "2"), Map.entry("jusqu_au", "2027-10-31"));
        assertThat(OrderRules.line("id", "ABONNEMENT", "lagon-bleu", params))
                .isEqualTo("id\tABONNEMENT\tlagon-bleu\tnumero=C-1A2B3C4D;places=12;pc=1;tablettes=2;jusqu_au=2027-10-31");
    }

    @Test
    void valeursHorsAlphabetRefusees() {
        assertThatThrownBy(() -> OrderRules.check("RM", "lagon-bleu", Map.of())).hasMessageContaining("Ordre inconnu");
        assertThatThrownBy(() -> OrderRules.check("SUSPENDRE", "Lagon Bleu", Map.of())).hasMessageContaining("Identifiant");
        assertThatThrownBy(() -> OrderRules.check("SUSPENDRE", "a;b", Map.of())).hasMessageContaining("Identifiant");
        assertThatThrownBy(() -> OrderRules.check("ABONNEMENT", "x1", Map.of("numero", "1; reboot", "offre", "gestion", "places", "1", "pc", "1",
                "tablettes", "1", "jusqu_au", "2027-01-01"))).hasMessageContaining("Numéro");
        assertThatThrownBy(() -> OrderRules.check("ABONNEMENT", "x1", Map.of("numero", "1", "offre", "gestion", "places", "0", "pc", "1",
                "tablettes", "1", "jusqu_au", "2027-01-01"))).hasMessageContaining("places");
        assertThatThrownBy(() -> OrderRules.check("METTRE_A_JOUR", "x1", Map.of("version", "1.5.0 && reboot"))).hasMessageContaining("Version");
        assertThatThrownBy(() -> OrderRules.check("CREER", "x1", Map.of("domaine", "a b.nc"))).hasMessageContaining("Domaine");
    }

    @Test
    void exportPuisSuppressionNommantLExportDeLaMemeCreche() {
        assertThat(OrderRules.check("EXPORTER", "niaoulis", Map.of("suite", "supprimer", "confirmation", "niaoulis")))
                .as("la confirmation ne part pas à l'hôte").containsExactly(Map.entry("suite", "supprimer"));
        assertThat(OrderRules.check("EXPORTER", "niaoulis", null)).isEmpty();
        assertThatThrownBy(() -> OrderRules.check("EXPORTER", "niaoulis", Map.of("suite", "reboot"))).hasMessageContaining("Suite");
        assertThat(OrderRules.check("SUPPRIMER", "niaoulis", Map.of("export", "niaoulis-20261109-1030.tar.gz.enc")))
                .containsExactly(Map.entry("export", "niaoulis-20261109-1030.tar.gz.enc"));
        assertThatThrownBy(() -> OrderRules.check("SUPPRIMER", "niaoulis", Map.of())).hasMessageContaining("sans export");
        assertThatThrownBy(() -> OrderRules.check("SUPPRIMER", "niaoulis", Map.of("export", "tiare-20261109-1030.tar.gz.enc")))
                .as("l'export d'une autre crèche").hasMessageContaining("sans export");
        assertThatThrownBy(() -> OrderRules.check("SUPPRIMER", "ab", Map.of("export", "ab-cd-20261109-1030.tar.gz.enc")))
                .as("préfixe d'une autre crèche").hasMessageContaining("sans export");
        assertThatThrownBy(() -> OrderRules.check("SUPPRIMER", "niaoulis", Map.of("export", "../niaoulis-20261109-1030.tar.gz.enc")))
                .hasMessageContaining("sans export");
    }

    @Test
    void rubriquesMasqueesEtAccesDeLaCreche() {
        assertThat(OrderRules.check("RUBRIQUES", "niaoulis", Map.of("masquer", "REPAS,COMPTABILITE,REPAS")))
                .as("sans doublon, dans l'ordre de la liste").containsExactly(Map.entry("masquer", "COMPTABILITE,REPAS"));
        assertThat(OrderRules.check("RUBRIQUES", "niaoulis", Map.of("masquer", ""))).as("tout réafficher").isEmpty();
        assertThatThrownBy(() -> OrderRules.check("RUBRIQUES", "niaoulis", Map.of("masquer", "REPAS;reboot")))
                .hasMessageContaining("Rubrique inconnue");
        assertThat(OrderRules.check("TELEPHONE_PERDU", "niaoulis", Map.of("identifiant", "nathalie.kaloi")))
                .containsExactly(Map.entry("identifiant", "nathalie.kaloi"));
        assertThatThrownBy(() -> OrderRules.check("TELEPHONE_PERDU", "niaoulis", Map.of("identifiant", "x' OR 1=1")))
                .hasMessageContaining("Identifiant du compte");
        assertThat(OrderRules.check("CODE_PORTAIL", "niaoulis", Map.of("bruit", "x"))).isEmpty();
    }

    @Test
    void ordresSansParametre() {
        assertThat(OrderRules.check("SUSPENDRE", "niaoulis", Map.of("bruit", "x"))).isEmpty();
        assertThat(OrderRules.check("DEMO", "demo-1", Map.of("formule", "Gestion"))).as("plus de formule de démonstration").isEmpty();
        assertThat(OrderRules.check("CREER", "tiare", Map.of("domaine", "creche-tiare.nc"))).containsEntry("domaine", "creche-tiare.nc");
    }
}
