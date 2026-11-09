package nc.ticagou.console.billing;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import nc.ticagou.console.billing.ReminderPlanner.Rule;

/** Relances (plan console, phase 4) : règles datées, fériés, règles par client, préparation à la demande. */
@RestController
@RequestMapping("/api/v1/relances")
class ReminderController {

    record RuleRequest(@Min(0) @Max(3) int level, @NotBlank @Size(max = 60) String label, @Min(-60) @Max(365) int days, boolean active,
                       @NotBlank(message = "Chaque niveau a un objet.") @Size(max = 200) String subject,
                       @NotBlank(message = "Chaque niveau a un texte.") @Size(max = 6000) String body,
                       boolean copyToIssuer, boolean attachPdf, boolean proposeSuspension) {
    }

    record VersionRequest(@NotBlank @Size(max = 80) String label, @NotNull LocalDate validFrom, @NotEmpty List<@Valid RuleRequest> rules) {
    }

    record ClientRuleRequest(@Min(value = 0, message = "Décalage : 0 à 120 jours.") @Max(value = 120, message = "Décalage : 0 à 120 jours.") int shiftDays,
                             boolean paused, @Size(max = 300) String note) {
    }

    record HolidayRequest(@NotNull LocalDate day, @NotBlank @Size(max = 80) String label) {
    }

    private final ReminderService service;

    ReminderController(ReminderService service) {
        this.service = service;
    }

    @GetMapping
    ReminderService.Settings settings() {
        return service.settings();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ReminderService.Settings newVersion(@Valid @RequestBody VersionRequest r) {
        return service.newVersion(r.label(), r.validFrom(), r.rules().stream().map(x -> new Rule(x.level(), x.label().strip(), x.days(),
                x.active(), x.subject().strip(), x.body(), x.copyToIssuer(), x.attachPdf(), x.proposeSuspension())).toList());
    }

    @PutMapping("/clients/{clientId}")
    ReminderService.Settings clientRule(@PathVariable UUID clientId, @Valid @RequestBody ClientRuleRequest r) {
        return service.clientRule(clientId, r.shiftDays(), r.paused(), r.note());
    }

    @PostMapping("/feries")
    @ResponseStatus(HttpStatus.CREATED)
    ReminderService.Settings addHoliday(@Valid @RequestBody HolidayRequest r) {
        return service.addHoliday(r.day(), r.label());
    }

    @DeleteMapping("/feries/{day}")
    ReminderService.Settings removeHoliday(@PathVariable LocalDate day) {
        return service.removeHoliday(day);
    }

    @PostMapping("/preparer")
    Map<String, Integer> prepare() {
        return Map.of("messages", service.prepareNow());
    }
}
