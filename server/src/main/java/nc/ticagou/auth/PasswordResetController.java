package nc.ticagou.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Lien de réinitialisation du mot de passe (créé par l'éditeur) : public, le jeton fait foi. */
@RestController
@RequestMapping("/api/v1/auth/password-reset")
@Tag(name = "Authentification")
public class PasswordResetController {

    public record CheckRequest(@NotBlank @Size(max = 128) String token) {
    }

    public record ResetRequest(@NotBlank @Size(max = 128) String token, @NotBlank @Size(max = 128) String password) {
    }

    private final PasswordResetService service;

    public PasswordResetController(PasswordResetService service) {
        this.service = service;
    }

    @PostMapping("/check")
    @Operation(summary = "Compte visé par un lien de réinitialisation encore valable")
    public PasswordResetService.Account check(@Valid @RequestBody CheckRequest request) {
        return service.check(request.token());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Choisir un nouveau mot de passe avec le lien (usage unique, 24 heures)")
    public void reset(@Valid @RequestBody ResetRequest request) {
        service.reset(request.token(), request.password());
    }
}
