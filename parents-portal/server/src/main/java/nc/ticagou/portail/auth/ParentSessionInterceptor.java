package nc.ticagou.portail.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import nc.ticagou.portail.PortalProperties;
import nc.ticagou.portail.common.ApiException;

/**
 * Session des parents sur {@code /api/v1/me/**} (cookie HttpOnly). Toute écriture de l'API parents exige l'en-tête
 * {@code X-Ticagou-Portail} : un formulaire d'un autre site ne peut pas l'ajouter (protection CSRF, en plus de
 * SameSite=Strict).
 */
@Configuration
public class ParentSessionInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    public static final String EMAIL = "parentEmail";
    public static final String CSRF_HEADER = "X-Ticagou-Portail";

    private final AuthService auth;
    private final PortalProperties properties;
    private final Clock clock;
    private final MediaTokens media;

    public ParentSessionInterceptor(AuthService auth, PortalProperties properties, Clock clock, MediaTokens media) {
        this.media = media;
        this.auth = auth;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/**");
    }

    static boolean isMedia(String uri) {
        return uri.startsWith("/api/v1/me/files/") || uri.matches("/api/v1/me/children/[^/]+/[^/]+/photo");
    }

    /** « Authorization: Bearer <jeton> », ou null. */
    public static String bearer(HttpServletRequest request) {
        var header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7).strip() : null;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Requête préalable CORS de l’application : ni session ni en-tête anti-CSRF, Spring répond seul.
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        if (!HttpMethod.GET.matches(request.getMethod()) && request.getHeader(CSRF_HEADER) == null) {
            throw ApiException.forbidden("csrf", "Requête refusée.");
        }
        if (!request.getRequestURI().startsWith("/api/v1/me")) {
            return true;
        }
        var cookie = request.getCookies() == null ? null : Arrays.stream(request.getCookies())
                .filter(c -> AuthController.COOKIE.equals(c.getName())).map(Cookie::getValue).findFirst().orElse(null);
        // Application Ti’Cagou Parents : jeton porteur (ADR 0024) ; navigateur : cookie.
        var token = cookie != null ? cookie : bearer(request);
        // Image, vidéo ou fichier ouvert par l’application : jeton de média dans l’adresse, lecture seule.
        if (token == null && HttpMethod.GET.matches(request.getMethod()) && isMedia(request.getRequestURI())) {
            var email = media.verify(request.getParameter("jeton"));
            if (email.isPresent()) {
                request.setAttribute(EMAIL, email.get());
                return true;
            }
        }
        var session = auth.active(token)
                .orElseThrow(() -> ApiException.unauthorized("unauthenticated", "Connexion requise."));
        request.setAttribute(EMAIL, session.email());
        if ("/api/v1/me".equals(request.getRequestURI()) && cookie != null) {
            // Profil lu à l'ouverture de l'application : le cookie suit un renouvellement d'abonnement.
            response.addHeader(HttpHeaders.SET_COOKIE, AuthController.cookie(token,
                    Duration.between(clock.instant(), session.expiresAt()), properties.secureCookie()));
        }
        return true;
    }
}
