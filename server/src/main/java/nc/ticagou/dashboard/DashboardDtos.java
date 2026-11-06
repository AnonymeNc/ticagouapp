package nc.ticagou.dashboard;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Tableau de bord de la direction (maquette « matinée type »). */
public final class DashboardDtos {

    private DashboardDtos() {
    }

    /** Absence signalée d'un enfant pour aujourd'hui : motif, retour prévu, prévenue le. */
    public record ReportedAbsence(UUID childId, String name, UUID sectionId, String reason, LocalDate until,
                                  LocalDate notifiedOn, String note) {
    }

    /** Enfant qui part avant la fermeture d'après son contrat. */
    public record PlannedDeparture(UUID childId, String name, UUID sectionId, LocalTime leavesAt, boolean present) {
    }

    /** Arrivée du personnel planifiée plus tard dans la journée (« Malia Tauhavili · à 09:30 »). */
    public record StaffArrival(UUID userId, String name, UUID sectionId, LocalTime startsAt, boolean countsInRatio) {
    }

    public record Today(LocalDate day, LocalTime closesAt, List<ReportedAbsence> absences,
                        List<PlannedDeparture> departures, List<StaffArrival> staffArrivals) {
    }

    /**
     * Indicateurs. Taux et évolutions en pour mille. {@code invoicedMonth} : dernier mois facturé.
     */
    public record Kpis(YearMonth month, int fillRate, long reservedMinutes, int enrolled, int capacity,
                       YearMonth invoicedMonth, long invoiced, Integer invoicedChange, int invoices,
                       int billableFamilies, long unpaid, int unpaidCount, LocalDate oldestUnpaid,
                       int waitingList, Map<UUID, Integer> waitingBySection, int newRequests) {
    }
}
