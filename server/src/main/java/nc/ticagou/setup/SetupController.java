package nc.ticagou.setup;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import nc.ticagou.auth.AuthDtos.SessionResponse;
import nc.ticagou.auth.SessionCookies;
import nc.ticagou.license.DesktopClientService;
import nc.ticagou.setup.SetupDtos.SetupRequest;
import nc.ticagou.setup.SetupDtos.SetupStatus;

@RestController
@RequestMapping("/api/v1/setup")
@Tag(name = "Premier lancement", description = "Installation initiale de la structure")
public class SetupController {

    /** Jeton de démarrage remis à la directrice. */
    static final String TOKEN_HEADER = "X-Ticagou-Setup";

    private final SetupService service;
    private final SessionCookies cookies;

    public SetupController(SetupService service, SessionCookies cookies) {
        this.service = service;
        this.cookies = cookies;
    }

    @GetMapping("/status")
    @Operation(summary = "Le serveur est-il déjà installé ?")
    public SetupStatus status() {
        return service.status();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Créer la structure, ses sections et le compte de la direction (une seule fois, avec le jeton de démarrage)")
    public SessionResponse complete(@Valid @RequestBody SetupRequest request,
                                    @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                    @RequestHeader(value = DesktopClientService.HEADER, required = false) String clientId,
                                    @RequestHeader(value = DesktopClientService.NAME_HEADER, required = false) String clientName,
                                    @RequestHeader(value = SessionCookies.HEADER, required = false) String cookieMode,
                                    HttpServletResponse response) {
        var session = service.complete(request, token, clientId, DesktopClientService.decodeName(clientName));
        return cookieMode == null ? session : cookies.apply(session, response);
    }
}
