package nc.ticagou.portail.auth;

import java.util.Arrays;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

    public ParentSessionInterceptor(AuthService auth) {
        this.auth = auth;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!HttpMethod.GET.matches(request.getMethod()) && request.getHeader(CSRF_HEADER) == null) {
            throw ApiException.forbidden("csrf", "Requête refusée.");
        }
        if (!request.getRequestURI().startsWith("/api/v1/me")) {
            return true;
        }
        var token = request.getCookies() == null ? null : Arrays.stream(request.getCookies())
                .filter(c -> AuthController.COOKIE.equals(c.getName())).map(Cookie::getValue).findFirst().orElse(null);
        var email = auth.email(token)
                .orElseThrow(() -> ApiException.unauthorized("unauthenticated", "Connexion requise."));
        request.setAttribute(EMAIL, email);
        return true;
    }
}
