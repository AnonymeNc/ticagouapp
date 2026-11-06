package nc.ticagou.dataimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;

import nc.ticagou.IntegrationTest;

/** Reprise de bout en bout : modèle, aperçu sans écriture, import, droits. */
class ImportIT extends IntegrationTest {

    private String director;

    @BeforeEach
    void setUp() throws Exception {
        director = install();
    }

    private static Map<String, List<List<Object>>> validSheets() {
        var sheets = new LinkedHashMap<String, List<List<Object>>>();
        sheets.put("Familles", List.of(
                List.of("Réf famille *", "Libellé *", "Ville"),
                List.of("F1", "Famille Gope", "Dumbéa")));
        sheets.put("Responsables", List.of(
                List.of("Réf famille *", "Prénom *", "Nom *", "Lien *", "Responsable légal *", "Payeur *", "Téléphone mobile"),
                List.of("F1", "Marie", "Gope", "Mère", "oui", "oui", 751234),
                Arrays.asList("F1", "Paul", "Gope", "Père", "oui", "non", null)));
        sheets.put("Enfants", List.of(
                List.of("Réf enfant *", "Réf famille *", "Prénom *", "Nom *", "Date de naissance *", "Section", "Date d’entrée"),
                List.of("E1", "F1", "Lila", "Gope", LocalDate.of(2024, 3, 15), "Moyens", "01/02/2025"),
                Arrays.asList("E2", "F1", "Noa", "Gope", "20/08/2025", "bébés", null)));
        sheets.put("Santé", List.of(
                List.of("Réf enfant *", "Régime", "PAI", "Motif du PAI"),
                List.of("E1", "Sans porc", "oui", "Asthme")));
        sheets.put("Allergies", List.of(
                List.of("Réf enfant *", "Allergène *", "Réaction", "Gravité *"),
                List.of("E1", "Arachide", "Œdème", "Sévère")));
        sheets.put("Vaccins", List.of(
                List.of("Réf enfant *", "Vaccin *", "Dernière dose"),
                List.of("E1", "ROR", "10/01/2025")));
        sheets.put("Personnes autorisées", List.of(
                List.of("Réf enfant *", "Prénom *", "Nom *", "Lien *", "Téléphone"),
                List.of("E1", "Tina", "Gope", "Tante", "75 00 00")));
        sheets.put("Autorisations", List.of(
                List.of("Réf enfant *", "Photos", "Sorties", "Soins d’urgence", "Signées le"),
                List.of("E1", "oui", "non", "oui", "01/02/2025")));
        return sheets;
    }

    private MvcResult preview(String token, byte[] content) throws Exception {
        var request = multipart("/api/v1/data-import/preview").file(new MockMultipartFile("file", "reprise.xlsx", null, content));
        return perform(request, token, null);
    }

    private MvcResult commit(String token, byte[] content, String hash, boolean reviewed) throws Exception {
        var request = multipart("/api/v1/data-import/commit").file(new MockMultipartFile("file", "reprise.xlsx", null, content))
                .param("fileHash", hash).param("healthReviewed", String.valueOf(reviewed));
        return perform(request, token, null);
    }

    private JsonNode checked(byte[] content) throws Exception {
        var result = preview(director, content);
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return body(result);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    @Test
    void leModeleVideSeTelechargeEtSeRelit() throws Exception {
        var result = getAs(director, "/api/v1/data-import/template");

        assertThat(status(result)).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Content-Disposition")).contains("reprise-ticagou.xlsx");
        var file = ImportReader.read(result.getResponse().getContentAsByteArray());
        assertThat(file.sheets()).extracting(ImportFile.Sheet::name).containsExactly("Lisez-moi", "Familles", "Responsables",
                "Enfants", "Santé", "Allergies", "Vaccins", "Personnes autorisées", "Autorisations");
        assertThat(file.sheet("Lisez-moi").orElseThrow().rows()).anySatisfy(
                row -> assertThat(row.values().values()).anyMatch(v -> v.contains("Bébés, Moyens, Grands")));

        var preview = checked(result.getResponse().getContentAsByteArray());

        assertThat(preview.get("warnings")).as("le modèle est reconnu colonne par colonne").isEmpty();
        assertThat(preview.get("errors")).hasSize(1);
        assertThat(preview.get("errors").get(0).get("message").asText()).isEqualTo("Aucun enfant dans cette feuille.");
    }

    @Test
    void lApercuMontreCeQuIlFautRelireSansRienEcrire() throws Exception {
        var preview = checked(ImportWorkbooks.workbook(validSheets()));

        assertThat(preview.get("errors")).isEmpty();
        assertThat(preview.get("counts").get("families").asInt()).isEqualTo(1);
        assertThat(preview.get("counts").get("children").asInt()).isEqualTo(2);
        assertThat(preview.get("counts").get("allergies").asInt()).isEqualTo(1);
        assertThat(preview.get("counts").get("pais").asInt()).isEqualTo(1);
        var lila = preview.get("children").get(0);
        assertThat(lila.get("firstName").asText()).isEqualTo("Lila");
        assertThat(lila.get("allergies").get(0).get("severity").asText()).isEqualTo("SEVERE");
        assertThat(lila.get("pai").asBoolean()).isTrue();
        assertThat(lila.get("pickupPersons")).extracting(JsonNode::asText)
                .containsExactly("Marie Gope (Mère)", "Paul Gope (Père)", "Tina Gope (Tante)");
        assertThat(count("child")).isZero();
        assertThat(count("family")).isZero();
    }

    @Test
    void lImportCreeLesFichesCommeUneSaisie() throws Exception {
        var content = ImportWorkbooks.workbook(validSheets());
        var hash = checked(content).get("fileHash").asText();

        var result = commit(director, content, hash, true);

        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(result).get("children").asInt()).isEqualTo(2);
        var children = body(getAs(director, "/api/v1/children"));
        assertThat(children).hasSize(2);
        var lila = java.util.stream.StreamSupport.stream(children.spliterator(), false)
                .filter(c -> c.get("firstName").asText().equals("Lila")).findFirst().orElseThrow();
        assertThat(lila.get("fileNumber").asText()).isEqualTo("ENF-2025-001");
        assertThat(lila.get("sectionId").asText()).isEqualTo(sectionId(director, "Moyens"));

        var record = body(getAs(director, "/api/v1/children/" + lila.get("id").asText()));
        assertThat(record.get("birthDate").asText()).isEqualTo("2024-03-15");
        assertThat(record.get("family").get("guardians")).hasSize(2);
        assertThat(record.get("family").get("guardians").get(0).get("mobilePhone").asText()).isEqualTo("751234");
        assertThat(record.get("family").get("children")).hasSize(2);
        var health = record.get("health");
        assertThat(health.get("diet").asText()).isEqualTo("Sans porc");
        assertThat(health.get("paiActive").asBoolean()).isTrue();
        assertThat(health.get("allergies").get(0).get("allergen").asText()).isEqualTo("Arachide");
        assertThat(health.get("vaccinations").get(0).get("lastDoseOn").asText()).isEqualTo("2025-01-10");
        var person = record.get("authorizedPersons").get(0);
        assertThat(person.get("firstName").asText()).isEqualTo("Tina");
        assertThat(person.get("idVerifiedOn").isNull()).isTrue();
        assertThat(record.get("consents")).hasSize(3);
    }

