package nc.ticagou.contract;

/**
 * Mode de facturation d'un contrat à planning fixe : forfait mensualisé (heures par semaine × semaines d'accueil par
 * an ÷ 12, le même montant chaque mois) ou au réel (les jours du planning du mois, à l'heure).
 */
public enum BillingMode { MENSUALISE, REEL }
