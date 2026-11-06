package nc.ticagou.funder;

/** Ce qui détermine la tranche du barème, donc le tarif horaire d'un contrat. */
public enum TariffCriterion {
    /** Quotient familial mensuel de la famille, en XPF. */
    QUOTIENT_FAMILIAL,
    /** Volume d'accueil hebdomadaire prévu au contrat, en minutes. */
    HEURES_SEMAINE
}
