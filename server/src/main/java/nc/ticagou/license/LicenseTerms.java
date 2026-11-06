package nc.ticagou.license;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import nc.ticagou.common.Formats;

/**
 * Règles de la licence (ADR 0011), sans accès à la base : l'état d'une installation un jour donné.
 * <ul>
 *   <li>sans clé, l'évaluation dure {@code evaluationDays} jours depuis l'installation, puis tout passe en lecture
 *       seule ;</li>
 *   <li>une licence est à renouveler {@value #RENEWAL_DAYS} jours avant son échéance ;</li>
 *   <li>après l'échéance, {@code graceDays} jours de grâce : tout fonctionne, sauf l'appairage de tablettes ;</li>
 *   <li>ensuite, lecture seule : seules restent possibles les sauvegardes, l'export des données d'une famille et
 *       l'activation d'une nouvelle clé ({@link ModuleGuard}).</li>
 * </ul>
 */
public record LicenseTerms(int evaluationDays, int graceDays) {

    /** Bandeau de renouvellement à partir de 30 jours avant l'échéance. */
    public static final int RENEWAL_DAYS = 30;

    /** PC du bureau permis pendant l'évaluation. */
    public static final int EVALUATION_DESKTOPS = 2;

    public enum State {
        EVALUATION, EVALUATION_TERMINEE, ACTIVE, A_RENOUVELER, EXPIREE_GRACE, LECTURE_SEULE;

        public boolean readOnly() {
            return this == EVALUATION_TERMINEE || this == LECTURE_SEULE;
        }

        /** Licence expirée (pendant la grâce ou après) : plus d'appairage de tablettes. */
        public boolean expired() {
            return this == EXPIREE_GRACE || this == LECTURE_SEULE;
        }
    }

    /**
     * @param deadline date à partir de laquelle la saisie sera bloquée (fin de l'évaluation ou fin de la grâce) ;
     *                 nulle pour une licence en cours de validité
     * @param daysLeft jours restants avant {@code deadline}, ou avant l'échéance de la licence si elle est à renouveler
     */
    public record Status(State state, LocalDate deadline, long daysLeft, String message) {
    }

    /**
     * @param installedOn jour de la première installation (assistant de démarrage)
     * @param today       jour courant, jamais antérieur au plus grand jour déjà vu par le serveur
     */
    public Status status(LocalDate installedOn, LocalDate today, License license) {
        if (license == null) {
            var end = installedOn.plusDays(evaluationDays);
            if (today.isBefore(end)) {
                long left = ChronoUnit.DAYS.between(today, end);
                return new Status(State.EVALUATION, end, left, "Version d’évaluation jusqu’au %s (encore %s) : contactez Ti’Cagou pour souscrire."
                        .formatted(Formats.date(end.minusDays(1)), days(left)));
            }
            return new Status(State.EVALUATION_TERMINEE, end, 0, "L’évaluation est terminée depuis le %s : Ti’Cagou est en lecture seule. Contactez Ti’Cagou pour souscrire."
                    .formatted(Formats.date(end.minusDays(1))));
        }
        var validUntil = license.validUntil();
        if (!today.isAfter(validUntil)) {
            long left = ChronoUnit.DAYS.between(today, validUntil);
            if (left < RENEWAL_DAYS) {
                return new Status(State.A_RENOUVELER, null, left, "Abonnement valable jusqu’au %s, encore %s."
                        .formatted(Formats.date(validUntil), days(left)));
            }
            return new Status(State.ACTIVE, null, left, null);
        }
        var blockedFrom = validUntil.plusDays(graceDays + 1L);
        if (today.isBefore(blockedFrom)) {
            long left = ChronoUnit.DAYS.between(today, blockedFrom);
            return new Status(State.EXPIREE_GRACE, blockedFrom, left, "Abonnement échu le %s. Renouvelez-le : Ti’Cagou passera en lecture seule le %s (dans %s)."
                    .formatted(Formats.date(validUntil), Formats.date(blockedFrom), days(left)));
        }
        return new Status(State.LECTURE_SEULE, blockedFrom, 0, "Abonnement échu le %s : Ti’Cagou est en lecture seule. Contactez Ti’Cagou pour le renouveler."
                .formatted(Formats.date(validUntil)));
    }

    private static String days(long n) {
        return n <= 1 ? n + " jour" : n + " jours";
    }
}
