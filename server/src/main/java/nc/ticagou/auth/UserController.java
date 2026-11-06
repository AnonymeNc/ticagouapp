package nc.ticagou.auth;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import nc.ticagou.auth.UserDtos.CreateUserRequest;
import nc.ticagou.auth.UserDtos.ResetPasswordRequest;
import nc.ticagou.auth.UserDtos.ResetPinRequest;
import nc.ticagou.auth.UserDtos.UpdateUserRequest;
import nc.ticagou.auth.UserDtos.UserDto;

@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasRole('DIRECTION')")
@Tag(name = "Utilisateurs", description = "Comptes du personnel et rôles (direction uniquement)")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Utilisateurs de la structure")
    public List<UserDto> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Créer un utilisateur (mot de passe et/ou PIN)")
    public UserDto create(@Valid @RequestBody CreateUserRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Modifier le profil, le rôle ou l'état d'un utilisateur")
    public UserDto update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return service.update(id, request);
    }

    @PutMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Définir un nouveau mot de passe (débloque le compte, ferme ses sessions)")
    public void resetPassword(@PathVariable UUID id, @Valid @RequestBody ResetPasswordRequest request) {
        service.resetPassword(id, request.password());
    }

    @PutMapping("/{id}/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Définir un nouveau code PIN (débloque le PIN)")
    public void resetPin(@PathVariable UUID id, @Valid @RequestBody ResetPinRequest request) {
        service.resetPin(id, request.pin());
    }
}
