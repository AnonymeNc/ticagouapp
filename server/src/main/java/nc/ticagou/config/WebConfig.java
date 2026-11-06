package nc.ticagou.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import nc.ticagou.auth.MfaGuard;
import nc.ticagou.license.ModuleGuard;

/**
 * L'instance sert aussi le front : le bureau dans le navigateur, les tablettes sur {@code /kiosk}, installé en PWA. Toute adresse qui n'est ni un fichier ni l'API renvoie {@code index.html}
 * (routes du front).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    private final TiCagouProperties properties;
    private final ModuleGuard moduleGuard;
    private final MfaGuard mfaGuard;

    public WebConfig(TiCagouProperties properties, ModuleGuard moduleGuard, MfaGuard mfaGuard) {
        this.moduleGuard = moduleGuard;
        this.mfaGuard = mfaGuard;
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(mfaGuard).addPathPatterns("/api/**");
        registry.addInterceptor(moduleGuard).addPathPatterns("/api/**");
    }

    /** La racine n'est pas une ressource pour le gestionnaire de fichiers : sans cela, elle répond 404. */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        var configured = properties.web() == null ? "" : properties.web().root();
        var candidates = configured.isBlank() ? List.of("../web/dist", "web/dist") : List.of(configured);
        var root = candidates.stream().map(c -> Path.of(c).toAbsolutePath().normalize())
                .filter(p -> Files.isRegularFile(p.resolve("index.html"))).findFirst().orElse(null);
        if (root == null) {
            log.info("Front non servi : aucun build dans {} (pnpm build dans web/).", candidates);
            return;
        }
        log.info("Front servi depuis {}", root);
        registry.addResourceHandler("/**")
                .addResourceLocations(new FileSystemResource(root.toString() + "/"))
                .resourceChain(false)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        if (resourcePath.startsWith("api/") || resourcePath.startsWith("actuator/")) {
                            return null;
                        }
                        var requested = location.createRelative(resourcePath);
                        if (!resourcePath.isEmpty() && requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        // Fichier manquant sous /assets : vraie erreur 404, pas la page du front.
                        return resourcePath.startsWith("assets/") ? null : location.createRelative("index.html");
                    }
                });
    }
}
