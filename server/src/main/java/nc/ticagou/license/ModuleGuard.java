package nc.ticagou.license;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import nc.ticagou.common.ApiException;

/**
 * Licence appliquée aux modifications (la lecture reste toujours permise : factures et feuilles d'heures déjà
 * produites restent consultables).
 * <ul>
 *   <li>évaluation terminée ou licence expirée depuis la fin de la grâce : toute modification est refusée (403
 *       {@code license_read_only}), sauf connexion, activation d'une clé, sauvegardes et export des données d'une
 *       famille (ADR 0011) ;</li>
 *   <li>sur un contrôleur {@link RequiresModule}, une modification est refusée (403 {@code module_absent}) si la
 *       licence ne comprend pas le module.</li>
 * </ul>
 */
@Component
public class ModuleGuard implements HandlerInterceptor {

    /** Modifications encore permises en lecture seule. */
    static final List<Pattern> READ_ONLY_ALLOWED = List.of(
            Pattern.compile("/api/v1/auth/(login|pin|refresh|logout|mfa/.*)"),
            Pattern.compile("/api/v1/setup(/.*)?"),
            Pattern.compile("/api/v1/backups/(run|restore-test)"),
            Pattern.compile("/api/v1/devices/session"));

    private final LicenseService licenses;

    public ModuleGuard(LicenseService licenses) {
        this.licenses = licenses;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || isRead(request.getMethod())) {
            return true;
        }
        var path = request.getRequestURI().substring(request.getContextPath().length());
        if (READ_ONLY_ALLOWED.stream().noneMatch(p -> p.matcher(path).matches())) {
            var status = licenses.status();
            if (status.state().readOnly()) {
                throw ApiException.forbidden("license_read_only", status.message());
            }
        }
        var required = method.getBeanType().getAnnotation(RequiresModule.class);
        if (required != null && !licenses.allows(required.value())) {
            throw ApiException.forbidden("module_absent", "Le module %s n’est pas compris dans votre abonnement."
                    .formatted(required.value().label()));
        }
        return true;
    }

    private static boolean isRead(String method) {
        return HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method) || HttpMethod.OPTIONS.matches(method);
    }
}
