package nc.ticagou.structure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import nc.ticagou.IntegrationTest;

/** Rubriques masquées à la demande de la crèche (ADR 0022) : lues par le bureau et la tablette, sans rien refuser. */
class RubriqueIT extends IntegrationTest {

    private static ArrayList<String> texts(JsonNode array) {
        var out = new ArrayList<String>();
        array.forEach(n -> out.add(n.isObject() ? n.get("key").asText() : n.asText()));
        return out;
    }

    @Test
    void rubriquesMasqueesParLEditeurAvecLeursDependances() throws Exception {
        var director = install();
        assertThat(body(getAs(director, "/api/v1/rubriques")).get("hidden")).as("tout est affiché par défaut").isEmpty();
        assertThat(texts(body(getAs(director, "/api/v1/getting-started")))).contains("bareme", "portail");

        // Écrit par creche.sh rubriques (ordre RUBRIQUES de la console) ; un nom inconnu est ignoré.
        jdbc.update("UPDATE structure SET hidden_sections = 'INSCRIPTIONS,PORTAIL_PARENTS,PERSONNEL,INCONNU'");

        var rubriques = body(getAs(director, "/api/v1/rubriques"));
        assertThat(texts(rubriques.get("hidden"))).as("sans contrats, ni facturation ni comptabilité")
                .containsExactly("INSCRIPTIONS", "FACTURATION", "COMPTABILITE", "PERSONNEL", "PORTAIL_PARENTS");
        assertThat(texts(rubriques.get("labels"))).contains("Personnel (planning, congés, heures)");
        assertThat(texts(body(getAs(director, "/api/v1/getting-started")))).doesNotContain("bareme", "portail").contains("enfants");

        var tablet = pairDevice(director, "Accueil");
        assertThat(texts(body(getAs(tablet.token(), "/api/v1/rubriques")).get("hidden"))).contains("PORTAIL_PARENTS");

        // Simple affichage : la direction garde l'accès (une rubrique masquée par erreur se rouvre sans perte).
        assertThat(status(getAs(director, "/api/v1/tariff-schedules"))).isEqualTo(200);
        assertThat(status(getAs(null, "/api/v1/rubriques"))).isEqualTo(401);
    }
}
