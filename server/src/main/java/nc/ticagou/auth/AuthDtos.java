package nc.ticagou.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    static final String PIN_PATTERN = "^\\d{4}$";
    static final String PIN_MESSAGE = "Le code PIN comporte 4 chiffres";
    static final String LOGIN_PATTERN = "^[A-Za-z0-9._-]{3,60}$";
    static final String LOGIN_MESSAGE = "L’identifiant comporte 3 à 60 caractères : lettres sans accent, chiffres, point, tiret";

    /** @param code code de l'application d'authentification, si la double authentification est active */
    public record LoginRequest(@NotBlank @Size(max = 60) String login, @NotBlank @Size(max = 128) String password,
                               @Size(max = 6) String code) {
    }

    public record MfaCodeRequest(@NotBlank @Size(max = 6) String code) {
    }

    /** @param uri adresse otpauth, affichée en QR code */
    public record MfaSetup(String secret, String uri) {
    }

    public record PinLoginRequest(@NotNull UUID userId,
                                  @NotNull @Pattern(regexp = PIN_PATTERN, message = PIN_MESSAGE) String pin) {
    }

    public record RefreshRequest(@NotBlank @Size(max = 100) String refreshToken) {
    }

    public record ChangePasswordRequest(@NotBlank @Size(max = 128) String currentPassword,
                                        @NotBlank @Size(max = 128) String newPassword) {
    }

    /** Réponse de connexion : jetons + profil, pour éviter un aller-retour au démarrage du client. */
    public record SessionResponse(String accessToken, long expiresIn, String refreshToken, UUID sessionId,
                                  MeResponse user) {

        static SessionResponse of(TokenService.TokenPair tokens, AppUser user, AuthMethod method, boolean mfaRequired) {
            return new SessionResponse(tokens.accessToken(), tokens.expiresIn(), tokens.refreshToken(),
                    tokens.sessionId(), MeResponse.of(user, method, mfaRequired));
        }

        SessionResponse withoutRefreshToken() {
            return new SessionResponse(accessToken, expiresIn, null, sessionId, user);
        }
    }

    public record MeResponse(UUID id, UUID structureId, String firstName, String lastName, Role role,
                             AuthMethod authMethod, Instant lastLoginAt, boolean mfaEnabled,
                             boolean mfaEnrollmentRequired) {

        static MeResponse of(AppUser user, AuthMethod method, boolean mfaRequired) {
            return new MeResponse(user.getId(), user.getStructureId(), user.getFirstName(), user.getLastName(),
                    user.getRole(), method, user.getLastLoginAt(), user.isTotpEnabled(),
                    user.needsMfaEnrollment(mfaRequired, method));
        }
    }

    /** Entrée du sélecteur de la tablette : prénom et initiale du nom seulement. */
    public record PinUser(UUID id, String displayName, String initials, Role role) {

        static PinUser of(AppUser user) {
            return new PinUser(user.getId(), user.shortName(), user.initials(), user.getRole());
        }
    }
}
