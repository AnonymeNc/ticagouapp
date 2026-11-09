package nc.ticagou.setup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import nc.ticagou.IntegrationTest;

/** Prise en main (lot 9) : premiers pas d'après l'état de la crèche, support visible par toute l'équipe. */
class GettingStartedIT extends IntegrationTest {

    private static boolean done(JsonNode steps, String key) {
        for (var step : steps) {
            if (step.get("key").asText().equals(key)) {
                return step.get("done").asBoolean();
            }
        }
        throw new AssertionError("Étape absente : " + key);
    }

    @Test
    void premiersPasSelonLEtatDeLaCreche() throws Exception {
        var director = install();
        var before = body(getAs(director, "/api/v1/getting-started"));
        assertThat(done(before, "enfants")).isFalse();
        assertThat(done(before, "encadrement")).as("règles posées par l'assistant").isTrue();
        createChild(director, "Noa", "Wahéo", "2024-09-10", sectionId(director, "Moyens"));
        assertThat(done(body(getAs(director, "/api/v1/getting-started")), "enfants")).isTrue();

        createUser(director, "EDUCATRICE", "lina.wema", "Tamanou-2026!", null);
        var educatrice = accessToken("lina.wema", "Tamanou-2026!");
        assertThat(done(body(getAs(director, "/api/v1/getting-started")), "equipe")).isTrue();
        assertThat(status(getAs(educatrice, "/api/v1/getting-started"))).isEqualTo(403);
        assertThat(status(getAs(educatrice, "/api/v1/support"))).isEqualTo(200);
    }
}
