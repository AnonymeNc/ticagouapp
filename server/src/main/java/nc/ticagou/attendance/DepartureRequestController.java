package nc.ticagou.attendance;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import nc.ticagou.attendance.DepartureRequestService.RequestDto;
import nc.ticagou.attendance.DepartureRequestService.RequestStatus;

/** Départ bloqué validé à distance par la direction (maquette B5). */
@RestController
@Tag(name = "Présences", description = "Pointage des enfants et du personnel")
public class DepartureRequestController {

    /** Demande de la tablette : la personne présente, son lien, la pièce d'identité vue, qui demande. */
    public record NewRequest(@NotNull UUID attendanceId, @NotBlank @Size(max = 170) String personName,
                             @NotBlank @Size(max = 120) String relationship, boolean idChecked,
                             @NotBlank @Size(max = 170) String requestedBy) {
    }

    /** Décision de la direction ; le motif est obligatoire dans les deux cas. */
    public record Decision(boolean approve, @NotBlank @Size(max = 300) String reason) {
    }

    private final DepartureRequestService service;

    public DepartureRequestController(DepartureRequestService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/kiosk/departure-requests")
    @PreAuthorize("hasRole('APPAREIL')")
    @Operation(summary = "Tablette : demander à la direction de valider un départ à distance")
    public RequestStatus request(@Valid @RequestBody NewRequest request) {
        return service.request(request.attendanceId(), request.personName(), request.relationship(), request.idChecked(),
                request.requestedBy());
    }

    @GetMapping("/api/v1/kiosk/departure-requests/{id}")
    @PreAuthorize("hasRole('APPAREIL')")
    @Operation(summary = "Tablette : décision de la direction")
    public RequestStatus status(@PathVariable UUID id) {
        return service.status(id);
    }

    @DeleteMapping("/api/v1/kiosk/departure-requests/{id}")
    @PreAuthorize("hasRole('APPAREIL')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Tablette : annuler la demande")
    public void cancel(@PathVariable UUID id) {
        service.cancel(id);
    }

    @GetMapping("/api/v1/attendance/departure-requests")
    @PreAuthorize("hasRole('DIRECTION')")
    @Operation(summary = "Départs bloqués en attente de la direction")
    public List<RequestDto> pending() {
        return service.pending();
    }

    @PostMapping("/api/v1/attendance/departure-requests/{id}/decision")
    @PreAuthorize("hasRole('DIRECTION')")
    @Operation(summary = "Autoriser ou refuser un départ bloqué, avec un motif (tracé)")
    public RequestDto decide(@PathVariable UUID id, @Valid @RequestBody Decision decision) {
        return service.decide(id, decision.approve(), decision.reason());
    }
}
