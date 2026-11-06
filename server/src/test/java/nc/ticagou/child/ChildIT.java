package nc.ticagou.child;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;

import nc.ticagou.IntegrationTest;

class ChildIT extends IntegrationTest {

    static final byte[] PAI_PDF = "%PDF-1.7 PAI Noa : arachide, adrénaline".getBytes(StandardCharsets.UTF_8);

    String token;
    String moyens;

    @BeforeEach
    void installer() throws Exception {
        token = install();
        moyens = sectionId(token, "Moyens");
    }

    static Map<String, Object> health(boolean pai) {
        var health = new LinkedHashMap<String, Object>();
        health.put("diet", "Sans porc");
        health.put("dietDetails", "Plateau repas identifié par une étiquette.");
        health.put("paiActive", pai);
        health.put("paiReason", "Allergie alimentaire sévère");
        health.put("paiValidFrom", "2026-08-28");
        health.put("paiValidUntil", "2027-08-31");
        health.put("paiKitLocation", "Pharmacie section Moyens");
        health.put("emergencyProtocol", "Suivre le protocole du PAI, appeler le 15");
        health.put("doctorName", "Dr Hélène Tavernier");
        health.put("instructions", "Aucun aliment apporté par une autre famille.");
        health.put("vaccinationCheckedOn", "2026-09-01");
        health.put("allergies", List.of(
                Map.of("allergen", "Arachide", "reaction", "Gonflement du visage", "severity", "SEVERE"),
                Map.of("allergen", "Fruits à coque", "severity", "SEVERE")));
        health.put("vaccinations", List.of(Map.of("vaccine", "ROR (2e dose)", "lastDoseOn", "2025-11-24")));
        return health;
    }

    @Test
    void creeUnEnfantAvecSaFamilleEtUnNumeroDeDossier() throws Exception {
        var noa = createChild(token, "Noa", "Wahéo", "2024-07-22", moyens);

        var record = body(getAs(token, "/api/v1/children/" + noa));
        assertThat(record.get("fileNumber").asText()).isEqualTo("ENF-2025-001");
        assertThat(record.get("sectionId").asText()).isEqualTo(moyens);
        assertThat(record.at("/family/label").asText()).isEqualTo("Famille Wahéo");
        assertThat(record.at("/family/guardians/0/payer").asBoolean()).isTrue();
        assertThat(record.at("/family/children")).hasSize(1);
        assertThat(auditCount("CHILD_CREATED")).isEqualTo(1);

        // Un frère rattaché au même foyer reçoit le numéro suivant.
        var familyId = record.at("/family/id").asText();
        var child = Map.of("firstName", "Eva", "lastName", "Wahéo", "birthDate", "2025-03-10",
                "entryDate", "2025-09-01");
        var eva = body(perform(post("/api/v1/children"), token, Map.of("child", child, "familyId", familyId)))
                .get("id").asText();
        assertThat(body(getAs(token, "/api/v1/children/" + eva)).get("fileNumber").asText()).isEqualTo("ENF-2025-002");
        assertThat(body(getAs(token, "/api/v1/children/" + eva)).at("/family/children")).hasSize(2);
    }

    @Test
    void chaqueConsultationDeFicheEstJournalisee() throws Exception {
        var noa = createChild(token, "Noa", "Wahéo", "2024-07-22", moyens);

        getAs(token, "/api/v1/children/" + noa);
        getAs(token, "/api/v1/children/" + noa);

        assertThat(childAccessCount(noa, "FICHE")).isEqualTo(2);
        var log = body(getAs(token, "/api/v1/audit/child-access?childId=" + noa));
        assertThat(log.at("/items/0/userName").asText()).isEqualTo("Nathalie Kaloï");
    }

    @Test
    void laListeAfficheToujoursLesInformationsDeSanteCritiques() throws Exception {
        var noa = createChild(token, "Noa", "Wahéo", "2024-07-22", moyens);
        createChild(token, "Kaïna", "Wamytan", "2023-05-02", sectionId(token, "Grands"));
        assertThat(status(perform(put("/api/v1/children/" + noa + "/health"), token, health(true)))).isEqualTo(200);

        var all = body(getAs(token, "/api/v1/children"));
        assertThat(all).hasSize(2);
        var noaItem = all.get(0); // tri par nom : Wahéo avant Wamytan
        assertThat(noaItem.get("firstName").asText()).isEqualTo("Noa");
        assertThat(noaItem.at("/health/allergies/0/allergen").asText()).isEqualTo("Arachide");
        assertThat(noaItem.at("/health/allergies/0/severity").asText()).isEqualTo("SEVERE");
        assertThat(noaItem.at("/health/pai").asBoolean()).isTrue();
        assertThat(noaItem.at("/health/diet").asText()).isEqualTo("Sans porc");

        assertThat(body(getAs(token, "/api/v1/children?q=kaina"))).hasSize(1);
        assertThat(body(getAs(token, "/api/v1/children?withAllergy=true"))).hasSize(1);
        assertThat(body(getAs(token, "/api/v1/children?sectionId=" + moyens))).hasSize(1);
        assertThat(auditCount("CHILD_HEALTH_UPDATED")).isEqualTo(1);
    }

