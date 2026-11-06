package nc.ticagou.structure;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
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
import nc.ticagou.structure.StructureDtos.SectionDto;
import nc.ticagou.structure.StructureDtos.SectionRequest;
import nc.ticagou.structure.StructureDtos.StructureDto;
import nc.ticagou.structure.StructureDtos.UpdateStructureRequest;

/** Paramètres › Structure et Sections : lecture pour tous, modification par la direction. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Structure", description = "Identité, agrément, horaires, logo et sections")
public class StructureController {

    private final StructureService service;

    public StructureController(StructureService service) {
        this.service = service;
    }

    @GetMapping("/structure")
    @Operation(summary = "Structure de l'utilisateur connecté")
    public StructureDto structure() {
        return service.current();
    }

    @PutMapping("/structure")
    @PreAuthorize("hasRole('DIRECTION')")
    @Operation(summary = "Modifier l'identité, l'agrément, les horaires et les mentions de facture")
    public StructureDto update(@Valid @RequestBody UpdateStructureRequest request) {
        return service.update(request);
    }

    @GetMapping("/structure/logo")
    @Operation(summary = "Logo de la structure")
    public ResponseEntity<byte[]> logo() {
        var logo = service.logo();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(logo.contentType()))
                .cacheControl(CacheControl.noCache())
                // Un SVG peut contenir du script : il n'est jamais exécuté depuis cette URL.
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox")
                .header("X-Content-Type-Options", "nosniff")
                .body(logo.content());
    }

    @PutMapping(path = "/structure/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('DIRECTION')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remplacer le logo (PNG, JPEG ou SVG, 1 Mo maximum)")
    public void replaceLogo(@RequestParam("file") MultipartFile file) throws IOException {
        service.replaceLogo(file.getBytes(), file.getContentType());
    }

    @DeleteMapping("/structure/logo")
    @PreAuthorize("hasRole('DIRECTION')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Retirer le logo")
    public void removeLogo() {
        service.removeLogo();
    }

    @GetMapping("/sections")
    @Operation(summary = "Sections de la structure, dans l'ordre d'affichage")
    public List<SectionDto> sections() {
        return service.sections();
    }

    @PostMapping("/sections")
    @PreAuthorize("hasRole('DIRECTION')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ajouter une section")
    public SectionDto createSection(@Valid @RequestBody SectionRequest request) {
        return service.createSection(request);
    }

    @PutMapping("/sections/{id}")
    @PreAuthorize("hasRole('DIRECTION')")
    @Operation(summary = "Modifier une section")
    public SectionDto updateSection(@PathVariable UUID id, @Valid @RequestBody SectionRequest request) {
        return service.updateSection(id, request);
    }

    @DeleteMapping("/sections/{id}")
    @PreAuthorize("hasRole('DIRECTION')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer une section")
    public void deleteSection(@PathVariable UUID id) {
        service.deleteSection(id);
    }

    @GetMapping("/sections/overview")
    @PreAuthorize("hasRole('DIRECTION')")
    @Operation(summary = "Sections, inscrits, tranches sans section, règles et passages à proposer")
    public StructureDtos.SectionsOverview sectionsOverview() {
        return service.sectionsOverview();
    }

    @PutMapping("/sections/passage-rules")
    @PreAuthorize("hasRole('DIRECTION')")
    @Operation(summary = "Règles de passage d'une section à la suivante")
    public StructureDtos.SectionsOverview passageRules(@Valid @RequestBody StructureDtos.PassageRulesDto request) {
        return service.updatePassageRules(request);
    }

    @PostMapping("/sections/passages")
    @PreAuthorize("hasRole('DIRECTION')")
    @Operation(summary = "Valider le passage d'un enfant dans une autre section")
    public StructureDtos.SectionsOverview passage(@Valid @RequestBody StructureDtos.PassageRequest request) {
        return service.applyPassage(request);
    }
}
