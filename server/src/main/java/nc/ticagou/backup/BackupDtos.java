package nc.ticagou.backup;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Paramètres › Sauvegardes. */
public final class BackupDtos {

    private BackupDtos() {
    }

    public enum Kind { AUTOMATIQUE, MANUELLE, TEST, AVANT_MIGRATION }

    public enum Status { EN_COURS, REUSSIE, ECHEC }

    public record RunDto(UUID id, Kind kind, String author, Instant startedAt, Instant finishedAt, Status status,
                         Long sizeBytes, String fileName, int copies, String detail) {
    }

    /**
     * @param toolsAvailable pg_dump et pg_restore trouvés sur le serveur
     * @param stale          aucune sauvegarde réussie depuis 48 h (ou jamais, 48 h après l'installation)
     */
    public record Overview(boolean enabled, LocalTime dailyTime, int keepDays, int keepWeeks, int keepMonths,
                           LocalDate lastTestOn, Boolean lastTestOk, boolean toolsAvailable, boolean running,
                           RunDto lastSuccess, Instant nextRun, boolean stale, List<RunDto> history) {
    }
}
