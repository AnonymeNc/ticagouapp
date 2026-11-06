package nc.ticagou.dashboard;

/** Calculs des indicateurs du tableau de bord, en entiers (pour mille). */
public final class Indicators {

    private Indicators() {
    }

    /** Taux de remplissage en pour mille : heures réservées aux contrats / heures que permet la capacité. */
    public static int fillRate(long reservedMinutes, int capacity, long weeklyOpenMinutes) {
        long available = capacity * weeklyOpenMinutes;
        return available <= 0 ? 0 : (int) Math.round(reservedMinutes * 1000.0 / available);
    }

    /** Évolution en pour mille d'un mois à l'autre (nul si le mois précédent est vide). */
    public static Integer change(long current, long previous) {
        return previous <= 0 ? null : (int) Math.round((current - previous) * 1000.0 / previous);
    }
}
