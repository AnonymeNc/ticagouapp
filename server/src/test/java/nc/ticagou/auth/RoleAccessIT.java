package nc.ticagou.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import nc.ticagou.IntegrationTest;

/**
 * Droits par rôle (maquette Paramètres › Droits par rôle) : seuls les paramètres, les utilisateurs et
 * le journal sont couverts à ce stade ; les autres modules ajouteront leurs lignes.
 */
class RoleAccessIT extends IntegrationTest {

    private static final int OK = 200;
    private static final int CREATED = 201;
    private static final int FORBIDDEN = 403;

    final Map<Role, String> tokens = new EnumMap<>(Role.class);

    @BeforeEach
    void creerUnCompteParRole() throws Exception {
        tokens.put(Role.DIRECTION, install());
        for (var role : List.of(Role.EDUCATRICE, Role.CUISINE, Role.COMPTABILITE)) {
            var login = role.name().toLowerCase();
            createUser(tokens.get(Role.DIRECTION), role.name(), login, "Mot-de-passe-" + login, null);
            tokens.put(role, accessToken(login, "Mot-de-passe-" + login));
        }
        // Place libre après « Grands » pour que chaque rôle autorisé puisse ajouter une section.
        var director = tokens.get(Role.DIRECTION);
        perform(put("/api/v1/structure"), director, payload("PUT /structure", Role.DIRECTION));
        var grandsId = body(getAs(director, "/api/v1/sections")).get(2).get("id").asText();
        perform(put("/api/v1/sections/" + grandsId), director, section("Grands", 30, 48, 20, "#3d479b", 2));
    }

    /** Point d'accès, puis statut attendu pour DIRECTION, EDUCATRICE, CUISINE, COMPTABILITE. */
    static Stream<Arguments> matrice() {
        return Stream.of(
                row("GET /structure", OK, OK, OK, OK),
                row("PUT /structure", OK, FORBIDDEN, FORBIDDEN, FORBIDDEN),
                row("GET /sections", OK, OK, OK, OK),
                row("POST /sections", CREATED, FORBIDDEN, FORBIDDEN, FORBIDDEN),
                row("GET /users", OK, FORBIDDEN, FORBIDDEN, FORBIDDEN),
                row("POST /users", CREATED, FORBIDDEN, FORBIDDEN, FORBIDDEN),
                row("GET /audit/events", OK, FORBIDDEN, FORBIDDEN, FORBIDDEN),
                row("GET /audit/child-access", OK, FORBIDDEN, FORBIDDEN, FORBIDDEN),
                row("GET /auth/me", OK, OK, OK, OK));
    }

    private static Arguments row(String endpoint, int direction, int educatrice, int cuisine, int comptabilite) {
        return Arguments.of(endpoint, Map.of(Role.DIRECTION, direction, Role.EDUCATRICE, educatrice,
                Role.CUISINE, cuisine, Role.COMPTABILITE, comptabilite));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("matrice")
    void droitsParRole(String endpoint, Map<Role, Integer> expected) throws Exception {
        for (var role : Role.values()) {
            var result = perform(request(endpoint), tokens.get(role), payload(endpoint, role));
            assertThat(status(result)).as("%s en tant que %s", endpoint, role).isEqualTo(expected.get(role));
        }
        assertThat(status(perform(request(endpoint), null, payload(endpoint, Role.DIRECTION))))
                .as("%s sans connexion", endpoint).isEqualTo(401);
    }

    @Test
    void lesRefusSontJournalises() throws Exception {
        perform(get("/api/v1/users"), tokens.get(Role.EDUCATRICE), null);

        var details = jdbc.queryForObject(
                "SELECT details::text FROM audit_event WHERE action = 'ACCESS_DENIED'", String.class);
        assertThat(details).contains("GET /api/v1/users", "EDUCATRICE");
    }

    @Test
    void uneStructureNeVoitPasLesDonneesDUneAutre() throws Exception {
        // Seconde structure créée directement en base (le multi-structures n'a pas encore d'écran).
        var otherStructure = UUID.randomUUID();
        jdbc.update("INSERT INTO structure (id, name, capacity) VALUES (?, 'Autre crèche', 20)", otherStructure);
        jdbc.update("""
                INSERT INTO app_user (structure_id, first_name, last_name, role, login, password_hash)
                SELECT ?, 'Directrice', 'Autre', 'DIRECTION', 'autre.direction', password_hash
                FROM app_user WHERE login = ?""", otherStructure, DIRECTOR_LOGIN);
        var otherToken = accessToken("autre.direction", DIRECTOR_PASSWORD);

        var users = body(getAs(otherToken, "/api/v1/users"));
        assertThat(users).hasSize(1);
        assertThat(body(getAs(otherToken, "/api/v1/sections"))).isEmpty();

        var firstStructureUserId = body(getAs(tokens.get(Role.DIRECTION), "/api/v1/users")).get(0).get("id").asText();
        var update = Map.of("firstName", "X", "lastName", "Y", "role", "DIRECTION", "active", true);
        assertThat(status(perform(put("/api/v1/users/" + firstStructureUserId), otherToken, update)))
                .isEqualTo(404);
    }

    private static MockHttpServletRequestBuilder request(String endpoint) {
        var parts = endpoint.split(" ");
        var path = "/api/v1" + parts[1];
        return switch (parts[0]) {
            case "GET" -> get(path);
            case "POST" -> post(path);
            case "PUT" -> put(path);
            default -> throw new IllegalArgumentException(endpoint);
        };
    }

    /** Corps valide et unique par rôle (les créations réussies ne doivent pas entrer en conflit). */
    private static Object payload(String endpoint, Role role) {
        var suffix = role.name().toLowerCase();
        return switch (endpoint) {
            case "PUT /structure" -> Map.of(
                    "identity", Map.of("name", "Les P’tits Niaoulis", "city", "Dumbéa", "capacity", 60),
                    "invoiceFooter", "Paiement à réception.",
                    "openingHours", List.of(Map.of("dayOfWeek", 1, "opensAt", "06:30", "closesAt", "17:30")));
            case "POST /sections" -> section("Passerelle " + suffix, 60 + role.ordinal(), 61 + role.ordinal(), 1,
                    "#0b6e72", 9);
            case "POST /users" -> Map.of("firstName", "Malia", "lastName", "Tauhavili", "role", "EDUCATRICE",
                    "pin", "48" + (31 + role.ordinal()));
            default -> null;
        };
    }
}
