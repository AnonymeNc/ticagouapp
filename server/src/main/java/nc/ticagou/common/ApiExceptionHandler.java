package nc.ticagou.common;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import nc.ticagou.audit.AuditAction;
import nc.ticagou.audit.AuditService;
import nc.ticagou.auth.AuthenticationFailedException;
import nc.ticagou.auth.CurrentUser;

/** Toutes les erreurs de l'API au format RFC 7807, avec un {@code code} stable et un message en français. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final AuditService audit;

    public ApiExceptionHandler(AuditService audit) {
        this.audit = audit;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException e) {
        var problem = problem(e.status(), e.code(), e.getMessage());
        var response = ResponseEntity.status(e.status());
        if (e instanceof AuthenticationFailedException failed && failed.lockedUntil() != null) {
            problem.setProperty("lockedUntil", failed.lockedUntil());
        }
        return response.body(problem);
    }

    /** Refus d'accès par rôle : réponse 403 et trace dans le journal d'audit. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException e, WebRequest request) {
        CurrentUser.find().ifPresent(user -> {
            var path = request instanceof ServletWebRequest servlet
                    ? servlet.getRequest().getMethod() + " " + servlet.getRequest().getRequestURI()
                    : request.getDescription(false);
            audit.record(AuditAction.ACCESS_DENIED, "endpoint", null, Map.of("path", path, "role", user.role().name()));
        });
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(problem(HttpStatus.FORBIDDEN, "forbidden", "Votre rôle ne permet pas cette action."));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(HttpStatus.CONFLICT, "concurrent_update",
                "Ces données viennent d’être modifiées par quelqu’un d’autre. Rechargez la page."));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        var errors = new LinkedHashMap<String, String>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        e.getBindingResult().getGlobalErrors()
                .forEach(error -> errors.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));
        var problem = problem(HttpStatus.BAD_REQUEST, "validation", "Certaines informations sont à corriger.");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (statusCode.is5xxServerError()) {
            log.error("Erreur serveur", e);
        }
        return super.handleExceptionInternal(e, body, headers, statusCode, request);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }
}
