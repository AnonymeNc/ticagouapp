package nc.ticagou.common;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonMappingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
        // Chaque erreur dit quel champ corriger et pourquoi ; le message général les reprend toutes.
        var errors = new LinkedHashMap<String, String>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), ValidationMessages.message(error));
        }
        e.getBindingResult().getGlobalErrors()
                .forEach(error -> errors.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));
        var problem = problem(HttpStatus.BAD_REQUEST, "validation", ValidationMessages.detail(errors.values()));
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    /** Valeur illisible (date ou heure mal formée, choix inconnu) : on dit laquelle, en français. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        String detail = "La demande envoyée est illisible. Rechargez la page et recommencez.";
        var errors = new LinkedHashMap<String, String>();
        if (e.getCause() instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            var path = new StringBuilder();
            for (var ref : mapping.getPath()) {
                if (ref.getFieldName() != null) {
                    path.append(path.isEmpty() ? "" : ".").append(ref.getFieldName());
                } else if (ref.getIndex() >= 0) {
                    path.append('[').append(ref.getIndex()).append(']');
                }
            }
            detail = ValidationMessages.label(path.toString()) + " : valeur au mauvais format.";
            errors.put(path.toString(), detail);
        }
        var problem = problem(HttpStatus.BAD_REQUEST, "validation", detail);
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (statusCode.is5xxServerError()) {
            log.error("Erreur serveur", e);
        }
        // Erreurs produites par Spring (paramètre manquant, identifiant mal formé, fichier trop lourd…) : leur détail
        // est en anglais et technique. On le remplace par un message en français et un code stable.
        if (body instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            var french = french(statusCode.value());
            problem.setDetail(french[1]);
            problem.setProperty("code", french[0]);
        }
        return super.handleExceptionInternal(e, body, headers, statusCode, request);
    }

    /** Code et message en français des erreurs génériques, par statut HTTP. */
    private static String[] french(int status) {
        return switch (status) {
            case 400 -> new String[] { "bad_request", "La demande est incomplète ou mal formée. Rechargez la page et recommencez." };
            case 404 -> new String[] { "not_found", "Cette page ou cette donnée n’existe pas (ou plus)." };
            case 405 -> new String[] { "method_not_allowed", "Cette action n’est pas possible ici." };
            case 406, 415 -> new String[] { "unsupported_format", "Ce format n’est pas pris en charge." };
            case 413 -> new String[] { "file_too_large", "Le fichier est trop volumineux." };
            default -> status >= 500
                    ? new String[] { "server_error", "Erreur du serveur. Réessayez ; si elle se répète, contactez le support Ti’Cagou." }
                    : new String[] { "error", "La demande n’a pas pu être traitée (erreur " + status + ")." };
        };
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }
}
