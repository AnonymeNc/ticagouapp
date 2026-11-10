package nc.ticagou.dataimport;

import java.io.IOException;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import nc.ticagou.dataimport.ImportDtos.Counts;
import nc.ticagou.dataimport.ImportDtos.Preview;

/** Reprise des données : direction uniquement (enfants et familles sont dans la base, ADR 0016). */
@RestController
@RequestMapping("/api/v1/data-import")
@Tag(name = "Reprise des données", description = "Modèle Excel, aperçu et import des familles et des enfants")
public class ImportController {

    private static final String EDITORS = "hasRole('DIRECTION')";

    private final ImportService imports;

    public ImportController(ImportService imports) {
        this.imports = imports;
    }

    @GetMapping("/template")
    @PreAuthorize(EDITORS)
    @Operation(summary = "Classeur modèle à remplir")
    public ResponseEntity<byte[]> template() {
        var template = imports.template();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(template.fileName()).build().toString())
                .body(template.content());
    }

    @PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(EDITORS)
    @Operation(summary = "Contrôle d'un fichier de reprise, sans rien écrire")
    public Preview preview(@RequestParam("file") MultipartFile file) throws IOException {
        return imports.preview(file.getBytes());
    }

    @PostMapping(path = "/commit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(EDITORS)
    @Operation(summary = "Import du fichier relu, tout ou rien")
    public Counts commit(@RequestParam("file") MultipartFile file, @RequestParam String fileHash,
                         @RequestParam boolean healthReviewed) throws IOException {
        return imports.commit(file.getBytes(), fileHash, healthReviewed);
    }
}
