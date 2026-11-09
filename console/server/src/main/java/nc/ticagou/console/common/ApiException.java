package nc.ticagou.console.common;

import java.time.Instant;

import org.springframework.http.HttpStatus;

/** Erreur métier rendue en RFC 7807 avec un {@code code} stable pour l'interface. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Instant lockedUntil;

    public ApiException(HttpStatus status, String code, String detail) {
        this(status, code, detail, null);
    }

    private ApiException(HttpStatus status, String code, String detail, Instant lockedUntil) {
        super(detail);
        this.status = status;
        this.code = code;
        this.lockedUntil = lockedUntil;
    }

    public static ApiException rule(String code, String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, detail);
    }

    public static ApiException unauthorized(String code, String detail) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, detail);
    }

    public static ApiException forbidden(String code, String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, code, detail);
    }

    public static ApiException notFound(String code, String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, code, detail);
    }

    public static ApiException gone(String code, String detail) {
        return new ApiException(HttpStatus.GONE, code, detail);
    }

    public static ApiException conflict(String code, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, detail);
    }

    public static ApiException locked(Instant until) {
        return new ApiException(HttpStatus.LOCKED, "account_locked",
                "Trop d’essais manqués : ce compte est bloqué pendant quelques minutes.", until);
    }

    public static ApiException tooMany() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_requests",
                "Trop d’essais depuis cette adresse. Réessayez dans quelques minutes.");
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Instant lockedUntil() {
        return lockedUntil;
    }
}
