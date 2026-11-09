package nc.ticagou.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import nc.ticagou.IntegrationTest;

/** Modèles de documents (lot 6) : champs vérifiés, versions, PDF fusionné avec signataire et cachet. */
class TemplateIT extends IntegrationTest {

    private String director;

    @BeforeEach
    void setUp() throws Exception {
        director = install();
    }

    private static String text(byte[] pdf) throws Exception {
        try (var doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void modelesVersionnesEtDocumentsFusionnes() throws Exception {
        var starters = body(perform(post("/api/v1/document-templates/starters"), director, null));
        assertThat(starters).hasSize(3);
        assertThat(body(perform(post("/api/v1/document-templates/starters"), director, null)).get("code").asText())
                .isEqualTo("templates_exist");
        assertThat(body(perform(post("/api/v1/document-templates"), director, Map.of("subject", "ENFANT", "title", "Essai",
                "body", "Âge : {{enfant.age}}"))).get("code").asText()).isEqualTo("unknown_field");

        var attestation = java.util.stream.StreamSupport.stream(starters.spliterator(), false)
                .filter(t -> t.get("title").asText().equals("Attestation de présence")).findFirst().orElseThrow();
        var id = attestation.get("id").asText();
        var updated = body(perform(put("/api/v1/document-templates/" + id), director, Map.of("title", "Attestation de présence",
                "body", "# Attestation de présence\n\n{{enfant.prenom}} {{enfant.nom}} est accueilli(e) section {{enfant.section}}.\n\nContrat : {{contrat.numero}}")));
        assertThat(updated.get("version").asInt()).isEqualTo(2);
        assertThat(body(getAs(director, "/api/v1/document-templates/" + id + "/versions"))).hasSize(2);

        perform(put("/api/v1/document-settings"), director, Map.of("signatory", "Nathalie Kaloï, directrice"));
        var image = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        var png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        var stamp = mvc.perform(multipart("/api/v1/document-settings/stamp").file(new MockMultipartFile("file", "cachet.png",
                MediaType.IMAGE_PNG_VALUE, png.toByteArray())).with(r -> { r.setMethod("PUT"); return r; })
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + director)).andReturn();
        assertThat(status(stamp)).as(stamp.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(getAs(director, "/api/v1/document-settings")).get("hasStamp").asBoolean()).isTrue();

        var child = createChild(director, "Léa", "Wamytan", "2025-01-15", sectionId(director, "Moyens"));
        var pdf = getAs(director, "/api/v1/document-templates/" + id + "/pdf?subjectId=" + child);
        assertThat(status(pdf)).as(pdf.getResponse().getContentAsString()).isEqualTo(200);
        var content = text(pdf.getResponse().getContentAsByteArray());
        assertThat(content).contains("Léa Wamytan est accueilli(e) section Moyens.").contains("Contrat : __________")
                .contains("Nathalie Kaloï, directrice").contains("version 2");
        assertThat(auditCount("DOCUMENT_GENERATED")).isEqualTo(1);

        var employer = java.util.stream.StreamSupport.stream(starters.spliterator(), false)
                .filter(t -> t.get("subject").asText().equals("PERSONNEL")).findFirst().orElseThrow();
        var me = body(getAs(director, "/api/v1/auth/me")).get("id").asText();
        var staffPdf = getAs(director, "/api/v1/document-templates/" + employer.get("id").asText() + "/pdf?subjectId=" + me);
        assertThat(text(staffPdf.getResponse().getContentAsByteArray())).contains("Attestation employeur");

        createUser(director, "EDUCATRICE", "lina.wema", "Tamanou-2026!", null);
        assertThat(status(getAs(accessToken("lina.wema", "Tamanou-2026!"), "/api/v1/document-templates"))).isEqualTo(403);
    }
}
