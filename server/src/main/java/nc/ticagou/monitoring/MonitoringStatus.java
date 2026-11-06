package nc.ticagou.monitoring;

import java.time.Instant;
import java.time.LocalDate;

/**
 * État technique envoyé au service de supervision (ADR 0012). Liste blanche : seuls ces champs sortent, et aucun ne
 * contient de donnée personnelle (ni enfant, ni famille, ni personnel) — un test le vérifie.
 */
public record MonitoringStatus(String version, String database, Backup backup, Disk disk, License license,
                               Tablets tablets, Portal portal, int attendanceToReview) {

    /** Sauvegardes : réussite la plus récente, retard de plus de 48 h, dernier test de restauration. */
    public record Backup(boolean enabled, boolean toolsAvailable, boolean stale, Instant lastSuccessAt,
                         LocalDate lastTestOn, Boolean lastTestOk) {
    }

    /** Espace libre (octets) du disque des données et de celui des sauvegardes ; nul si non mesurable. */
    public record Disk(Long dataFreeBytes, Long dataTotalBytes, Long backupFreeBytes, Long backupTotalBytes) {
    }

    public record License(String state, LocalDate validUntil, LocalDate deadline, int desktops, int desktopLimit) {
    }

    /** Tablettes appairées et tablettes muettes depuis 3 jours. */
    public record Tablets(int paired, int silent) {
    }

    /** Portail parents : état, échecs de synchronisation à la suite, dernière erreur, file d'attente. */
    public record Portal(String state, int failures, String lastError, int pending) {
    }
}
