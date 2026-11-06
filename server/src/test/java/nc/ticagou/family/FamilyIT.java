package nc.ticagou.family;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import nc.ticagou.IntegrationTest;

class FamilyIT extends IntegrationTest {

    static final byte[] PDF = "%PDF-1.7 attestation".getBytes(StandardCharsets.UTF_8);

    String token;
    String noa;
    String familyId;

    @BeforeEach
    void installer() throws Exception {
        token = install();
        noa = createChild(token, "Noa", "Wahéo", "2024-07-22", sectionId(token, "Moyens"));
        familyId = body(getAs(token, "/api/v1/children/" + noa)).at("/family/id").asText();
        perform(multipart("/api/v1/children/" + noa + "/documents")
                .file(new MockMultipartFile("file", "assurance.pdf", "application/pdf", PDF))
                .param("name", "Assurance RC").param("category", "FAMILLE"), token, null);
    }

    private void leave(String childId) throws Exception {
        perform(put("/api/v1/children/" + childId), token, Map.of("firstName", "Noa", "lastName", "Wahéo",
                "birthDate", "2024-07-22", "entryDate", "2025-02-01", "exitDate", "2025-07-31"));
    }

    @Test
    void responsablesDuFoyer() throws Exception {
        var kevin = Map.of("firstName", "Kévin", "lastName", "Wahéo", "relationship", "Père",
                "legalGuardian", true, "payer", false, "mobilePhone", "79 45 12", "displayOrder", 1);
        var created = body(perform(post("/api/v1/families/" + familyId + "/guardians"), token, kevin));
        assertThat(body(getAs(token, "/api/v1/children/" + noa)).at("/family/guardians")).hasSize(2);

        var sarahId = body(getAs(token, "/api/v1/children/" + noa)).at("/family/guardians/0/id").asText();
        assertThat(status(perform(delete("/api/v1/guardians/" + sarahId), token, null))).isEqualTo(204);

        // Le dernier responsable légal ne peut pas être retiré.
        var last = perform(delete("/api/v1/guardians/" + created.get("id").asText()), token, null);
        assertThat(status(last)).isEqualTo(422);
        assertThat(body(last).get("code").asText()).isEqualTo("last_legal_guardian");

        var badEmail = Map.of("firstName", "A", "lastName", "B", "relationship", "Tante", "email", "pas-un-mail");
        assertThat(status(perform(post("/api/v1/families/" + familyId + "/guardians"), token, badEmail)))
                .isEqualTo(400);
    }

    @Test
    void rechercheDeFamille() throws Exception {
        assertThat(body(getAs(token, "/api/v1/families?q=sarah"))).hasSize(1);
        assertThat(body(getAs(token, "/api/v1/families?q=waheo")).get(0).get("label").asText())
                .isEqualTo("Famille Wahéo");
        assertThat(body(getAs(token, "/api/v1/families?q=inconnu"))).isEmpty();
    }

    @Test
    void exportCompletLisibleEtJournalise() throws Exception {
        var result = getAs(token, "/api/v1/families/" + familyId + "/export");

        assertThat(status(result)).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Content-Disposition")).contains("export-famille-waheo-");
        var entries = new ArrayList<String>();
        String json = null;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.add(entry.getName());
                var content = zip.readAllBytes();
                if (entry.getName().equals("portail.json")) {
                    assertThat(new String(content, StandardCharsets.UTF_8)).contains("\"transmissions\"", "\"messages\"");
                } else if (entry.getName().equals("famille.json")) {
                    json = new String(content, StandardCharsets.UTF_8);
                } else {
                    assertThat(content).isEqualTo(PDF);
                }
            }
        }
        assertThat(entries).containsExactly("famille.json", "portail.json", "ENF-2025-001/documents/01-assurance.pdf");
        assertThat(json).contains("\"label\" : \"Famille Wahéo\"", "\"firstName\" : \"Noa\"");
        assertThat(childAccessCount(noa, "EXPORT")).isEqualTo(1);
        assertThat(auditCount("FAMILY_EXPORTED")).isEqualTo(1);
    }

    @Test
    void purgeRefuseeTantQueLEnfantEstInscrit() throws Exception {
        var result = perform(post("/api/v1/families/" + familyId + "/purge"), token,
                Map.of("confirmation", "Famille Wahéo"));

        assertThat(status(result)).isEqualTo(422);
        assertThat(body(result).get("code").asText()).isEqualTo("family_active");
    }

    @Test
    void purgeExigeLeNomExactDeLaFamille() throws Exception {
        leave(noa);

        var result = perform(post("/api/v1/families/" + familyId + "/purge"), token, Map.of("confirmation", "oui"));

        assertThat(status(result)).isEqualTo(422);
        assertThat(body(result).get("code").asText()).isEqualTo("confirmation_mismatch");
        assertThat(body(result).get("detail").asText()).contains("Famille Wahéo");
    }

    @Test
    void purgeSupprimeToutSaufLesTracesAnonymes() throws Exception {
        perform(put("/api/v1/children/" + noa + "/health"), token, Map.of("diet", "Sans porc",
                "allergies", List.of(Map.of("allergen", "Arachide", "severity", "SEVERE")), "vaccinations", List.of()));
        perform(post("/api/v1/children/" + noa + "/authorized-persons"), token, Map.of("firstName", "Joseph",
                "lastName", "Poédi", "relationship", "Oncle", "idVerified", true));
        var path = jdbc.queryForObject("SELECT storage_path FROM stored_file", String.class);
        leave(noa);

        var result = perform(post("/api/v1/families/" + familyId + "/purge"), token,
                Map.of("confirmation", " famille wahéo "));

        assertThat(status(result)).isEqualTo(204);
        for (var table : List.of("family", "guardian", "child", "authorized_person", "child_document",
                "health_info", "allergy", "stored_file")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).as(table).isZero();
        }
        assertThat(Files.exists(STORAGE.resolve(path))).isFalse();
        assertThat(status(getAs(token, "/api/v1/children/" + noa))).isEqualTo(404);

        var details = jdbc.queryForObject(
                "SELECT details::text FROM audit_event WHERE action = 'FAMILY_PURGED'", String.class);
        assertThat(details).contains("\"children\": 1", "\"documents\": 1").doesNotContain("Wahéo");
        // Les consultations passées restent tracées (identifiant de l'enfant seulement).
        assertThat(childAccessCount(noa, "FICHE")).isPositive();
    }
}
