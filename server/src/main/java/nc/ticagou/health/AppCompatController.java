package nc.ticagou.health;

import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import nc.ticagou.config.TiCagouProperties;

/**
 * Compatibilité des applications mobiles (ADR 0027), lue sans connexion au choix de la crèche (ADR 0025) et à chaque
 * ouverture : version de l'API, versions minimales des applications, nom de la crèche à confirmer.
 */
@RestController
@RequestMapping("/api/v1/app/compat")
@Tag(name = "Santé", description = "Disponibilité du service")
public class AppCompatController {

    /** Version majeure de l'API servie ; un changement incompatible ouvrirait /api/v2 à côté (ADR 0027). */
    public static final int API_VERSION = 1;

    /**
     * @param api     version majeure de l'API
     * @param minimum version minimale par application (direction, pointage)
     * @param creche  nom de la crèche, affiché pour confirmation ; null avant l'assistant d'installation
     */
    public record AppCompat(int api, Map<String, String> minimum, String creche) {
    }

    private final TiCagouProperties.App app;
    private final JdbcTemplate jdbc;

    public AppCompatController(TiCagouProperties properties, JdbcTemplate jdbc) {
        this.app = properties.app();
        this.jdbc = jdbc;
    }

    @GetMapping
    @Operation(summary = "Versions minimales des applications mobiles et nom de la crèche")
    public AppCompat compat() {
        var names = jdbc.queryForList("SELECT name FROM structure ORDER BY created_at LIMIT 1", String.class);
        return new AppCompat(API_VERSION, Map.of("direction", app.minDirection(), "pointage", app.minPointage()),
                names.isEmpty() ? null : names.getFirst());
    }
}
