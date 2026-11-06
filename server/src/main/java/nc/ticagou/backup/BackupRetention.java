package nc.ticagou.backup;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Conservation des sauvegardes : toutes celles des {@code keepDays} derniers jours, puis la plus ancienne de chaque
 * semaine (lundi à dimanche) pendant {@code keepWeeks} semaines, puis la plus ancienne de chaque mois pendant
 * {@code keepMonths} mois. Les autres sont supprimées.
 */
public final class BackupRetention {

    private BackupRetention() {
    }

    public record Archive(String name, LocalDate day) {
    }

    public static List<Archive> toDelete(List<Archive> archives, LocalDate today, int keepDays, int keepWeeks,
                                         int keepMonths) {
        var keep = new HashSet<Archive>();
        var recentFrom = today.minusDays(keepDays - 1L);
        var weeklyFrom = monday(today).minusWeeks(keepWeeks - 1L);
        var monthlyFrom = YearMonth.from(today).minusMonths(keepMonths - 1L);
        Set<LocalDate> weeksKept = new HashSet<>();
        Set<YearMonth> monthsKept = new HashSet<>();
        for (var archive : archives.stream().sorted(Comparator.comparing(Archive::day).thenComparing(Archive::name)).toList()) {
            if (!archive.day().isBefore(recentFrom)) {
                keep.add(archive);
                continue;
            }
            var week = monday(archive.day());
            var month = YearMonth.from(archive.day());
            // Une archive peut tenir lieu à la fois de semaine et de mois : chaque ensemble est rempli indépendamment.
            var weekly = keepWeeks > 0 && !week.isBefore(weeklyFrom) && weeksKept.add(week);
            var monthly = keepMonths > 0 && !month.isBefore(monthlyFrom) && monthsKept.add(month);
            if (weekly || monthly) {
                keep.add(archive);
            }
        }
        return archives.stream().filter(a -> !keep.contains(a)).toList();
    }

    private static LocalDate monday(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
