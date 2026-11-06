package nc.ticagou.billing;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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
import nc.ticagou.billing.AbsenceDtos.AbsenceDto;
import nc.ticagou.billing.AbsenceDtos.AbsenceRequest;

/** Absences : saisies par la direction et les éducatrices, consultées aussi par la comptabilité. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Absences", description = "Absences signalées, déduites selon leur motif")
public class AbsenceController {

    static final String READERS = "hasAnyRole('DIRECTION', 'EDUCATRICE', 'COMPTABILITE')";
    static final String EDITORS = "hasAnyRole('DIRECTION', 'EDUCATRICE')";

    private final AbsenceService absences;

    public AbsenceController(AbsenceService absences) {
        this.absences = absences;
    }

    @GetMapping("/absences")
    @PreAuthorize(READERS)
    @Operation(summary = "Absences qui chevauchent une période (trois mois au plus)")
    public List<AbsenceDto> between(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return absences.listBetween(from, to);
    }

    @GetMapping("/children/{childId}/absences")
    @PreAuthorize(READERS)
    @Operation(summary = "Absences d'un enfant, des plus récentes aux plus anciennes")
    public List<AbsenceDto> forChild(@PathVariable UUID childId) {
        return absences.listForChild(childId);
    }

    @PostMapping("/children/{childId}/absences")
    @PreAuthorize(EDITORS)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Signaler une absence")
    public AbsenceDto create(@PathVariable UUID childId, @Valid @RequestBody AbsenceRequest request) {
        return absences.create(childId, request);
    }

    @PutMapping("/absences/{id}")
    @PreAuthorize(EDITORS)
    @Operation(summary = "Modifier une absence d'un mois non facturé")
    public AbsenceDto update(@PathVariable UUID id, @Valid @RequestBody AbsenceRequest request) {
        return absences.update(id, request);
    }

    @DeleteMapping("/absences/{id}")
    @PreAuthorize(EDITORS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer une absence d'un mois non facturé")
    public void delete(@PathVariable UUID id) {
        absences.delete(id);
    }
}
