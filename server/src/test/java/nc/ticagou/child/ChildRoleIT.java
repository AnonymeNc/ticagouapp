package nc.ticagou.child;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import nc.ticagou.IntegrationTest;
import nc.ticagou.auth.Role;

/**
 * Droits du module Enfants (maquette Paramètres › Droits par rôle, ligne « Enfants, santé et PAI ») :
 * Direction modifie, Éducatrice consulte, Cuisine voit allergies et régimes, Comptabilité aucun accès.
 */
class ChildRoleIT extends IntegrationTest {

    final Map<Role, String> tokens = new EnumMap<>(Role.class);
    String noa;
    String documentId;
    String familyId;

    @BeforeEach
    void preparer() throws Exception {
        tokens.put(Role.DIRECTION, install());
        for (var role : List.of(Role.EDUCATRICE, Role.CUISINE, Role.COMPTABILITE)) {
            var login = role.name().toLowerCase();
            createUser(tokens.get(Role.DIRECTION), role.name(), login, "Mot-de-passe-" + login, null);
            tokens.put(role, accessToken(login, "Mot-de-passe-" + login));
        }
        var director = tokens.get(Role.DIRECTION);
        noa = createChild(director, "Noa", "Wahéo", "2024-07-22", sectionId(director, "Moyens"));
        perform(put("/api/v1/children/" + noa + "/health"), director, Map.of("diet", "Sans porc",
                "allergies", List.of(Map.of("allergen", "Arachide", "severity", "SEVERE")), "vaccinations", List.of()));
        documentId = body(perform(multipart("/api/v1/children/" + noa + "/documents")
                .file(new MockMultipartFile("file", "pai.pdf", "application/pdf",
                        "%PDF-1.7".getBytes(StandardCharsets.UTF_8)))
                .param("name", "PAI").param("category", "SANTE"), director, null)).get("id").asText();
        familyId = body(getAs(director, "/api/v1/children/" + noa)).at("/family/id").asText();
    }

    /** Une requête neuve par rôle : un constructeur MockMvc cumule les en-têtes d’un appel à l’autre. */
    private void expect(Supplier<MockHttpServletRequestBuilder> request, Object payload, int direction,
                        int educatrice, int cuisine, int comptabilite) throws Exception {
        var expected = Map.of(Role.DIRECTION, direction, Role.EDUCATRICE, educatrice, Role.CUISINE, cuisine,
                Role.COMPTABILITE, comptabilite);
        for (var role : List.of(Role.EDUCATRICE, Role.CUISINE, Role.COMPTABILITE, Role.DIRECTION)) {
            var builder = request.get();
            var result = perform(builder, tokens.get(role), payload);
            assertThat(status(result)).as("%s en tant que %s", result.getRequest().getRequestURI(), role)
                    .isEqualTo(expected.get(role));
        }
    }

    @Test
    void listeAvecSanteCritiquePourLaCuisineMaisPasLaComptabilite() throws Exception {
        expect(() -> get("/api/v1/children"), null, 200, 200, 200, 403);

        var forKitchen = body(getAs(tokens.get(Role.CUISINE), "/api/v1/children"));
        assertThat(forKitchen.at("/0/health/allergies/0/allergen").asText()).isEqualTo("Arachide");
        assertThat(forKitchen.at("/0/health/diet").asText()).isEqualTo("Sans porc");
    }

    @Test
    void ficheEtDocumentsConsultablesParLEducatriceSeulement() throws Exception {
        expect(() -> get("/api/v1/children/" + noa), null, 200, 200, 403, 403);
        expect(() -> get("/api/v1/documents/" + documentId + "/content"), null, 200, 200, 403, 403);
        expect(() -> get("/api/v1/children/" + noa + "/photo"), null, 404, 404, 404, 403);
    }

    @Test
    void seuleLaDirectionModifie() throws Exception {
        var identity = Map.of("firstName", "Noa", "lastName", "Wahéo", "birthDate", "2024-07-22");
        expect(() -> put("/api/v1/children/" + noa), identity, 204, 403, 403, 403);
        var emptyHealth = Map.of("allergies", List.of(), "vaccinations", List.of());
        expect(() -> put("/api/v1/children/" + noa + "/health"), emptyHealth, 200, 403, 403, 403);
        expect(() -> post("/api/v1/children/" + noa + "/authorized-persons"), Map.of("firstName", "Joseph",
                "lastName", "Poédi", "relationship", "Oncle", "idVerified", false), 201, 403, 403, 403);
        expect(() -> get("/api/v1/families"), null, 200, 403, 403, 403);
        expect(() -> get("/api/v1/families/" + familyId + "/export"), null, 200, 403, 403, 403);
        expect(() -> post("/api/v1/families/" + familyId + "/purge"), Map.of("confirmation", "x"),
                422, 403, 403, 403);
    }

    @Test
    void lesConsultationsRefuseesNeSontPasDesConsultations() throws Exception {
        perform(get("/api/v1/children/" + noa), tokens.get(Role.CUISINE), null);

        assertThat(childAccessCount(noa, "FICHE")).isEqualTo(1); // celle de la préparation
        assertThat(auditCount("ACCESS_DENIED")).isEqualTo(1);
    }
}
