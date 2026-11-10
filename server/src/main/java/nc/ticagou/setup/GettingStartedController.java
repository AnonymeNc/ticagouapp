package nc.ticagou.setup;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import nc.ticagou.auth.CurrentUser;
import nc.ticagou.structure.Rubrique;
import nc.ticagou.structure.RubriqueController;

/**
 * Prise en main (lot 9) : la liste « Premiers pas » de la direction, calculée d'après l'état réel de la crèche, et les
 * coordonnées du support affichées dans l'aide (réglées par l'hébergeur, {@code TICAGOU_SUPPORT_*}).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Prise en main", description = "Premiers pas et support")
public class GettingStartedController {

    /** Une étape : faite ou non, et l'écran où la faire. */
    public record Step(String key, String label, boolean done, String path) {
    }

    /** Coordonnées du support ; une valeur vide n'est pas affichée. */
    public record Support(String phone, String whatsapp, String email, String hours, String delay) {
    }

    private final JdbcTemplate jdbc;
    private final RubriqueController.Lookup rubriques;
    private final Support support;

    public GettingStartedController(JdbcTemplate jdbc, RubriqueController.Lookup rubriques, @Value("${TICAGOU_SUPPORT_PHONE:}") String phone,
                                    @Value("${TICAGOU_SUPPORT_WHATSAPP:}") String whatsapp,
                                    @Value("${TICAGOU_SUPPORT_EMAIL:}") String email,
                                    @Value("${TICAGOU_SUPPORT_HOURS:}") String hours,
                                    @Value("${TICAGOU_SUPPORT_DELAY:}") String delay) {
        this.jdbc = jdbc;
        this.rubriques = rubriques;
        this.support = new Support(blank(phone), blank(whatsapp), blank(email), blank(hours), blank(delay));
    }

    @GetMapping("/support")
    @Operation(summary = "Coordonnées du support Ti'Cagou et délais de réponse")
    public Support support() {
        return support;
    }

    @GetMapping("/getting-started")
    @PreAuthorize("hasRole('DIRECTION')")
    @Operation(summary = "Premiers pas : ce qui reste à mettre en place dans la crèche")
    public List<Step> steps() {
        var structureId = CurrentUser.get().structureId();
        var steps = new ArrayList<Step>();
        // Rubriques masquées à la demande de la crèche (ADR 0022) : pas d'étape pour ce qu'elle n'utilise pas.
        var hidden = rubriques.hidden(structureId);
        steps.add(new Step("structure", "Compléter la fiche de la structure (adresse, agrément)",
                exists("SELECT 1 FROM structure WHERE id = ? AND address IS NOT NULL AND agrement_number IS NOT NULL", structureId),
                "/parametres?onglet=structure"));
        // Le barème vient tôt : sans lui, aucun contrat d'accueil ne peut être établi, donc aucune inscription aboutir.
        if (!hidden.contains(Rubrique.INSCRIPTIONS)) {
            steps.add(new Step("bareme", "Saisir le barème des tarifs : indispensable avant d’établir un contrat d’inscription",
                    exists("SELECT 1 FROM tariff_schedule WHERE structure_id = ? AND (valid_to IS NULL OR valid_to >= CURRENT_DATE)",
                            structureId), "/parametres?onglet=bareme"));
        }
        steps.add(new Step("encadrement", "Vérifier les règles d’encadrement de l’agrément",
                exists("SELECT 1 FROM ratio_rule WHERE structure_id = ?", structureId), "/parametres?onglet=encadrement"));
        steps.add(new Step("enfants", "Ajouter les enfants (ou reprendre un fichier Excel)",
                exists("SELECT 1 FROM child WHERE structure_id = ?", structureId), "/enfants"));
        steps.add(new Step("equipe", "Créer les comptes de l’équipe",
                count("SELECT count(*) FROM app_user WHERE structure_id = ? AND active", structureId) > 1, "/parametres?onglet=utilisateurs"));
        steps.add(new Step("tablette", "Appairer la tablette de l’accueil",
                exists("SELECT 1 FROM device WHERE structure_id = ? AND revoked_at IS NULL", structureId), "/parametres?onglet=appareils"));
        if (!hidden.contains(Rubrique.PORTAIL_PARENTS)) {
            steps.add(new Step("portail", "Ouvrir le portail aux parents",
                    exists("SELECT 1 FROM portal_settings WHERE structure_id = ? AND state = 'ACTIF'", structureId), "/parametres?onglet=portail"));
        }
        steps.add(new Step("sauvegarde", "Vérifier qu’une sauvegarde a réussi",
                exists("SELECT 1 FROM backup_run WHERE status = 'REUSSIE'", null), "/parametres?onglet=sauvegardes"));
        return steps;
    }

    private boolean exists(String sql, Object id) {
        var rows = id == null ? jdbc.queryForList(sql) : jdbc.queryForList(sql, id);
        return !rows.isEmpty();
    }

    private int count(String sql, Object id) {
        return jdbc.queryForObject(sql, Integer.class, id);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
