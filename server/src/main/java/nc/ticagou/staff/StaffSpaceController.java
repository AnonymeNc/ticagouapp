package nc.ticagou.staff;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import nc.ticagou.license.LicenseModule;
import nc.ticagou.license.RequiresModule;
import nc.ticagou.staff.StaffSpaceService.Answer;
import nc.ticagou.staff.StaffSpaceService.Counters;
import nc.ticagou.staff.StaffSpaceService.RequestKind;
import nc.ticagou.staff.StaffSpaceService.StaffRequestDto;
import nc.ticagou.staff.StaffSpaceService.TeamNote;

/** Espace pro (lot 4) : demandes du personnel, compteurs, cahier d'équipe, agenda partagé. */
@RestController
@RequestMapping("/api/v1")
@RequiresModule(LicenseModule.PERSONNEL)
@Tag(name = "Espace pro", description = "Demandes de congé et d'heures, compteurs, cahier d'équipe, agenda")
public class StaffSpaceController {

    static final String STAFF = "hasAnyRole('DIRECTION', 'EDUCATRICE', 'CUISINE', 'COMPTABILITE')";
    static final String TEAM = "hasAnyRole('DIRECTION', 'EDUCATRICE', 'CUISINE')";

    public record RequestBodyDto(@NotNull RequestKind kind, @NotNull LocalDate fromDay, @NotNull LocalDate toDay, Integer minutes,
                                 @Size(max = 300) String note) {
    }

    public record Refusal(@Size(max = 300) String reason) {
    }

    public record NoteBody(UUID sectionId, @Size(max = 1000) String body) {
    }

    private final StaffSpaceService space;

    public StaffSpaceController(StaffSpaceService space) {
        this.space = space;
    }

    @PostMapping("/staff-requests")
    @PreAuthorize(STAFF)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Demander un congé, une récupération, ou déclarer des heures supplémentaires")
    public StaffRequestDto request(@Valid @RequestBody RequestBodyDto r) {
        return space.request(r.kind(), r.fromDay(), r.toDay(), r.minutes(), r.note());
    }

    @GetMapping("/staff-requests/mine")
    @PreAuthorize(STAFF)
    @Operation(summary = "Mes demandes et leur réponse")
    public List<StaffRequestDto> mine() {
        return space.mine();
    }

    @PostMapping("/staff-requests/{id}/cancel")
    @PreAuthorize(STAFF)
    @Operation(summary = "Annuler ma demande tant qu'elle attend une réponse")
    public void cancel(@PathVariable UUID id) {
        space.cancel(id);
    }

    @GetMapping("/staff-requests")
    @PreAuthorize(StaffController.EDITORS)
    @Operation(summary = "Demandes du personnel en attente")
    public List<StaffRequestDto> pending() {
        return space.pending();
    }

    @PostMapping("/staff-requests/{id}/accept")
    @PreAuthorize(StaffController.EDITORS)
    @Operation(summary = "Accepter : le congé entre au planning ; jours en sous-effectif signalés")
    public Answer accept(@PathVariable UUID id) {
        return space.accept(id);
    }

    @PostMapping("/staff-requests/{id}/refuse")
    @PreAuthorize(StaffController.EDITORS)
    @Operation(summary = "Refuser avec un motif")
    public StaffRequestDto refuse(@PathVariable UUID id, @Valid @RequestBody Refusal refusal) {
        return space.refuse(id, refusal.reason());
    }

    @GetMapping("/staff-counters")
    @PreAuthorize(STAFF)
    @Operation(summary = "Compteurs de l'année (les miens, ou ceux d'un membre pour la direction)")
    public Counters counters(@RequestParam(required = false) UUID userId) {
        return space.counters(userId);
    }

    @GetMapping("/team-notes")
    @PreAuthorize(TEAM)
    @Operation(summary = "Cahier d'équipe des 60 derniers jours")
    public List<TeamNote> notes() {
        return space.notes();
    }

    @PostMapping("/team-notes")
    @PreAuthorize(TEAM)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Écrire dans le cahier d'équipe (toute l'équipe ou une section)")
    public TeamNote write(@Valid @RequestBody NoteBody note) {
        return space.writeNote(note.sectionId(), note.body());
    }

    @PostMapping("/team-notes/{id}/read")
    @PreAuthorize(TEAM)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "J'ai lu cette note")
    public void read(@PathVariable UUID id) {
        space.markRead(id);
    }

    @GetMapping("/staff-calendar")
    @PreAuthorize(STAFF)
    @Operation(summary = "Ai-je un lien d'agenda ?")
    public Map<String, Boolean> calendarState() {
        return Map.of("active", space.hasCalendar());
    }

    @PostMapping("/staff-calendar")
    @PreAuthorize(STAFF)
    @Operation(summary = "Créer (ou remplacer) mon lien d'agenda privé ; le lien n'est montré qu'une fois")
    public Map<String, String> newCalendar() {
        return Map.of("token", space.newCalendarToken());
    }

    @DeleteMapping("/staff-calendar")
    @PreAuthorize(STAFF)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Révoquer mon lien d'agenda")
    public void revokeCalendar() {
        space.revokeCalendar();
    }

    @GetMapping("/public/calendar/{token}.ics")
    @Operation(summary = "Mon planning au format iCalendar (lien privé, sans connexion)")
    public ResponseEntity<String> calendar(@PathVariable String token) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/calendar;charset=UTF-8"))
                .cacheControl(CacheControl.noStore()).body(space.calendar(token));
    }
}
