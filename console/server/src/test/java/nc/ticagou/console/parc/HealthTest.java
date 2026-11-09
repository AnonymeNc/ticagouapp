package nc.ticagou.console.parc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Pastille du parc, versions, lecture de la formule et du disque. */
class HealthTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant NOW = Instant.parse("2026-11-09T21:00:00Z");

    static JsonNode node(String text) throws Exception {
        return JSON.readTree(text);
    }

    static JsonNode status(String version, int desktops, int limit, int tablets) throws Exception {
        return node("""
                {"version":"%s","license":{"state":"ACTIVE","desktops":%d,"desktopLimit":%d},"tablets":{"paired":%d}}"""
                .formatted(version, desktops, limit, tablets));
    }

    static Health health(String lifecycle, JsonNode status, JsonNode host, List<String> alerts, boolean hostReporting) {
        return Health.evaluate(new Health.Input(lifecycle, status == null ? null : NOW, false, status, host, alerts, "1.5.0", hostReporting, 0));
    }

    @Test
    void enOrdre() throws Exception {
        var host = node("{\"container\":\"healthy\",\"subscription\":{\"tablets\":2}}");
        assertThat(health("CLIENT", status("1.5.0", 1, 1, 2), host, List.of(), true))
                .isEqualTo(new Health(Health.Level.OK, List.of()));
    }

    @Test
    void alerteOuverteOuConteneurEnEchecRouge() throws Exception {
        var host = node("{\"container\":\"healthy\"}");
        var red = health("CLIENT", status("1.5.0", 1, 1, 1), host, List.of("Sauvegarde"), true);
        assertThat(red.level()).isEqualTo(Health.Level.BAD);
        assertThat(red.reasons()).containsExactly("Sauvegarde");
        var unhealthy = health("CLIENT", status("1.5.0", 1, 1, 1), node("{\"container\":\"unhealthy\"}"), List.of(), true);
        assertThat(unhealthy.reasons()).containsExactly("Conteneur en échec");
    }

    @Test
    void aSurveillerOrange() throws Exception {
        var host = node("{\"container\":\"healthy\",\"subscription\":{\"tablets\":2}}");
        var warn = health("CLIENT", status("1.4.2", 3, 2, 3), host, List.of(), true);
        assertThat(warn.level()).isEqualTo(Health.Level.WARN);
        assertThat(warn.reasons()).containsExactly("PC au-delà du contrat (3/2)", "Tablettes au-delà du contrat (3/2)",
                "Version en retard (1.4.2, courante 1.5.0)");
        assertThat(health("CLIENT", null, host, List.of(), true).reasons()).containsExactly("Aucun état de supervision reçu");
        assertThat(health("CLIENT", status("1.5.0", 1, 1, 1), null, List.of(), true).reasons())
                .as("l'hôte envoie ses rapports mais ne cite pas la crèche").containsExactly("Absente du rapport de l’hôte");
        assertThat(health("CLIENT", status("1.5.0", 1, 1, 1), null, List.of(), false).level())
                .as("hôte muet : pas reproché à la crèche").isEqualTo(Health.Level.OK);
    }

    @Test
    void arreteeProspectOuResilieeGris() throws Exception {
        assertThat(health("CLIENT", status("1.5.0", 1, 1, 1), node("{\"container\":\"exited\"}"), List.of("Serveur muet"), true))
                .isEqualTo(new Health(Health.Level.OFF, List.of("Instance arrêtée")));
        assertThat(health("CLIENT", null, node("{\"container\":\"absent\"}"), List.of(), true).reasons())
                .containsExactly("Instance absente de l’hôte");
        assertThat(health("PROSPECT", null, null, List.of(), true).level()).isEqualTo(Health.Level.OFF);
        assertThat(health("RESILIE", status("1.5.0", 1, 1, 1), null, List.of("Sauvegarde"), true).reasons()).containsExactly("Contrat résilié");
    }

    @Test
    void versions() {
        assertThat(Versions.compare("1.10.0", "1.9.9")).isPositive();
        assertThat(Versions.compare("1.5", "1.5.0")).isZero();
        assertThat(Versions.compare("1.5.0-rc1", "1.5.0")).isZero();
        assertThat(Versions.latest(java.util.Arrays.asList("1.4.2", null, "latest", "1.5.0", "1.10.1"))).isEqualTo("1.10.1");
        assertThat(Versions.readable("99999999999999999999")).isFalse();
    }

    @Test
    void formuleModulesEtDisque() throws Exception {
        assertThat(ParcService.plan(node("{\"plan\":\"Gestion — jusqu’à 24 places\"}"), "ACTIVE")).isEqualTo("Gestion");
        assertThat(ParcService.plan(null, "EVALUATION")).isEqualTo("Évaluation");
        assertThat(ParcService.plan(null, "ACTIVE")).isNull();
        assertThat(ParcService.modules(node("{\"modules\":\"INSCRIPTIONS,FACTURATION,,\"}"))).containsExactly("INSCRIPTIONS", "FACTURATION");
        assertThat(ParcService.modules(node("{\"modules\":[\"REPAS\"]}"))).containsExactly("REPAS");
        assertThat(ParcService.diskPercent(18L, 50L)).isEqualTo(64);
        assertThat(ParcService.diskPercent(18L, null)).isNull();
    }
}
