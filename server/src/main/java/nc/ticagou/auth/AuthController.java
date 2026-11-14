package nc.ticagou.auth;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import nc.ticagou.auth.AuthDtos.ChangePasswordRequest;
import nc.ticagou.auth.AuthDtos.LoginRequest;
import nc.ticagou.auth.AuthDtos.MeResponse;
import nc.ticagou.auth.AuthDtos.MfaCodeRequest;
import nc.ticagou.auth.AuthDtos.MfaSetup;
import nc.ticagou.auth.AuthDtos.PinLoginRequest;
import nc.ticagou.auth.AuthDtos.PinUser;
import nc.ticagou.auth.AuthDtos.RefreshRequest;
import nc.ticagou.auth.AuthDtos.SessionResponse;
import nc.ticagou.license.DesktopClientService;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentification", description = "Mot de passe (PC du bureau), PIN (tablette), jetons")
public class AuthController {

    private final AuthService service;
    private final MfaService mfa;
    private final SessionCookies cookies;

    public AuthController(AuthService service, MfaService mfa, SessionCookies cookies) {
        this.service = service;
        this.mfa = mfa;
        this.cookies = cookies;
    }

    @PostMapping("/login")
    @Operation(summary = "Connexion par identifiant et mot de passe")
    public SessionResponse login(@Valid @RequestBody LoginRequest request,
                                 @RequestHeader(value = DesktopClientService.HEADER, required = false) String clientId,
                                 @RequestHeader(value = DesktopClientService.NAME_HEADER, required = false) String clientName,
                                 @RequestHeader(value = SessionCookies.HEADER, required = false) String cookieMode,
                                 @RequestHeader(value = AppClient.HEADER, required = false) String app,
                                 HttpServletResponse response) {
        var session = service.login(request.login(), request.password(), request.code(), clientId,
                DesktopClientService.decodeName(clientName), AppClient.parse(app).orElse(null));
        return cookieMode == null ? session : cookies.apply(session, response);
    }

    @PostMapping("/pin")
    @PreAuthorize("hasRole('APPAREIL')")
    @Operation(summary = "Connexion par code PIN sur une tablette appairée")
    public SessionResponse pin(@Valid @RequestBody PinLoginRequest request) {
        return service.loginWithPin(request.userId(), request.pin());
    }

    @GetMapping("/pin-users")
    @PreAuthorize("hasRole('APPAREIL')")
    @Operation(summary = "Personnes disposant d'un code PIN (sélecteur d'une tablette appairée)")
    public List<PinUser> pinUsers() {
        return service.pinUsers();
    }

    @PostMapping("/refresh")
    @Operation(summary = "Renouveler le jeton d'accès (le jeton de rafraîchissement est à usage unique)")
    public SessionResponse refresh(@Valid @RequestBody(required = false) RefreshRequest request,
                                   @CookieValue(name = SessionCookies.NAME, required = false) String cookie,
                                   @RequestHeader(value = DesktopClientService.HEADER, required = false) String clientId,
                                   @RequestHeader(value = DesktopClientService.NAME_HEADER, required = false) String clientName,
                                   @RequestHeader(value = AppClient.HEADER, required = false) String app,
                                   HttpServletResponse response) {
        var token = request != null ? request.refreshToken() : cookie;
        if (token == null) {
            throw AuthenticationFailedException.sessionExpired();
        }
        var session = service.refresh(token, clientId, DesktopClientService.decodeName(clientName),
                AppClient.parse(app).orElse(null));
        // Session ouverte par cookie : le nouveau jeton ne repasse jamais par le JavaScript de la page.
        return request != null ? session : cookies.apply(session, response);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Fermer la session (révoque le jeton de rafraîchissement)")
    public void logout(@Valid @RequestBody(required = false) RefreshRequest request,
                       @CookieValue(name = SessionCookies.NAME, required = false) String cookie,
                       HttpServletResponse response) {
        var token = request != null ? request.refreshToken() : cookie;
        if (token != null) {
            service.logout(token);
        }
        cookies.clear(response);
    }

    @PostMapping("/mfa/start")
    @Operation(summary = "Double authentification : nouveau secret à scanner")
    public MfaSetup startMfa() {
        return mfa.start();
    }

    @PostMapping("/mfa/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Double authentification : activation avec un premier code")
    public void confirmMfa(@Valid @RequestBody MfaCodeRequest request) {
        mfa.confirm(request.code());
    }

    @DeleteMapping("/mfa/{userId}")
    @PreAuthorize("hasRole('DIRECTION')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Réinitialiser la double authentification d'une personne (téléphone perdu)")
    public void resetMfa(@PathVariable UUID userId) {
        mfa.reset(userId);
    }

    @GetMapping("/me")
    @Operation(summary = "Utilisateur connecté")
    public MeResponse me() {
        return service.me();
    }

    @PutMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Changer son propre mot de passe")
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        service.changeOwnPassword(request.currentPassword(), request.newPassword());
    }
}