    @Test
    void lImportEstTraceSansDonneeNominative() throws Exception {
        var content = ImportWorkbooks.workbook(validSheets());
        var hash = checked(content).get("fileHash").asText();

        commit(director, content, hash, true);

        var details = jdbc.queryForObject("SELECT details::text FROM audit_event WHERE action = 'DATA_IMPORTED'", String.class);
        assertThat(details).contains(hash).contains("\"healthReviewed\": true").doesNotContain("Lila", "Arachide");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'CHILD_CREATED'", Long.class)).isEqualTo(2);
    }

    @Test
    void laRelectureEstObligatoire() throws Exception {
        var content = ImportWorkbooks.workbook(validSheets());
        var hash = checked(content).get("fileHash").asText();

        var result = commit(director, content, hash, false);

        assertThat(status(result)).isEqualTo(422);
        assertThat(body(result).get("code").asText()).isEqualTo("import_not_reviewed");
        assertThat(count("child")).isZero();
    }

    @Test
    void unFichierDifferentDeLApercuEstRefuse() throws Exception {
        var previewed = checked(ImportWorkbooks.workbook(validSheets())).get("fileHash").asText();
        var sheets = validSheets();
        sheets.put("Allergies", List.of(List.of("Réf enfant *", "Allergène *", "Réaction", "Gravité *")));

        var result = commit(director, ImportWorkbooks.workbook(sheets), previewed, true);

        assertThat(status(result)).isEqualTo(409);
        assertThat(body(result).get("code").asText()).isEqualTo("import_file_changed");
        assertThat(count("child")).isZero();
    }

    @Test
    void uneSeuleErreurBloqueToutLeFichier() throws Exception {
        var sheets = validSheets();
        sheets.put("Allergies", List.of(
                List.of("Réf enfant *", "Allergène *", "Réaction", "Gravité *"),
                Arrays.asList("E1", "Arachide", null, null)));
        var content = ImportWorkbooks.workbook(sheets);

        var preview = checked(content);

        assertThat(preview.get("errors").get(0).get("sheet").asText()).isEqualTo("Allergies");
        assertThat(preview.get("errors").get(0).get("row").asInt()).isEqualTo(2);
        assertThat(preview.get("children")).isEmpty();
        var result = commit(director, content, preview.get("fileHash").asText(), true);
        assertThat(status(result)).isEqualTo(422);
        assertThat(body(result).get("code").asText()).isEqualTo("import_invalid");
        assertThat(count("child")).isZero();
        assertThat(count("family")).isZero();
    }

    @Test
    void unSecondImportNeCreePasDeDoublons() throws Exception {
        var content = ImportWorkbooks.workbook(validSheets());
        var hash = checked(content).get("fileHash").asText();
        commit(director, content, hash, true);

        var again = commit(director, content, hash, true);

        assertThat(status(again)).isEqualTo(422);
        assertThat(count("child")).isEqualTo(2);
        assertThat(checked(content).get("errors")).hasSize(2);
    }

    @Test
    void seuleLaDirectionReprendLesDonnees() throws Exception {
        var content = ImportWorkbooks.workbook(validSheets());
        for (var role : List.of("EDUCATRICE", "CUISINE", "COMPTABILITE")) {
            var login = role.toLowerCase();
            createUser(director, role, login, "Mot-de-passe-" + login, null);
            var token = accessToken(login, "Mot-de-passe-" + login);

            assertThat(status(getAs(token, "/api/v1/data-import/template"))).as(role).isEqualTo(403);
            assertThat(status(preview(token, content))).as(role).isEqualTo(403);
            assertThat(status(commit(token, content, "x", true))).as(role).isEqualTo(403);
        }
        assertThat(count("child")).isZero();
    }
}
