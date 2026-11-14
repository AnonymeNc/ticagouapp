package nc.ticagou.portail.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import nc.ticagou.portail.PortalProperties;
import nc.ticagou.portail.common.ApiException;

/**
 * Connexion des parents : demande d'e-mail, lien ou code, déconnexion. Dans le navigateur, la session est un cookie
 * HttpOnly ; l'application Ti’Cagou Parents (en-tête X-Ticagou-App) reçoit le jeton dans la réponse et l'envoie en
 * {@code Authorization: Bearer} (ADR 0024).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public static final String COOKIE = "ticagou_session";
    public static final String APP_HEADER = "X-Ticagou-App";

    public record LoginRequest(@NotBlank(message = "Saisissez votre adresse e-mail")
                               @Email(message = "Adresse e-mail invalide") @Size(max = 150) String email) {
    }

    public record VerifyRequest(@Size(max = 100) String token, @Size(max = 150) String email, @Size(max = 12) String code) {
    }

    private final AuthService auth;
    private final PortalProperties properties;
    private final Clock clock;

    public AuthController(AuthService auth, PortalProperties properties, Clock clock) {
        this.auth = auth;
        this.properties = properties;
        this.clock = clock;
    }

    @PostMapping("/request")
    public ResponseEntity<Map<String, String>> request(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        auth.request(request.email(), http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("message",
                "Si cette adresse est connue de la crèche, un e-mail de connexion vient de partir."));
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, String>> verify(@Valid @RequestBody VerifyRequest request, HttpServletRequest http,
                                                      @org.springframework.web.bind.annotation.RequestHeader(value = APP_HEADER, required = false) String app) {
        AuthService.Session session;
        if (request.token() != null && !request.token().isBlank()) {
            session = auth.verifyToken(request.token(), http.getRemoteAddr());
        } else if (request.email() != null && request.code() != null) {
            session = auth.verifyCode(request.email(), request.code(), http.getRemoteAddr());
        } else {
            throw ApiException.rule("verify_incomplete", "Saisissez le code reçu par e-mail.");
        }
        if (app != null && app.startsWith("parents/")) {
            // Application : jeton gardé dans le trousseau du téléphone, jamais de cookie (autre origine).
            return ResponseEntity.ok(Map.of("email", session.email(), "token", session.token(),
                    "expiresAt", session.expiresAt().toString()));
        }
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(session.token(),
                        Duration.between(clock.instant(), session.expiresAt()), properties.secureCookie()))
                .body(Map.of("email", session.email()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(value = COOKIE, required = false) String token, HttpServletRequest http) {
        auth.logout(token != null ? token : ParentSessionInterceptor.bearer(http));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO, properties.secureCookie()))
                .build();
    }

    /** Les navigateurs plafonnent la durée d'un cookie (400 jours pour Chrome) : il est rafraîchi à chaque ouverture. */
    static final Duration MAX_COOKIE_AGE = Duration.ofDays(365);

    /** Cookie de session ; sa durée suit la fin de la session (abonnement des crèches), plafonnée à un an. */
    static String cookie(String value, Duration maxAge, boolean secure) {
        var age = maxAge.isNegative() ? Duration.ZERO : maxAge.compareTo(MAX_COOKIE_AGE) > 0 ? MAX_COOKIE_AGE : maxAge;
        return ResponseCookie.from(COOKIE, value).httpOnly(true).secure(secure).sameSite("Strict")
                .path("/api/").maxAge(age).build().toString();
    }
}