    @Test
    void lesEnfantsSortisSontMasquesParDefaut() throws Exception {
        var noa = createChild(token, "Noa", "Wahéo", "2024-07-22", moyens);
        var identity = new HashMap<String, Object>(Map.of("firstName", "Noa", "lastName", "Wahéo",
                "birthDate", "2024-07-22", "entryDate", "2025-02-01", "exitDate", "2025-07-31"));
        assertThat(status(perform(put("/api/v1/children/" + noa), token, identity))).isEqualTo(204);

        assertThat(body(getAs(token, "/api/v1/children"))).isEmpty();
        assertThat(body(getAs(token, "/api/v1/children?includeExited=true"))).hasSize(1);
    }

    @Test
    void controleLesDatesEtLaSection() throws Exception {
        var noa = createChild(token, "Noa", "Wahéo", "2024-07-22", moyens);

        var exitBeforeEntry = perform(put("/api/v1/children/" + noa), token, Map.of("firstName", "Noa",
                "lastName", "Wahéo", "birthDate", "2024-07-22", "entryDate", "2025-02-01", "exitDate", "2025-01-01"));
        assertThat(status(exitBeforeEntry)).isEqualTo(400);
        assertThat(body(exitBeforeEntry).at("/errors/exitAfterEntry").asText()).contains("sortie");

        var future = perform(put("/api/v1/children/" + noa), token, Map.of("firstName", "Noa", "lastName", "Wahéo",
                "birthDate", LocalDate.now().plusYears(1).toString()));
        assertThat(status(future)).isEqualTo(400);

        var unknownSection = perform(put("/api/v1/children/" + noa), token, Map.of("firstName", "Noa",
                "lastName", "Wahéo", "birthDate", "2024-07-22", "sectionId", "00000000-0000-0000-0000-000000000000"));
        assertThat(status(unknownSection)).isEqualTo(422);
    }

    @Test
    void personneAutoriseeAvecVerificationDeLaPiece() throws Exception {
        var noa = createChild(token, "Noa", "Wahéo", "2024-07-22", moyens);
        var request = new HashMap<String, Object>(Map.of("firstName", "Marie-Claire", "lastName", "Wahéo",
                "relationship", "Grand-mère paternelle", "phone", "76 22 41", "idVerified", false));

        var created = body(perform(post("/api/v1/children/" + noa + "/authorized-persons"), token, request));
        assertThat(created.get("idVerifiedOn").isNull()).isTrue();

        request.put("idVerified", true);
        var verified = body(perform(put("/api/v1/authorized-persons/" + created.get("id").asText()), token, request));
        assertThat(verified.get("idVerifiedOn").asText()).isNotBlank();
        assertThat(verified.get("idVerifiedBy").asText()).isEqualTo("Nathalie Kaloï");

        var record = body(getAs(token, "/api/v1/children/" + noa));
        assertThat(record.get("authorizedPersons")).hasSize(1);

        assertThat(status(perform(delete("/api/v1/authorized-persons/" + created.get("id").asText()), token, null)))
                .isEqualTo(204);
        assertThat(body(getAs(token, "/api/v1/children/" + noa)).get("authorizedPersons")).isEmpty();
    }

    @Test
    void autorisationsParentales() throws Exception {
        var noa = createChild(token, "Noa", "Wahéo", "2024-07-22", moyens);
        var consents = Map.of("consents", List.of(
                Map.of("type", "PHOTOS", "granted", true, "details", "Usage interne uniquement",
                        "signedOn", "2025-02-01", "signedBy", "Sarah et Kévin Wahéo"),
                Map.of("type", "SOINS_URGENCE", "granted", true, "signedOn", "2026-08-28")));

        var saved = body(perform(put("/api/v1/children/" + noa + "/consents"), token, consents));

        assertThat(saved).hasSize(2);
        var twice = Map.of("consents", List.of(Map.of("type", "PHOTOS", "granted", true),
                Map.of("type", "PHOTOS", "granted", false)));
        assertThat(status(perform(put("/api/v1/children/" + noa + "/consents"), token, twice))).isEqualTo(400);
    }

