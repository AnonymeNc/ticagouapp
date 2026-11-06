package nc.ticagou.auth;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import nc.ticagou.common.ApiException;

/**
 * Tant que la double authentification obligatoire n'est pas enregistrée, la session ne sert qu'à l'enregistrer : un
 * mot de passe volé ne donne accès à aucune donnée. Le contrôle est dans le jeton, recalculé à chaque renouvellement.
 */
@Component
public class MfaGuard implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        var path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/v1/auth/")) {
            return true;
        }
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token
                && Boolean.TRUE.equals(token.getToken().getClaimAsBoolean(CurrentUser.CLAIM_MFA_PENDING))) {
            throw ApiException.forbidden("mfa_enrollment_required", "Activez d’abord la double authentification.");
        }
        return true;
    }
}
