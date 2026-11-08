package nc.ticagou.console.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Erreurs au format RFC 7807, en français, sans détail technique ; passe avant le gestionnaire générique de Spring. */
@RestControllerAdvice
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException e) {
        var response = problem(e.status(), e.code(), e.getMessage());
        if (e.lockedUntil() != null) {
            response.getBody().setProperty("lockedUntil", e.lockedUntil().toString());
        }
        return response;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException e) {
        var first = e.getBindingResult().getFieldErrors().stream().findFirst()
                .map(f -> f.getDefaultMessage()).orElse("Certaines informations sont à corriger.");
        return problem(HttpStatus.BAD_REQUEST, "validation", first);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "unreadable", "Requête illisible.");
    }

    public static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return ResponseEntity.status(status).body(problem);
    }
}
