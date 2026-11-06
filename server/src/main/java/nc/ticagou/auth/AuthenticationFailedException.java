package nc.ticagou.auth;

import java.time.Instant;

import org.springframework.http.HttpStatus;

import nc.ticagou.common.ApiException;

/**
 * Échec d'authentification. Les compteurs d'échecs sont enregistrés malgré l'exception
 * ({@code noRollbackFor} dans {@link AuthService}).
 */
public class AuthenticationFailedException extends ApiException {

    private final Instant lockedUntil;

    private AuthenticationFailedException(HttpStatus status, String code, String detail, Instant lockedUntil) {
        super(status, code, detail);
        this.lockedUntil = lockedUntil;
    }

    static AuthenticationFailedException badCredentials() {
        return new AuthenticationFailedException(HttpStatus.UNAUTHORIZED, "bad_credentials",
                "Identifiant ou mot de passe incorrect.", null);
    }

    static AuthenticationFailedException badPin() {
        return new AuthenticationFailedException(HttpStatus.UNAUTHORIZED, "bad_pin", "Code PIN incorrect.", null);
    }

    static AuthenticationFailedException locked(Instant until) {
        return new AuthenticationFailedException(HttpStatus.TOO_MANY_REQUESTS, "locked",
                "Trop d’essais incorrects. Réessayez dans quelques minutes ou demandez à la direction.", until);
    }

    static AuthenticationFailedException sessionExpired() {
        return new AuthenticationFailedException(HttpStatus.UNAUTHORIZED, "session_expired",
                "Votre session a expiré. Reconnectez-vous.", null);
    }

    public Instant lockedUntil() {
        return lockedUntil;
    }
}
