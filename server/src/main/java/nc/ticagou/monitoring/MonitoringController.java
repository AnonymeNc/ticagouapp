package nc.ticagou.monitoring;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import nc.ticagou.monitoring.MonitoringService.SettingsDto;

/** Paramètres › Supervision : la direction active ou coupe l'envoi de l'état technique au prestataire. */
@RestController
@RequestMapping("/api/v1/monitoring")
@Tag(name = "Supervision", description = "État technique envoyé au prestataire (ADR 0012)")
@PreAuthorize("hasRole('DIRECTION')")
public class MonitoringController {

    public record ActivateRequest(@NotBlank @Size(max = 255) String serviceUrl, @NotBlank @Size(max = 40) String activationCode) {
    }

    private final MonitoringService monitoring;

    public MonitoringController(MonitoringService monitoring) {
        this.monitoring = monitoring;
    }

    @GetMapping
    @Operation(summary = "État de la supervision")
    public SettingsDto settings() {
        return monitoring.settings();
    }

    @PostMapping("/activate")
    @Operation(summary = "Activer la supervision avec le code remis par le prestataire")
    public SettingsDto activate(@Valid @RequestBody ActivateRequest request) {
        return monitoring.activate(request.serviceUrl(), request.activationCode());
    }

    @PostMapping("/deactivate")
    @Operation(summary = "Couper la supervision (plus aucun envoi)")
    public SettingsDto deactivate() {
        return monitoring.deactivate();
    }

    @PostMapping("/send")
    @Operation(summary = "Envoyer l'état maintenant")
    public SettingsDto send() {
        return monitoring.sendNow();
    }
}
