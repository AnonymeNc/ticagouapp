package nc.ticagou.common;

import org.springframework.http.HttpStatus;

/**
 * Erreur métier traduite en réponse RFC 7807. {@code code} est stable et exploitable par le client ;
 * {@code detail} est le message affiché à l'utilisateur, en français.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", detail);
    }

    public static ApiException conflict(String code, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, detail);
    }

    /** Règle métier non respectée (données valides en forme mais incohérentes). */
    public static ApiException rule(String code, String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, detail);
    }

    public static ApiException forbidden(String code, String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, code, detail);
    }
}
