package nc.ticagou.journal;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.journal.JournalDtos.EntryCorrection;
import nc.ticagou.journal.JournalDtos.EntryDto;

/** Transmissions du jour, relues et corrigées au bureau ; la saisie se fait sur la tablette. */
@RestController
@RequestMapping("/api/v1/journal")
@Tag(name = "Transmissions", description = "Repas, siestes, changes, activités et photos de la journée")
public class JournalController {

    static final String TEAM = "hasAnyRole('DIRECTION', 'EDUCATRICE')";

    private final JournalService journal;

    public JournalController(JournalService journal) {
        this.journal = journal;
    }

    @GetMapping
    @PreAuthorize(TEAM)
    @Operation(summary = "Transmissions d'un jour, pour tous les enfants ou un seul")
    public List<EntryDto> list(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                               @RequestParam(required = false) UUID childId) {
        return journal.list(day, childId);
    }

    @PutMapping("/{id}")
    @PreAuthorize(TEAM)
    @Operation(summary = "Corriger une transmission (heure, texte, détails, partage)")
    public EntryDto correct(@PathVariable UUID id, @Valid @RequestBody EntryCorrection request) {
        return journal.correct(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(TEAM)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer une transmission et sa photo")
    public void delete(@PathVariable UUID id) {
        journal.delete(id);
    }

    @GetMapping("/{id}/photo")
    @PreAuthorize(TEAM)
    @Operation(summary = "Photo d'une transmission")
    public ResponseEntity<byte[]> photo(@PathVariable UUID id) {
        return JournalPhotos.response(journal.photo(CurrentUser.get().structureId(), id));
    }
}
