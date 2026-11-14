package nc.ticagou.notification;

import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import nc.ticagou.auth.AppClient;
import nc.ticagou.common.ApiException;
import nc.ticagou.license.DesktopClientService;
import nc.ticagou.notification.NativePush.Platform;
import nc.ticagou.notification.NotificationService.Category;
import nc.ticagou.notification.NotificationService.DeviceDto;

/** Appareils de l'application Direction inscrits aux notifications (ADR 0026, maquette C7). */
@RestController
@RequestMapping("/api/v1/notifications/device")
@Tag(name = "Notifications", description = "Alertes de la direction sur son téléphone")
public class NotificationController {

    /**
     * @param platform   IOS (jeton APNs) ou ANDROID (jeton FCM)
     * @param token      jeton donné par le système du téléphone
     * @param categories alertes choisies ; absent = celles par défaut
     */
    public record DeviceRequest(@NotNull Platform platform, @NotBlank @Size(max = 400) String token, Set<Category> categories) {
    }

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @PutMapping
    @Operation(summary = "Inscrire ce téléphone aux notifications, ou changer ses catégories")
    public DeviceDto register(@Valid @RequestBody DeviceRequest request,
                              @RequestHeader(value = AppClient.HEADER, required = false) String app,
                              @RequestHeader(value = DesktopClientService.HEADER, required = false) String poste) {
        var client = AppClient.parse(app).orElseThrow(() -> ApiException.rule("app_required",
                "Les notifications se règlent dans l’application Ti’Cagou Direction."));
        return service.register(request.platform(), client.app(), request.token().strip(), request.categories(), poste);
    }

    @GetMapping
    @Operation(summary = "Catégories reçues par ce téléphone")
    public DeviceDto get(@RequestParam String token) {
        return service.get(token);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Ne plus recevoir de notification sur ce téléphone (déconnexion, oubli de la crèche)")
    public void unregister(@RequestParam String token) {
        service.unregister(token);
    }
}
