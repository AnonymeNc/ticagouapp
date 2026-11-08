package nc.ticagou.console.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import nc.ticagou.console.ConsoleProperties;

/**
 * Sécurité de la console (ADR 0017) : session côté serveur ({@code CONSOLE_SESSION}, HttpOnly, Secure, SameSite=Strict),
 * jeton CSRF lu dans le cookie {@code XSRF-TOKEN} et renvoyé dans l'en-tête {@code X-XSRF-TOKEN}, API fermée sans
 * connexion complète (mot de passe et code), durée maximale d'une session.
 */
@Configuration
class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ConsoleProperties properties, SecurityContextRepository contexts,
                                            Clock clock) throws Exception {
        var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookieCustomizer(cookie -> cookie.sameSite("Strict").secure(properties.secureCookie()));
        http.csrf(c -> c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .addFilterAfter(new SessionAgeFilter(Duration.ofHours(properties.sessionMaxHours()), clock), SecurityContextHolderFilter.class)
                .securityContext(s -> s.securityContextRepository(contexts))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/v1/auth/**", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/**", "/actuator/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) ->
                                problem(response, 401, "unauthenticated", "Connectez-vous."))
                        .accessDeniedHandler((request, response, ex) ->
                                problem(response, 403, "csrf", "Page expirée : rechargez-la puis recommencez.")))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable);
        return http.build();
    }

    static void problem(HttpServletResponse response, int status, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"status\":%d,\"code\":\"%s\",\"detail\":\"%s\"}".formatted(status, code, detail));
    }

    /** Le jeton CSRF est différé : le lire suffit à poser le cookie dès la première réponse. */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }

    /** Au-delà de la durée maximale depuis la connexion, la session est fermée, même active. */
    static final class SessionAgeFilter extends OncePerRequestFilter {
        private final Duration max;
        private final Clock clock;

        SessionAgeFilter(Duration max, Clock clock) {
            this.max = max;
            this.clock = clock;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            var session = request.getSession(false);
            if (session != null && session.getAttribute(SessionKeys.LOGIN_AT) instanceof Instant loginAt
                    && loginAt.plus(max).isBefore(clock.instant())) {
                session.invalidate();
                SecurityContextHolder.clearContext();
            }
            chain.doFilter(request, response);
        }
    }
}
