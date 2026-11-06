package nc.ticagou.portail.common;

import org.springframework.http.HttpStatus;

/** Erreur métier rendue en RFC 7807 avec un {@code code} stable pour la PWA et le serveur de la crèche. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", detail);
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

    public static ApiException tooMany(String detail) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_requests", detail);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
