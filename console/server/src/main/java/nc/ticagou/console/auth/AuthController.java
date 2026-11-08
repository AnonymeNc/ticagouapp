package nc.ticagou.console.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Connexion : état, premier compte, mot de passe, code TOTP, confirmation d'une action sensible, déconnexion. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record SetupRequest(String token, String login, String displayName, String password) {
    }

    public record LoginRequest(String login, String password) {
    }

    public record CodeRequest(String code) {
    }

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @GetMapping("/state")
    public AuthService.State state(HttpServletRequest request) {
        return auth.state(request);
    }

    @PostMapping("/setup")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthService.OperatorDto setup(@RequestBody SetupRequest body, HttpServletRequest request) {
        return auth.setup(body.token(), body.login(), body.displayName(), body.password(), request);
    }

    @PostMapping("/login")
    public AuthService.LoginStep login(@RequestBody LoginRequest body, HttpServletRequest request) {
        return auth.login(body.login(), body.password(), request);
    }

    @PostMapping("/totp")
    public AuthService.OperatorDto totp(@RequestBody CodeRequest body, HttpServletRequest request, HttpServletResponse response) {
        return auth.verify(body.code(), request, response);
    }

    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@RequestBody CodeRequest body, HttpServletRequest request) {
        auth.confirm(body.code(), request);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        auth.logout(request);
    }
}
