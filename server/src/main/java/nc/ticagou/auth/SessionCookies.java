package nc.ticagou.auth;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletResponse;
import nc.ticagou.auth.AuthDtos.SessionResponse;
import nc.ticagou.config.TiCagouProperties;

/**
 * Jeton de rafraîchissement du navigateur dans un cookie HttpOnly : une faille XSS ne peut pas le lire, et
 * SameSite=Strict écarte les requêtes venues d'un autre site. La tablette n'en a pas : sa session reste en mémoire.
 */
@Component
public class SessionCookies {

    public static final String NAME = "ticagou_session";
    /** Demande du navigateur : jeton dans le cookie, et non dans la réponse. */
    public static final String HEADER = "X-Ticagou-Session";
    private static final String PATH = "/api/v1/auth";

    private final Duration ttl;

    public SessionCookies(TiCagouProperties properties) {
        this.ttl = properties.security().refreshTokenTtl();
    }

    /** Pose le cookie et retire le jeton de la réponse. */
    public SessionResponse apply(SessionResponse session, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(session.refreshToken(), ttl).toString());
        return session.withoutRefreshToken();
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
    }

    private static ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(true).sameSite("Strict").path(PATH).maxAge(maxAge).build();
    }
}
