package nc.ticagou.structure;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * Rubriques que l'éditeur peut masquer à la demande d'une crèche (ADR 0022). Tout est compris dans la formule
 * (ADR 0021) : masquer une rubrique simplifie seulement l'interface, rien n'est refusé ni effacé. La base (enfants,
 * présences, encadrement, paramètres, sauvegardes) ne se masque pas.
 */
public enum Rubrique {
    INSCRIPTIONS("Inscriptions et contrats"),
    FACTURATION("Facturation et statistiques"),
    COMPTABILITE("Comptabilité"),
    PERSONNEL("Personnel (planning, congés, heures)"),
    REPAS("Repas"),
    PORTAIL_PARENTS("Portail parents et messages");

    private final String label;

    Rubrique(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * Rubriques masquées d'après la colonne {@code structure.hidden_sections} (« COMPTABILITE,PERSONNEL »), avec ce qui en
     * dépend : sans inscriptions ni contrats, pas de facturation ; sans facturation, pas de comptabilité. Un nom inconnu
     * est ignoré.
     */
    public static Set<Rubrique> parse(String value) {
        var hidden = EnumSet.noneOf(Rubrique.class);
        if (value == null || value.isBlank()) {
            return hidden;
        }
        Arrays.stream(value.split(",")).map(String::strip)
                .flatMap(name -> Arrays.stream(values()).filter(r -> r.name().equals(name)))
                .forEach(hidden::add);
        if (hidden.contains(INSCRIPTIONS)) {
            hidden.add(FACTURATION);
        }
        if (hidden.contains(FACTURATION)) {
            hidden.add(COMPTABILITE);
        }
        return hidden;
    }
}
