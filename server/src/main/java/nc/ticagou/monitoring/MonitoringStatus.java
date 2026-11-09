package nc.ticagou.monitoring;

import java.time.Instant;
import java.time.LocalDate;

/**
 * État technique envoyé au service de supervision (ADR 0012). Liste blanche : seuls ces champs sortent, et aucun ne
 * contient de donnée personnelle (ni enfant, ni famille, ni personnel) — un test le vérifie. {@code group} n'apparaît que
 * si la direction a rejoint un groupe (lot 7) : le nom de la crèche, son adresse et des indicateurs agrégés.
 */
public record MonitoringStatus(String version, String database, Backup backup, Disk disk, License license,
                               Tablets tablets, Portal portal, int attendanceToReview, Usage usage,
                               @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                               Group group) {

    /** Indicateurs partagés avec les autres crèches du groupe : des nombres, rien sur les personnes. */
    public record Group(String codeHash, String name, String url, int activeChildren, Integer places, Integer occupancyPercent,
                        long invoicedLastMonth, long outstanding, int ratioBreaches7d, LocalDate asOf) {
    }

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

    /**
     * Usage, pour l'éditeur (console, ADR 0017) : des nombres et une date, jamais un nom ni un identifiant.
     *
     * @param activeChildren          enfants entrés et pas encore sortis ce jour
     * @param places                  places du contrat d'abonnement ; nul pendant l'évaluation
     * @param activeUsers30d          comptes actifs connectés depuis 30 jours
     * @param lastDirectionLoginOn    jour de la dernière connexion d'un compte de direction
     * @param attendances7d           pointages d'arrivée des 7 derniers jours
     * @param familyInvoicesLastMonth factures aux familles du mois dernier
     */
    public record Usage(int activeChildren, Integer places, int activeUsers30d, LocalDate lastDirectionLoginOn,
                        int attendances7d, int familyInvoicesLastMonth) {
    }
}
