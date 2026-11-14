package nc.ticagou.portail;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Application Ti’Cagou Parents (ADR 0024) : la coque Capacitor tourne sur une autre origine que le portail
 * (capacitor://localhost, https://localhost). Aucun cookie ne traverse : l'application s'authentifie par jeton porteur.
 */
@Configuration
public class AppCorsConfig implements WebMvcConfigurer {

    private final List<String> origins;

    public AppCorsConfig(@Value("${portal.app-origins:capacitor://localhost,https://localhost}") List<String> origins) {
        this.origins = origins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/v1/**").allowedOrigins(origins.toArray(String[]::new)).allowedMethods("*")
                .allowedHeaders("*").allowCredentials(false);
    }
}
