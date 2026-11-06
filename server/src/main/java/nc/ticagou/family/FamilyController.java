package nc.ticagou.family;

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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import nc.ticagou.family.FamilyDtos.FamilyRequest;
import nc.ticagou.family.FamilyDtos.FamilySummary;
import nc.ticagou.family.FamilyDtos.GuardianDto;
import nc.ticagou.family.FamilyDtos.GuardianRequest;
import nc.ticagou.family.FamilyDtos.PurgeRequest;

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasRole('DIRECTION')")
@Tag(name = "Familles", description = "Foyers, responsables légaux, export et purge des données (direction)")
public class FamilyController {

    private final FamilyService families;
    private final FamilyDataService data;

    public FamilyController(FamilyService families, FamilyDataService data) {
        this.families = families;
        this.data = data;
    }

    @GetMapping("/families")
    @Operation(summary = "Rechercher une famille (libellé ou nom d'un responsable)")
    public List<FamilySummary> search(@RequestParam(required = false) String q) {
        return families.search(q);
    }

    @PutMapping("/families/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Modifier le foyer (adresse, situation…)")
    public void update(@PathVariable UUID id, @Valid @RequestBody FamilyRequest request) {
        families.update(id, request);
    }

    @PostMapping("/families/{id}/guardians")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ajouter un responsable au foyer")
    public GuardianDto addGuardian(@PathVariable UUID id, @Valid @RequestBody GuardianRequest request) {
        return families.addGuardian(id, request);
    }

    @PutMapping("/guardians/{id}")
    @Operation(summary = "Modifier un responsable")
    public GuardianDto updateGuardian(@PathVariable UUID id, @Valid @RequestBody GuardianRequest request) {
        return families.updateGuardian(id, request);
    }

    @DeleteMapping("/guardians/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Retirer un responsable (un responsable légal doit rester)")
    public void deleteGuardian(@PathVariable UUID id) {
        families.deleteGuardian(id);
    }

    @GetMapping("/families/{id}/export")
    @Operation(summary = "Exporter toutes les données de la famille (ZIP : JSON + documents)")
    public ResponseEntity<byte[]> export(@PathVariable UUID id) {
        var archive = data.export(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(archive.fileName()).build().toString())
                .body(archive.content());
    }

    @PostMapping("/families/{id}/purge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer définitivement les données de la famille (enfants sortis, confirmation requise)")
    public void purge(@PathVariable UUID id, @Valid @RequestBody PurgeRequest request) {
        data.purge(id, request.confirmation());
    }
}
