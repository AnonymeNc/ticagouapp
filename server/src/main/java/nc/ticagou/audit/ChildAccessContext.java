package nc.ticagou.audit;

/** Écran ou usage ayant donné accès au dossier d'un enfant. */
public enum ChildAccessContext {
    /** Fiche enfant (identité, famille). */
    FICHE,
    /** Informations de santé : allergies, PAI, régime, vaccins. */
    SANTE,
    /** Documents joints. */
    DOCUMENTS,
    /** Export des données de la famille. */
    EXPORT,
    /** Pointage : santé affichée à l'arrivée de l'enfant (tablette ou bureau). */
    POINTAGE,
    /** Transmissions du jour et messages de la famille. */
    JOURNAL
}
