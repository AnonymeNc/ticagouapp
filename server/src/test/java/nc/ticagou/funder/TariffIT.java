package nc.ticagou.funder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import nc.ticagou.IntegrationTest;

class TariffIT extends IntegrationTest {

    String director;

    @BeforeEach
    void installer() throws Exception {
        director = install();
    }

    static Map<String, Object> quotientTariff(String validFrom, String validTo) {
        var request = new LinkedHashMap<String, Object>();
        request.put("label", "Barème QF");
        request.put("criterion", "QUOTIENT_FAMILIAL");
        request.put("weeksPerYear", 47);
        request.put("registrationFee", 5_000);
        request.put("validFrom", validFrom);
        request.put("validTo", validTo);
        request.put("bands", List.of(Map.of("lowerBound", 0, "hourlyRate", 280),
                Map.of("lowerBound", 80_001, "hourlyRate", 360)));
        return request;
    }

    @Test
    void deuxBaremesNePeuventPasSeChevaucher() throws Exception {
        createVolumeTariff(director, "2026-01-01");

        var overlap = perform(post("/api/v1/tariff-schedules"), director, quotientTariff("2026-06-01", null));

        assertThat(status(overlap)).isEqualTo(422);
        assertThat(body(overlap).get("code").asText()).isEqualTo("tariff_overlap");
    }

    @Test
    void dupliquerFermeLAncienBareme() throws Exception {
        var id = createVolumeTariff(director, "2026-01-01");

        var copy = perform(post("/api/v1/tariff-schedules/" + id + "/duplicate"), director,
                Map.of("label", "Barème 2027", "validFrom", "2027-01-01"));

        assertThat(status(copy)).isEqualTo(201);
        assertThat(body(copy).get("bands")).hasSize(3);
        var list = body(getAs(director, "/api/v1/tariff-schedules"));
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("label").asText()).isEqualTo("Barème 2027");
        assertThat(list.get(1).get("validTo").asText()).isEqualTo("2026-12-31");
        assertThat(auditCount("TARIFF_CREATED")).isEqualTo(2);
    }

    @Test
    void trancheZeroObligatoire() throws Exception {
        var request = quotientTariff("2026-01-01", null);
        request.put("bands", List.of(Map.of("lowerBound", 1000, "hourlyRate", 280)));

        var result = perform(post("/api/v1/tariff-schedules"), director, request);

        assertThat(status(result)).isEqualTo(400);
    }

    @Test
    void modificationEtSuppression() throws Exception {
        var id = body(perform(post("/api/v1/tariff-schedules"), director, quotientTariff("2026-01-01", "2026-12-31")))
                .get("id").asText();

        var updated = perform(put("/api/v1/tariff-schedules/" + id), director, quotientTariff("2026-01-01", "2027-06-30"));
        assertThat(status(updated)).isEqualTo(200);
        assertThat(body(updated).get("validTo").asText()).isEqualTo("2027-06-30");

        assertThat(status(perform(delete("/api/v1/tariff-schedules/" + id), director, null))).isEqualTo(204);
        assertThat(body(getAs(director, "/api/v1/tariff-schedules"))).isEmpty();
    }

    @Test
    void comptabiliteGereLesBaremesMaisPasLEducatrice() throws Exception {
        createUser(director, "COMPTABILITE", "compta", "Mot-de-passe-compta", null);
        createUser(director, "EDUCATRICE", "educ", "Mot-de-passe-educ", null);
        var compta = accessToken("compta", "Mot-de-passe-compta");
        var educ = accessToken("educ", "Mot-de-passe-educ");

        assertThat(status(perform(post("/api/v1/tariff-schedules"), compta, quotientTariff("2026-01-01", null))))
                .isEqualTo(201);
        assertThat(status(getAs(educ, "/api/v1/tariff-schedules"))).isEqualTo(403);
    }
}
