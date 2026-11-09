package nc.ticagou.config;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;

/**
 * API sans état protégée par JWT (ADR 0002). Publics : santé, documentation, premier lancement,
 * connexion. Les droits par rôle sont posés par {@code @PreAuthorize} sur les contrôleurs.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, e) -> writeProblem(response, objectMapper,
                HttpStatus.UNAUTHORIZED, "unauthenticated", "Connexion requise.");
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Flux SSE : la requête initiale est authentifiée, les reprises asynchrones aussi.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/health", "/api/v1/setup/status").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/setup", "/api/v1/auth/login",
                                "/api/v1/auth/refresh", "/api/v1/auth/logout",
                                "/api/v1/devices/pair", "/api/v1/devices/session").permitAll()
                        // Pré-inscription en ligne (ADR 0020) : formulaire public, anti-abus côté service.
                        .requestMatchers("/api/v1/public/preinscription").permitAll()
                        // Agenda du personnel (lot 4) : lien privé à jeton, lu par les applications d'agenda.
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/calendar/*").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        // Front servi par l'instance (navigateur, kiosque PWA) : fichiers statiques publics.
                        .anyRequest().permitAll())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(roleConverter()))
                        .authenticationEntryPoint(unauthorized))
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized))
                .build();
    }

    /** Claim « role » du JWT → autorité ROLE_DIRECTION, ROLE_EDUCATRICE… */
    private static Converter<Jwt, AbstractAuthenticationToken> roleConverter() {
        return jwt -> {
            var role = jwt.getClaimAsString("role");
            var authorities = role == null ? java.util.List.<SimpleGrantedAuthority>of()
                    : java.util.List.of(new SimpleGrantedAuthority("ROLE_" + role));
            return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
        };
    }

    static void writeProblem(HttpServletResponse response, ObjectMapper objectMapper, HttpStatus status, String code,
                             String detail) throws IOException {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(TiCagouProperties properties) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(properties.allowedOrigins());
        config.addAllowedMethod("*");
        config.addAllowedHeader("*");
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
