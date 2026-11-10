package nc.ticagou.enrollment;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import nc.ticagou.enrollment.EnrollmentDtos.CriterionDto;
import nc.ticagou.enrollment.EnrollmentDtos.CriterionRequest;

@RestController
@RequestMapping("/api/v1/enrollment-criteria")
@Tag(name = "Critères d'inscription", description = "Critères de priorité et leurs points, datés")
public class EnrollmentCriterionController {

    private final EnrollmentCriterionService criteria;

    public EnrollmentCriterionController(EnrollmentCriterionService criteria) {
        this.criteria = criteria;
    }

    @GetMapping
    @PreAuthorize(EnrollmentController.READERS)
    @Operation(summary = "Critères de la structure")
    public List<CriterionDto> list() {
        return criteria.list();
    }

    @PostMapping
    @PreAuthorize(EnrollmentController.EDITORS)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ajouter un critère")
    public CriterionDto create(@Valid @RequestBody CriterionRequest request) {
        return criteria.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize(EnrollmentController.EDITORS)
    @Operation(summary = "Modifier un critère")
    public CriterionDto update(@PathVariable UUID id, @Valid @RequestBody CriterionRequest request) {
        return criteria.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(EnrollmentController.EDITORS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer un critère")
    public void delete(@PathVariable UUID id) {
        criteria.delete(id);
    }
}
