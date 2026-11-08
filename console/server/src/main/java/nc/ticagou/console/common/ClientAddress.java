package nc.ticagou.console.common;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Adresse du client de la requête en cours (déjà résolue depuis X-Forwarded-For par Tomcat derrière Caddy). */
public final class ClientAddress {

    private ClientAddress() {
    }

    public static String current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest().getRemoteAddr();
        }
        return null;
    }
}
