package nc.ticagou.document;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import nc.ticagou.document.TemplateService.Field;
import nc.ticagou.document.TemplateService.Settings;
import nc.ticagou.document.TemplateService.Subject;
import nc.ticagou.document.TemplateService.TemplateDto;

/** Modèles de documents (lot 6) : modèles versionnés, champs de fusion, PDF, signataire et cachet. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Documents", description = "Modèles de documents : attestations, certificats, contrat de travail")
public class TemplateController {

    private static final String DIRECTION = "hasRole('DIRECTION')";

    public record CreateTemplate(@NotNull Subject subject, @NotNull @Size(max = 120) String title, @NotNull @Size(max = 20000) String body) {
    }

    public record UpdateTemplate(@NotNull @Size(max = 120) String title, @NotNull @Size(max = 20000) String body) {
    }

    public record Signatory(@Size(max = 120) String signatory) {
    }

    private final TemplateService templates;

    public TemplateController(TemplateService templates) {
        this.templates = templates;
    }

    @GetMapping("/document-templates")
    @PreAuthorize(DIRECTION)
    @Operation(summary = "Modèles de la structure, dans leur dernière version")
    public List<TemplateDto> list() {
        return templates.list();
    }

    @GetMapping("/document-templates/fields")
    @PreAuthorize(DIRECTION)
    @Operation(summary = "Champs de fusion disponibles pour un enfant ou un membre du personnel")
    public List<Field> fields(@RequestParam Subject subject) {
        return TemplateService.fields(subject);
    }

    @GetMapping("/document-templates/{id}/versions")
    @PreAuthorize(DIRECTION)
    @Operation(summary = "Versions d'un modèle, la plus récente d'abord")
    public List<TemplateDto> versions(@PathVariable UUID id) {
        return templates.versions(id);
    }

    @PostMapping("/document-templates")
    @PreAuthorize(DIRECTION)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Créer un modèle ; un champ inconnu est refusé")
    public TemplateDto create(@Valid @RequestBody CreateTemplate t) {
        return templates.create(t.subject(), t.title(), t.body());
    }

    @PostMapping("/document-templates/starters")
    @PreAuthorize(DIRECTION)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Créer les modèles de départ (attestation de présence, certificat d'inscription, attestation employeur)")
    public List<TemplateDto> starters() {
        return templates.starters();
    }

    @PutMapping("/document-templates/{id}")
    @PreAuthorize(DIRECTION)
    @Operation(summary = "Modifier : crée une nouvelle version en vigueur dès aujourd'hui")
    public TemplateDto update(@PathVariable UUID id, @Valid @RequestBody UpdateTemplate t) {
        return templates.update(id, t.title(), t.body());
    }

    @DeleteMapping("/document-templates/{id}")
    @PreAuthorize(DIRECTION)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Retirer un modèle (ses versions restent dans l'historique)")
    public void retire(@PathVariable UUID id) {
        templates.retire(id);
    }

    @GetMapping("/document-templates/{id}/pdf")
    @PreAuthorize(DIRECTION)
    @Operation(summary = "Produire le document pour un enfant ou un membre du personnel (PDF)")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id, @RequestParam UUID subjectId) {
        var pdf = templates.render(id, subjectId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(pdf.fileName()).build().toString())
                .body(pdf.content());
    }

    @GetMapping("/document-settings")
    @PreAuthorize(DIRECTION)
    @Operation(summary = "Signataire et cachet des documents")
    public Settings settings() {
        return templates.settings();
    }

    @PutMapping("/document-settings")
    @PreAuthorize(DIRECTION)
    @Operation(summary = "Nom du signataire des documents")
    public Settings signatory(@Valid @RequestBody Signatory s) {
        return templates.updateSignatory(s.signatory());
    }

    @PutMapping(path = "/document-settings/stamp", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(DIRECTION)
    @Operation(summary = "Image de signature et cachet (JPEG ou PNG, 2 Mo au plus)")
    public Settings stamp(@RequestParam("file") MultipartFile file) throws IOException {
        return templates.updateStamp(file.getBytes());
    }

    @DeleteMapping("/document-settings/stamp")
    @PreAuthorize(DIRECTION)
    @Operation(summary = "Retirer l'image de signature et cachet")
    public Settings removeStamp() {
        return templates.updateStamp(null);
    }
}
