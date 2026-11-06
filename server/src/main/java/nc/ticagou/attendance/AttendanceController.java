package nc.ticagou.attendance;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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
import nc.ticagou.attendance.AttendanceCorrection.Target;
import nc.ticagou.attendance.AttendanceDtos.ChildAddition;
import nc.ticagou.attendance.AttendanceDtos.ChildCorrection;
import nc.ticagou.attendance.AttendanceDtos.CorrectionDto;
import nc.ticagou.attendance.AttendanceDtos.DayView;
import nc.ticagou.attendance.AttendanceDtos.DeskArrival;
import nc.ticagou.attendance.AttendanceDtos.DeskDeparture;
import nc.ticagou.attendance.AttendanceDtos.ReviewList;
import nc.ticagou.attendance.AttendanceDtos.ReviewRequest;
import nc.ticagou.attendance.AttendanceDtos.StaffAddition;
import nc.ticagou.attendance.AttendanceDtos.StaffCorrection;
import nc.ticagou.attendance.AttendanceService.Author;
import nc.ticagou.attendance.AttendanceService.Validation;
import nc.ticagou.auth.AppUserRepository;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.auth.Role;
import nc.ticagou.common.ApiException;
import nc.ticagou.config.TiCagouProperties;

/**
 * Présences au poste bureau (maquette Droits par rôle, ligne « Présences et pointage ») : direction et
 * éducatrices pointent, cuisine et comptabilité consultent ; corrections et vérifications par la direction.
 */
@RestController
@RequestMapping("/api/v1/attendance")
@Tag(name = "Présences", description = "Pointages du jour, corrections de la direction")
public class AttendanceController {

    private final AttendanceQueries queries;
    private final AttendanceService service;
    private final AppUserRepository users;
    private final ZoneId zone;
    private final Clock clock;

    public AttendanceController(AttendanceQueries queries, AttendanceService service, AppUserRepository users,
                                TiCagouProperties properties, Clock clock) {
        this.queries = queries;
        this.service = service;
        this.users = users;
        this.zone = properties.zone();
        this.clock = clock;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('DIRECTION', 'EDUCATRICE', 'CUISINE', 'COMPTABILITE')")
    @Operation(summary = "Pointages d'une journée (aujourd'hui par défaut), attendus pas encore arrivés")
    public DayView day(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return queries.day(CurrentUser.get().structureId(), date != null ? date : LocalDate.now(clock.withZone(zone)));
    }

    @GetMapping("/review")
    @PreAuthorize("hasRole('DIRECTION')")
    @Operation(summary = "Pointages à vérifier : hors connexion, code incorrect, départ non pointé, opérations refusées")
    public ReviewList review() {
        return queries.toReview(CurrentUser.get().structureId());
    }

    @GetMapping("/{target}/{id}/corrections")
    @PreAuthorize("hasAnyRole('DIRECTION', 'EDUCATRICE', 'CUISINE', 'COMPTABILITE')")
    @Operation(summary = "Historique des corrections d'un pointage")
    public List<CorrectionDto> corrections(@PathVariable String target, @PathVariable UUID id) {
        return queries.corrections(CurrentUser.get().structureId(), target(target), id);
    }

    @PostMapping("/children/{childId}/arrival")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('DIRECTION', 'EDUCATRICE')")
    @Operation(summary = "Pointer l'arrivée d'un enfant depuis le bureau, à l'heure du serveur")
    public UUID arrive(@PathVariable UUID childId, @Valid @RequestBody DeskArrival request) {
        var user = CurrentUser.get();
        var attendance = service.arrive(user.structureId(), null, childId, request.sectionId(), clock.instant(),
                author(), ChildAttendance.Source.BUREAU);
        if (request.droppedOffBy() != null) {
            service.setInstructions(user.structureId(), attendance.getId(), request.droppedOffBy(), List.of(), author());
        }
        return attendance.getId();
    }

    @PostMapping("/children/records/{id}/departure")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('DIRECTION', 'EDUCATRICE')")
    @Operation(summary = "Pointer un départ depuis le bureau ; une personne hors liste exige une session de direction")
    public void depart(@PathVariable UUID id, @Valid @RequestBody DeskDeparture request) {
        var user = CurrentUser.get();
        var attendance = service.loadAttendance(user.structureId(), id);
        Validation validation = null;
        if (request.other() != null && user.role() == Role.DIRECTION) {
            if (request.other().reason() == null || request.other().reason().isBlank()) {
                throw ApiException.rule("reason_required", "Indiquez pourquoi vous validez ce départ.");
            }
            validation = new Validation(user.userId(), user.displayName(), true);
        }
        service.depart(user.structureId(), attendance, clock.instant(), request.pickup(), request.other(), validation,
                author());
    }

    @PostMapping("/children/records")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('DIRECTION')")
    @Operation(summary = "Ajouter un passage oublié (motif obligatoire)")
    public UUID addChild(@Valid @RequestBody ChildAddition request) {
        return service.addChild(CurrentUser.get().structureId(), request.childId(), request.arrivedAt(),
                request.departedAt(), request.sectionId(), request.pickedUpBy(), request.reason(), author()).getId();
    }

    @PutMapping("/children/records/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('DIRECTION')")
    @Operation(summary = "Corriger un passage (motif obligatoire, journal en ajout seul)")
    public void correctChild(@PathVariable UUID id, @Valid @RequestBody ChildCorrection request) {
        service.correctChild(CurrentUser.get().structureId(), id, request.arrivedAt(), request.departedAt(),
                request.sectionId(), request.pickedUpBy(), request.reason(), author());
    }

    @PostMapping("/staff/records")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('DIRECTION')")
    @Operation(summary = "Ajouter un pointage du personnel oublié (motif obligatoire)")
    public UUID addStaff(@Valid @RequestBody StaffAddition request) {
        var structureId = CurrentUser.get().structureId();
        var user = users.findByIdAndStructureId(request.userId(), structureId)
                .orElseThrow(() -> ApiException.notFound("Utilisateur introuvable."));
        return service.addStaff(structureId, user, request.arrivedAt(), request.departedAt(), request.sectionId(),
                request.reason(), author()).getId();
    }

    @PutMapping("/staff/records/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('DIRECTION')")
    @Operation(summary = "Corriger un pointage du personnel (motif obligatoire)")
    public void correctStaff(@PathVariable UUID id, @Valid @RequestBody StaffCorrection request) {
        service.correctStaff(CurrentUser.get().structureId(), id, request.arrivedAt(), request.departedAt(),
                request.sectionId(), request.reason(), author());
    }

    @PutMapping("/{target}/{id}/review")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('DIRECTION')")
    @Operation(summary = "Marquer un pointage signalé comme vérifié")
    public void markReviewed(@PathVariable String target, @PathVariable UUID id,
                             @Valid @RequestBody ReviewRequest request) {
        service.review(CurrentUser.get().structureId(), target(target), id, request.note(), author());
    }

    private static Target target(String value) {
        return switch (value) {
            case "children" -> Target.ENFANT;
            case "staff" -> Target.PERSONNEL;
            default -> throw ApiException.notFound("Type de pointage inconnu.");
        };
    }

    private static Author author() {
        var user = CurrentUser.get();
        return new Author(user.userId(), user.displayName(), user.role());
    }
}
