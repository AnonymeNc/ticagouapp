package nc.ticagou.console.reporting;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

import nc.ticagou.console.billing.Xpf;

/** Calculs du reporting, sans Spring ni base : tranches de la balance âgée, période en mois, coût du support, départs. */
public final class Reporting {

    private Reporting() {
    }

    /** Tranches de la balance âgée, selon les jours de retard après l'échéance. */
    public enum Bucket { NON_ECHU, J30, J60, J90, PLUS_90 }

    /** Échue le jour même : pas encore en retard. 1 à 30 jours, 31 à 60, 61 à 90, au-delà. */
    public static Bucket bucket(long daysLate) {
        if (daysLate <= 0) {
            return Bucket.NON_ECHU;
        }
        if (daysLate <= 30) {
            return Bucket.J30;
        }
        if (daysLate <= 60) {
            return Bucket.J60;
        }
        return daysLate <= 90 ? Bucket.J90 : Bucket.PLUS_90;
    }

    public static long daysLate(LocalDate dueOn, LocalDate asOf) {
        return dueOn == null ? 0 : ChronoUnit.DAYS.between(dueOn, asOf);
    }

    /** Mois civils touchés par la période (01/01 au 09/11 : 11), au moins 1 : un temps saisi y est ramené au mois. */
    public static int months(LocalDate from, LocalDate to) {
        return (int) Math.max(1, ChronoUnit.MONTHS.between(YearMonth.from(from), YearMonth.from(to)) + 1);
    }

    /** Coût mensuel du support : minutes saisies sur la période, ramenées au mois, au taux horaire ; arrondi au franc. */
    public static long supportCost(long minutes, int months, long hourlyRate) {
        return Xpf.divide(minutes * hourlyRate, 60L * months);
    }

    /** Taux de départ en dixièmes de pour cent ; nul sans crèche cliente au début de la période. */
    public static Integer churnTenths(int departures, int base) {
        if (base == 0) {
            return departures == 0 ? Integer.valueOf(0) : null;
        }
        return Integer.valueOf((int) Xpf.divide(departures * 1000L, base));
    }
}
