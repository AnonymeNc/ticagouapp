package nc.ticagou.structure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;

import nc.ticagou.IntegrationTest;

class StructureIT extends IntegrationTest {

    String token;

    @BeforeEach
    void installer() throws Exception {
        token = install();
    }

    private static Map<String, Object> update(int capacity, String ridet) {
        var identity = new java.util.HashMap<String, Object>(Map.of("name", "Les P’tits Niaoulis", "city", "Dumbéa",
                "agrementNumber", "AGR-2024-017", "agrementDate", "2024-01-15", "capacity", capacity));
        identity.put("ridet", ridet);
        return Map.of(
                "identity", identity,
                "invoiceFooter", "Paiement à réception par virement.",
                "openingHours", List.of(
                        Map.of("dayOfWeek", 1, "opensAt", "06:30", "closesAt", "17:30"),
                        Map.of("dayOfWeek", 5, "opensAt", "06:30", "closesAt", "17:00")));
    }

    @Test
    void modifieLIdentiteLesHorairesEtJournalise() throws Exception {
        var result = perform(put("/api/v1/structure"), token, update(50, "7654321.002"));

        assertThat(status(result)).isEqualTo(200);
        var structure = body(result);
        assertThat(structure.get("capacity").asInt()).isEqualTo(50);
        assertThat(structure.get("ridet").asText()).isEqualTo("7654321.002");
        assertThat(structure.get("invoiceFooter").asText()).startsWith("Paiement");
        assertThat(structure.get("openingHours")).hasSize(2);
        assertThat(structure.at("/openingHours/1/closesAt").asText()).isEqualTo("17:00:00");
        assertThat(auditCount("STRUCTURE_UPDATED")).isEqualTo(1);
    }

    @Test
    void laCapaciteNePeutPasDescendreSousLeTotalDesSections() throws Exception {
        var result = perform(put("/api/v1/structure"), token, update(40, null));

        assertThat(status(result)).isEqualTo(422);
        assertThat(body(result).get("code").asText()).isEqualTo("capacity_exceeded");
        assertThat(body(result).get("detail").asText()).contains("48", "40");
    }

    @Test
    void ridetInvalideRefuse() throws Exception {
        var result = perform(put("/api/v1/structure"), token, update(50, "123"));

        assertThat(status(result)).isEqualTo(400);
        assertThat(body(result).at("/errors/identity.ridet").asText()).contains("7 chiffres");
    }

    @Test
    void horairesIncoherentsRefuses() throws Exception {
        var request = Map.of("identity", Map.of("name", "Crèche", "capacity", 48), "openingHours",
                List.of(Map.of("dayOfWeek", 1, "opensAt", "17:00", "closesAt", "07:00")));

        assertThat(status(perform(put("/api/v1/structure"), token, request))).isEqualTo(400);
    }

    @Test
    void sectionsSansChevauchementNiDepassement() throws Exception {
        var overlap = perform(post("/api/v1/sections"), token, section("Passerelle", 24, 36, 4, "#0b6e72", 3));
        assertThat(status(overlap)).isEqualTo(422);
        assertThat(body(overlap).get("code").asText()).isEqualTo("section_overlap");

        // Grands n'a pas d'âge de sortie : on le borne pour ajouter une section après.
        var sections = body(getAs(token, "/api/v1/sections"));
        var grandsId = sections.get(2).get("id").asText();
        assertThat(status(perform(put("/api/v1/sections/" + grandsId), token,
                section("Grands", 30, 48, 20, "#3d479b", 2)))).isEqualTo(200);

        var tooMany = perform(post("/api/v1/sections"), token, section("Passerelle", 48, 60, 1, "#0b6e72", 3));
        assertThat(status(tooMany)).isEqualTo(422);
        assertThat(body(tooMany).get("code").asText()).isEqualTo("capacity_exceeded");

        var duplicate = perform(post("/api/v1/sections"), token, section("moyens", 48, 60, 0, "#0b6e72", 3));
        assertThat(status(duplicate)).isEqualTo(400);

        var badColor = perform(post("/api/v1/sections"), token, section("Passerelle", 48, 60, 1, "teal", 3));
        assertThat(status(badColor)).isEqualTo(400);
        assertThat(body(badColor).at("/errors/color").asText()).contains("#rrggbb");
    }

    @Test
    void supprimeUneSection() throws Exception {
        var moyensId = body(getAs(token, "/api/v1/sections")).get(1).get("id").asText();

        assertThat(status(perform(delete("/api/v1/sections/" + moyensId), token, null))).isEqualTo(204);

        assertThat(body(getAs(token, "/api/v1/sections"))).hasSize(2);
        assertThat(auditCount("SECTION_DELETED")).isEqualTo(1);
    }

    @Test
    void logoPngEnregistreEtServiAvecProtections() throws Exception {
        var png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        var upload = multipart(HttpMethod.PUT, "/api/v1/structure/logo")
                .file(new MockMultipartFile("file", "logo.png", "image/png", png));

        assertThat(status(perform(upload, token, null))).isEqualTo(204);

        var logo = getAs(token, "/api/v1/structure/logo");
        assertThat(status(logo)).isEqualTo(200);
        assertThat(logo.getResponse().getContentType()).isEqualTo("image/png");
        assertThat(logo.getResponse().getContentAsByteArray()).isEqualTo(png);
        assertThat(logo.getResponse().getHeader("Content-Security-Policy")).contains("sandbox");
        assertThat(body(getAs(token, "/api/v1/structure")).get("hasLogo").asBoolean()).isTrue();

        assertThat(status(perform(delete("/api/v1/structure/logo"), token, null))).isEqualTo(204);
        assertThat(status(getAs(token, "/api/v1/structure/logo"))).isEqualTo(404);
    }

    @Test
    void logoDeTypeNonImageRefuse() throws Exception {
        var upload = multipart(HttpMethod.PUT, "/api/v1/structure/logo")
                .file(new MockMultipartFile("file", "logo.html", "text/html", "<script>".getBytes()));

        var result = perform(upload, token, null);

        assertThat(status(result)).isEqualTo(422);
        assertThat(body(result).get("code").asText()).isEqualTo("logo_type");
    }
}
