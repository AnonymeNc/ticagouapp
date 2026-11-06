package nc.ticagou.auth;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class UserDtos {

    private UserDtos() {
    }

    public record UserDto(UUID id, String firstName, String lastName, Role role, String login, boolean hasPassword,
                          boolean hasPin, boolean active, boolean locked, Instant lastLoginAt, String jobTitle,
                          UUID homeSectionId, boolean countsInRatio) {

        static UserDto of(AppUser user, Instant now) {
            return new UserDto(user.getId(), user.getFirstName(), user.getLastName(), user.getRole(), user.getLogin(),
                    user.getPasswordHash() != null, user.getPinHash() != null, user.isActive(),
                    user.isPasswordLocked(now) || user.isPinLocked(now), user.getLastLoginAt(), user.getJobTitle(),
                    user.getHomeSectionId(), user.isCountsInRatio());
        }
    }

    /** Création : mot de passe (PC du bureau) et/ou PIN (tablette), au moins l'un des deux. */
    public record CreateUserRequest(
            @NotBlank @Size(max = 80) String firstName,
            @NotBlank @Size(max = 80) String lastName,
            @NotNull Role role,
            @Pattern(regexp = AuthDtos.LOGIN_PATTERN, message = AuthDtos.LOGIN_MESSAGE) String login,
            @Size(max = 128) String password,
            @Pattern(regexp = AuthDtos.PIN_PATTERN, message = AuthDtos.PIN_MESSAGE) String pin,
            @Size(max = 80) String jobTitle,
            UUID homeSectionId,
            Boolean countsInRatio) {

        /** Création sans les champs du personnel (assistant de premier lancement, anciens clients). */
        public CreateUserRequest(String firstName, String lastName, Role role, String login, String password,
                                 String pin) {
            this(firstName, lastName, role, login, password, pin, null, null, null);
        }

        @AssertTrue(message = "Renseignez un mot de passe (PC du bureau) ou un code PIN (tablette)")
        @JsonIgnore
        public boolean isAuthenticationProvided() {
            return password != null || pin != null;
        }

        @AssertTrue(message = "Un identifiant est nécessaire pour se connecter avec un mot de passe")
        @JsonIgnore
        public boolean isLoginProvidedWithPassword() {
            return password == null || login != null;
        }
    }

    public record UpdateUserRequest(
            @NotBlank @Size(max = 80) String firstName,
            @NotBlank @Size(max = 80) String lastName,
            @NotNull Role role,
            @Pattern(regexp = AuthDtos.LOGIN_PATTERN, message = AuthDtos.LOGIN_MESSAGE) String login,
            boolean active,
            @Size(max = 80) String jobTitle,
            UUID homeSectionId,
            Boolean countsInRatio) {
    }

    public record ResetPasswordRequest(@NotBlank @Size(max = 128) String password) {
    }

    public record ResetPinRequest(
            @NotNull @Pattern(regexp = AuthDtos.PIN_PATTERN, message = AuthDtos.PIN_MESSAGE) String pin) {
    }
}
