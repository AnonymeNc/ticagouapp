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

    /** {@code url} : adresse de cette crèche, pour le lien depuis la vue groupe des autres. */
    public record JoinGroup(@NotBlank @Size(max = 64) String code, @Size(max = 255) String url) {
    }

    private final MonitoringService monitoring;
    private final GroupService groups;

    public MonitoringController(MonitoringService monitoring, GroupService groups) {
        this.monitoring = monitoring;
        this.groups = groups;
    }

    @GetMapping("/group")
    @Operation(summary = "Vue groupe : crèches du groupe et leurs indicateurs agrégés")
    public GroupService.GroupView group() {
        return groups.view();
    }

    @PostMapping("/group")
    @Operation(summary = "Rejoindre un groupe (code commun) : partage des indicateurs agrégés de la crèche")
    public GroupService.GroupView join(@Valid @RequestBody JoinGroup request) {
        return groups.join(request.code(), request.url());
    }

    @PostMapping("/group/leave")
    @Operation(summary = "Quitter le groupe : plus rien n'est partagé")
    public GroupService.GroupView leave() {
        return groups.leave();
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
