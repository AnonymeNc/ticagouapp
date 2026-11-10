package nc.ticagou.funder;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Formule de facturation d'un barème : forfait mensuel nommé (« Temps plein », « Matins seulement », « 3 jours par
 * semaine »…), en francs CFP entiers. Un contrat qui la choisit est facturé de ce montant chaque mois, au lieu du
 * calcul à l'heure.
 */
@Embeddable
public record TariffFormula(
        @Column(name = "label", nullable = false) String label,
        @Column(name = "monthly_fee", nullable = false) long monthlyFee) {
}
