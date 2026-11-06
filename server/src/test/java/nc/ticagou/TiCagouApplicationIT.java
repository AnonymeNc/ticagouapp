package nc.ticagou;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import jakarta.validation.ValidatorFactory;

class TiCagouApplicationIT extends IntegrationTest {

    @Autowired
    private ValidatorFactory validators;

    @Test
    void demarreAvecPostgresEtFlyway() {
        Integer migrations = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class);
        assertThat(migrations).isGreaterThanOrEqualTo(2);
    }

    @Test
    void santeRepondUpAvecLaBase() throws Exception {
        var result = getAs(null, "/api/v1/health");

        assertThat(status(result)).isEqualTo(200);
        var health = body(result);
        assertThat(health.get("status").asText()).isEqualTo("UP");
        assertThat(health.get("database").asText()).isEqualTo("UP");
        assertThat(health.get("zone").asText()).isEqualTo("Pacific/Noumea");
    }

    @Test
    void laValidationDesDatesSuitLeFuseauDeNoumea() {
        assertThat(validators.getClockProvider().getClock().getZone().getId()).isEqualTo("Pacific/Noumea");
    }

    @Test
    void documentationOpenApiExposee() throws Exception {
        var result = getAs(null, "/v3/api-docs");

        assertThat(status(result)).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .contains("/api/v1/health", "/api/v1/auth/login", "/api/v1/setup");
    }
}
