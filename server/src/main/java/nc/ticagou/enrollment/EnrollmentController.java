package nc.ticagou.enrollment;

import java.time.YearMonth;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
import nc.ticagou.enrollment.EnrollmentDtos.BoardDto;
import nc.ticagou.enrollment.EnrollmentDtos.Created;
import nc.ticagou.enrollment.EnrollmentDtos.NoteDto;
import nc.ticagou.enrollment.EnrollmentDtos.NoteRequest;
import nc.ticagou.enrollment.EnrollmentDtos.RequestForm;
import nc.ticagou.enrollment.EnrollmentDtos.RequestRecord;
import nc.ticagou.enrollment.EnrollmentDtos.StageRequest;
import nc.ticagou.enrollment.EnrollmentDtos.VisitRequest;

/**
 * Module Inscriptions. Droits (maquette Paramètres › Droits par rôle, ligne « Inscriptions et contrats ») :
 * la direction modifie, la comptabilité consulte, l'éducatrice et la cuisine n'ont pas accès.
 */
@RestController
@RequestMapping("/api/v1/enrollment-requests")
@Tag(name = "Inscriptions", description = "Demandes d'inscription, de la prise de contact à la signature")
public class EnrollmentController {

    static final String READERS = "hasAnyRole('DIRECTION', 'COMPTABILITE')";
    static final String EDITORS = "hasRole('DIRECTION')";

    private final EnrollmentService enrollments;

    public EnrollmentController(EnrollmentService enrollments) {
        this.enrollments = enrollments;
    }

    @GetMapping
    @PreAuthorize(READERS)
    @Operation(summary = "Tableau des demandes : statistiques et cartes filtrées")
    public BoardDto board(@RequestParam(required = false) String q,
                          @RequestParam(required = false) UUID sectionId,
                          @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
                          @RequestParam(defaultValue = "false") boolean includeClosed) {
        return enrollments.board(q, sectionId, month, includeClosed);
    }

    @PostMapping
    @PreAuthorize(EDITORS)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Enregistrer une nouvelle demande")
    public Created create(@Valid @RequestBody RequestForm form) {
        return new Created(enrollments.create(form));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READERS)
    @Operation(summary = "Fiche demande : score, rang, places libres, notes et historique")
    public RequestRecord record(@PathVariable UUID id) {
        return enrollments.record(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize(EDITORS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Modifier la demande")
    public void update(@PathVariable UUID id, @Valid @RequestBody RequestForm form) {
        enrollments.update(id, form);
    }

    @PutMapping("/{id}/stage")
    @PreAuthorize(EDITORS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Changer d'étape (refus avec motif, réouverture)")
    public void changeStage(@PathVariable UUID id, @Valid @RequestBody StageRequest request) {
        enrollments.changeStage(id, request);
    }

    @PutMapping("/{id}/visit")
    @PreAuthorize(EDITORS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Déplacer la visite")
    public void moveVisit(@PathVariable UUID id, @Valid @RequestBody VisitRequest request) {
        enrollments.moveVisit(id, request);
    }

    @PostMapping("/{id}/visit/done")
    @PreAuthorize(EDITORS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Marquer la visite comme faite")
    public void markVisitDone(@PathVariable UUID id) {
        enrollments.markVisitDone(id);
    }

    @PostMapping("/{id}/notes")
    @PreAuthorize(EDITORS)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ajouter une note")
    public NoteDto addNote(@PathVariable UUID id, @Valid @RequestBody NoteRequest request) {
        return enrollments.addNote(id, request);
    }
}
