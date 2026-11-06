package nc.ticagou.license;

import java.util.EnumSet;
import java.util.Set;

/**
 * Modules optionnels de l'abonnement (ADR 0015). La base (enfants, familles, présences des enfants et du personnel,
 * encadrement, sauvegardes) est toujours comprise. Un module entraîne ceux dont il a besoin : pas de facture sans
 * contrat, pas d'export comptable sans facture.
 */
public enum LicenseModule {
    INSCRIPTIONS("Inscriptions et contrats", null),
    FACTURATION("Facturation et statistiques", INSCRIPTIONS),
    COMPTABILITE("Comptabilité", FACTURATION),
    PERSONNEL("Personnel", null),
    REPAS("Repas", null),
    PORTAIL_PARENTS("Portail parents", null);

    private final String label;
    private final LicenseModule requires;

    LicenseModule(String label, LicenseModule requires) {
        this.label = label;
        this.requires = requires;
    }

    public String label() {
        return label;
    }

    /** Les modules donnés et tous ceux dont ils ont besoin. */
    public static Set<LicenseModule> withRequired(Set<LicenseModule> modules) {
        var all = EnumSet.noneOf(LicenseModule.class);
        for (var module : modules) {
            for (var current = module; current != null; current = current.requires) {
                all.add(current);
            }
        }
        return all;
    }
}
