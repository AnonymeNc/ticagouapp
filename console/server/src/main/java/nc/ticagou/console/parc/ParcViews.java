package nc.ticagou.console.parc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import nc.ticagou.console.audit.AuditService;
import nc.ticagou.console.parc.ParcRepository.Client;
import nc.ticagou.console.parc.ParcRepository.Note;

/** Réponses de l'API du parc. */
public final class ParcViews {

    private ParcViews() {
    }

    /** Une ligne de la vue du parc. */
    public record Row(String slug, String name, String city, String clientName, String lifecycle, Health health,
                      Instant lastContactAt, String version, String plan, Integer places,
                      Integer activeChildren, String licenseState, LocalDate validUntil, LocalDate deadline,
                      Integer desktops, Integer desktopLimit, Integer tablets, Integer tabletLimit,
                      Instant backupAt, boolean backupStale, Integer diskUsedPercent, String container,
                      List<AlertView> alerts, Instant lastCallAt, long balanceDue, long daysLate) {
    }

    public record AlertView(long id, String kind, String label, String detail, Instant openedAt, Instant closedAt) {
    }

    /** Bandeau du haut et santé du parc. */
    public record Summary(int total, int ok, int warn, int bad, int off, int evaluationsEnding, int renewals,
                          int versionsBehind, String latestVersion, int unpaid) {
    }

    /** Dernières lectures des sources (pied de la barre latérale). */
    public record Sources(Instant supervisionAt, Instant supervisionAttemptAt, String supervisionError, Instant hostAt,
                          String hostError, List<String> unlinkedSites, List<String> rejectedSites, Long hostDiskFree,
                          Long hostDiskTotal) {
    }

    public record Parc(List<Row> creches, Summary summary, Sources sources) {
    }

    /** Point quotidien des courbes de la fiche. */
    public record Point(LocalDate day, Integer diskUsedPercent, Long dataFree, Integer activeChildren, Integer attendances7d,
                        Integer activeUsers30d) {
    }

    public record Detail(Row row, String domain, LocalDate clientSince, Instant createdAt, String supervisionName,
                         Instant hostSeenAt, Client client, JsonNode status, JsonNode subscription, List<AlertView> history,
                         List<Point> points, List<Note> notes, List<AuditService.Event> journal, List<String> hiddenSections) {
    }

    public record SearchHit(String slug, String name, String city, String clientName, Health.Level level) {
    }
}