    @Test
    void documentChiffreSurDisqueTelechargementJournalise() throws Exception {
        var noa = createChild(token, "Noa", "Wahéo", "2024-07-22", moyens);
        var upload = multipart("/api/v1/children/" + noa + "/documents")
                .file(new MockMultipartFile("file", "PAI_Noa_2026.pdf", "application/pdf", PAI_PDF))
                .param("name", "PAI 2026-2027").param("category", "SANTE").param("validUntil", "2027-08-31");

        var document = body(perform(upload, token, null));
        assertThat(document.get("contentType").asText()).isEqualTo("application/pdf");
        assertThat(document.get("fileName").asText()).isEqualTo("PAI_Noa_2026.pdf");

        // Sur disque : chiffré, sans nom d'origine.
        var path = jdbc.queryForObject("SELECT storage_path FROM stored_file", String.class);
        var raw = new String(Files.readAllBytes(STORAGE.resolve(path)), StandardCharsets.ISO_8859_1);
        assertThat(raw).startsWith("TCG1").doesNotContain("arachide");
        assertThat(path).doesNotContain("PAI");

        // Le PAI pointe vers ce document.
        var withPai = health(true);
        withPai.put("paiDocumentId", document.get("id").asText());
        assertThat(status(perform(put("/api/v1/children/" + noa + "/health"), token, withPai))).isEqualTo(200);

        var download = getAs(token, "/api/v1/documents/" + document.get("id").asText() + "/content");
        assertThat(status(download)).isEqualTo(200);
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(PAI_PDF);
        assertThat(download.getResponse().getHeader("Content-Disposition")).contains("PAI_Noa_2026.pdf");
        assertThat(childAccessCount(noa, "DOCUMENTS")).isEqualTo(1);

        assertThat(status(perform(delete("/api/v1/documents/" + document.get("id").asText()), token, null)))
                .isEqualTo(204);
        assertThat(Files.exists(STORAGE.resolve(path))).isFalse();
        assertThat(body(getAs(token, "/api/v1/children/" + noa)).at("/health/paiDocumentId").isNull()).isTrue();
    }

    @Test
    void refuseUnFichierQuiNEstPasCeQuIlPretendEtre() throws Exception {
        var noa = createChild(token, "Noa", "Wahéo", "2024-07-22", moyens);
        var upload = multipart("/api/v1/children/" + noa + "/documents")
                .file(new MockMultipartFile("file", "faux.pdf", "application/pdf", "<html>".getBytes()))
                .param("name", "Faux").param("category", "AUTRE");

        var result = perform(upload, token, null);

        assertThat(status(result)).isEqualTo(422);
        assertThat(body(result).get("code").asText()).isEqualTo("document_type");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stored_file", Integer.class)).isZero();
    }

    @Test
    void photoDeLEnfant() throws Exception {
        var noa = createChild(token, "Noa", "Wahéo", "2024-07-22", moyens);
        var jpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
        var upload = multipart(HttpMethod.PUT, "/api/v1/children/" + noa + "/photo")
                .file(new MockMultipartFile("file", "noa.jpg", "image/jpeg", jpeg));

        assertThat(status(perform(upload, token, null))).isEqualTo(204);

        var photo = getAs(token, "/api/v1/children/" + noa + "/photo");
        assertThat(photo.getResponse().getContentAsByteArray()).isEqualTo(jpeg);
        assertThat(body(getAs(token, "/api/v1/children")).get(0).get("hasPhoto").asBoolean()).isTrue();

        // Une nouvelle photo remplace (et efface) l'ancienne.
        assertThat(status(perform(upload, token, null))).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stored_file", Integer.class)).isEqualTo(1);
    }

    @Test
    void uneSectionAvecDesEnfantsNePeutPasEtreSupprimee() throws Exception {
        createChild(token, "Noa", "Wahéo", "2024-07-22", moyens);

        var result = perform(delete("/api/v1/sections/" + moyens), token, null);

        assertThat(status(result)).isEqualTo(422);
        assertThat(body(result).get("code").asText()).isEqualTo("section_in_use");
    }
}
