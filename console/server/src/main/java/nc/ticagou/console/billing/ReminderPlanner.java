package nc.ticagou.console.billing;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Relances, sans Spring ni base : jours ouvrés (week-end et fériés de Nouvelle-Calédonie paramétrables), date prévue de
 * chaque niveau (échéance + délai, décalée pour un client qui a un délai propre, reportée au jour ouvré suivant),
 * niveau dû pour une facture (le plus élevé atteint et pas encore préparé : rattrapage après un arrêt), modèles.
 */
public final class ReminderPlanner {

    private ReminderPlanner() {
    }

    public record Rule(int level, String label, int days, boolean active, String subject, String body, boolean copyToIssuer,
                       boolean attachPdf, boolean proposeSuspension) {
    }

    /**
     * Facture à examiner. {@code doneLevels} : niveaux déjà préparés (envoyés, en attente ou écartés : jamais deux fois).
     */
    public record Candidate(LocalDate dueOn, long remaining, boolean invoicePaused, boolean clientPaused, int clientShiftDays,
                            Set<Integer> doneLevels) {
    }

    public static boolean workingDay(LocalDate day, Set<LocalDate> holidays) {
        return day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.contains(day);
    }

    public static LocalDate nextWorkingDay(LocalDate day, Set<LocalDate> holidays) {
        var d = day;
        while (!workingDay(d, holidays)) {
            d = d.plusDays(1);
        }
        return d;
    }

    /** Jour prévu d'un niveau : le décalage du client ne s'applique qu'après l'échéance (pas au rappel courtois). */
    public static LocalDate scheduled(LocalDate dueOn, Rule rule, int clientShiftDays, Set<LocalDate> holidays) {
        var day = dueOn.plusDays(rule.days() + (rule.days() > 0 ? clientShiftDays : 0));
        return nextWorkingDay(day, holidays);
    }

    /** Niveau à préparer aujourd'hui, s'il y en a un. */
    public static Optional<Rule> due(Candidate c, List<Rule> rules, LocalDate today, Set<LocalDate> holidays) {
        if (c.remaining() <= 0 || c.invoicePaused() || c.clientPaused() || c.dueOn() == null) {
            return Optional.empty();
        }
        int highestDone = c.doneLevels().stream().max(Integer::compare).orElse(-1);
        return rules.stream()
                .filter(Rule::active)
                .filter(r -> r.level() > highestDone)
                .filter(r -> !scheduled(c.dueOn(), r, c.clientShiftDays(), holidays).isAfter(today))
                // Rappel courtois : seulement avant l'échéance ; après, ce sont les relances qui parlent.
                .filter(r -> r.days() > 0 || today.isBefore(c.dueOn()) || today.isEqual(c.dueOn()))
                .max(Comparator.comparingInt(Rule::level));
    }

    /** Prochaine relance prévue (affichage sur la facture), après les niveaux déjà préparés. */
    public static Optional<LocalDate> next(Candidate c, List<Rule> rules, Set<LocalDate> holidays) {
        if (c.remaining() <= 0 || c.invoicePaused() || c.clientPaused() || c.dueOn() == null) {
            return Optional.empty();
        }
        int highestDone = c.doneLevels().stream().max(Integer::compare).orElse(-1);
        return rules.stream().filter(Rule::active).filter(r -> r.level() > highestDone && r.days() > 0)
                .map(r -> scheduled(c.dueOn(), r, c.clientShiftDays(), holidays)).min(LocalDate::compareTo);
    }

    private static final Pattern VARIABLE = Pattern.compile("\\{([a-z_]+)}");

    /** Remplace {client}, {numero}, {total}… ; une variable inconnue reste telle quelle (visible à la relecture). */
    public static String render(String template, Map<String, String> values) {
        Matcher m = VARIABLE.matcher(template);
        var out = new StringBuilder();
        while (m.find()) {
            var value = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group() : value));
        }
        m.appendTail(out);
        return out.toString();
    }

    public static final List<String> VARIABLES = List.of("bonjour", "client", "creche", "numero", "factures", "total", "retard", "echeance",
            "paiement", "editeur");
}
