package nc.ticagou.structure;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import nc.ticagou.auth.CurrentDevice;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.common.ApiException;

/** Rubriques masquées de la structure (ADR 0022), pour les postes du bureau comme pour les tablettes. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Structure", description = "Rubriques masquées à la demande de la crèche")
public class RubriqueController {

    /** {@code hidden} : rubriques masquées, dépendances comprises ; {@code labels} : leurs libellés, dans le même ordre. */
    public record Rubriques(List<Rubrique> hidden, List<String> labels) {
    }

    private final Lookup lookup;

    public RubriqueController(Lookup lookup) {
        this.lookup = lookup;
    }

    @GetMapping("/rubriques")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Rubriques masquées (menus, onglets, cartes à ne pas afficher)")
    public Rubriques rubriques() {
        var structureId = CurrentUser.find().map(CurrentUser::structureId)
                .or(() -> CurrentDevice.find().map(CurrentDevice::structureId))
                .orElseThrow(() -> ApiException.forbidden("forbidden", "Connexion requise."));
        var hidden = lookup.hidden(structureId).stream().toList();
        return new Rubriques(hidden, hidden.stream().map(Rubrique::label).toList());
    }

    /** Lecture des rubriques masquées d'une structure. */
    @Component
    public static class Lookup {

        private final JdbcTemplate jdbc;

        public Lookup(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        public Set<Rubrique> hidden(UUID structureId) {
            var values = jdbc.queryForList("SELECT hidden_sections FROM structure WHERE id = ?", String.class, structureId);
            return Rubrique.parse(values.isEmpty() ? null : values.getFirst());
        }
    }
}
