package nc.ticagou.staffing;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Taux d'encadrement, sans dépendance à Spring. Chaque enfant présent compte pour 1/n adulte, n étant le
 * ratio de sa tranche d'âge à la date du jour ; les adultes requis sont l'arrondi supérieur de la somme.
 * Calcul exact en entiers sur le PPCM des ratios (pas de virgule flottante).
 */
public final class RatioCalculator {

    private RatioCalculator() {
    }

    /** Règle datée : tranche [minAgeMonths, maxAgeMonths[ (maxAgeMonths nul = sans limite). */
    public record Rule(String label, int minAgeMonths, Integer maxAgeMonths, int childrenPerAdult,
                       LocalDate validFrom, LocalDate validTo) {

        public boolean isValidOn(LocalDate day) {
            return !day.isBefore(validFrom) && (validTo == null || !day.isAfter(validTo));
        }

        public boolean coversAge(long months) {
            return months >= minAgeMonths && (maxAgeMonths == null || months < maxAgeMonths);
        }
    }

    /** Enfant présent, rattaché à la section où il a été pointé. */
    public record PresentChild(UUID sectionId, LocalDate birthDate) {
    }

    /** Adulte présent qui compte dans l'encadrement, dans la section où il a pointé. */
    public record PresentAdult(UUID sectionId) {
    }

    public enum Status { CONFORME, LIMITE, INSUFFISANT }

    /**
     * Charge d'une section ou de la structure.
     *
     * @param coveredPlaces enfants que l'effectif présent peut accueillir, à composition égale
     * @param missingRule   au moins un enfant sans règle applicable (la plus exigeante a été retenue)
     */
    public record Load(int children, int adults, int required, int coveredPlaces, Status status, boolean missingRule) {
    }

    public record Result(LocalDate day, Map<UUID, Load> sections, Load global) {
    }

    /** Ratio applicable à un enfant, ou {@code -1} si aucune règle ne couvre son âge ce jour-là. */
    public static int childrenPerAdult(List<Rule> rules, LocalDate birthDate, LocalDate day) {
        long months = ChronoUnit.MONTHS.between(birthDate, day);
        return rules.stream()
                .filter(r -> r.isValidOn(day) && r.coversAge(months))
                .mapToInt(Rule::childrenPerAdult)
                .min()
                .orElse(-1);
    }

    /** Ratio le plus exigeant parmi les règles valides ce jour-là (8 si aucune règle n'est saisie). */
    public static int strictest(List<Rule> rules, LocalDate day) {
        return rules.stream().filter(r -> r.isValidOn(day)).mapToInt(Rule::childrenPerAdult).min().orElse(8);
    }

    public static Result compute(LocalDate day, List<Rule> rules, Collection<UUID> sectionIds,
                                 List<PresentChild> children, List<PresentAdult> adults) {
        int fallback = strictest(rules, day);
        var ratiosBySection = new LinkedHashMap<UUID, ArrayList<Integer>>();
        var missingBySection = new LinkedHashMap<UUID, Boolean>();
        for (var id : sectionIds) {
            ratiosBySection.put(id, new ArrayList<>());
            missingBySection.put(id, false);
        }
        var allRatios = new ArrayList<Integer>();
        boolean anyMissing = false;
        for (var child : children) {
            int n = childrenPerAdult(rules, child.birthDate(), day);
            boolean missing = n < 0;
            if (missing) {
                n = fallback;
                anyMissing = true;
            }
            allRatios.add(n);
            if (child.sectionId() != null) {
                ratiosBySection.computeIfAbsent(child.sectionId(), k -> new ArrayList<>()).add(n);
                if (missing) {
                    missingBySection.put(child.sectionId(), true);
                }
            }
        }
        var sections = new LinkedHashMap<UUID, Load>();
        for (var entry : ratiosBySection.entrySet()) {
            int sectionAdults = (int) adults.stream().filter(a -> Objects.equals(a.sectionId(), entry.getKey())).count();
            sections.put(entry.getKey(), load(entry.getValue(), sectionAdults, fallback,
                    missingBySection.getOrDefault(entry.getKey(), false)));
        }
        return new Result(day, sections, load(allRatios, adults.size(), fallback, anyMissing));
    }

    /**
     * Charge pour des enfants de ratios donnés et un nombre d'adultes.
     *
     * @param fallbackRatio ratio servant aux places couvertes quand aucun enfant n'est présent
     */
    public static Load load(List<Integer> ratios, int adults, int fallbackRatio, boolean missingRule) {
        int children = ratios.size();
        int strictestPresent = ratios.stream().mapToInt(Integer::intValue).min().orElse(fallbackRatio);
        long lcm = strictestPresent;
        for (int n : ratios) {
            lcm = lcm(lcm, n);
        }
        long shares = 0;
        for (int n : ratios) {
            shares += lcm / n;
        }
        long capacity = adults * lcm;
        int required = (int) ceilDiv(shares, lcm);
        long strongestShare = lcm / strictestPresent;
        int covered = (int) (children + Math.floorDiv(capacity - shares, strongestShare));
        Status status;
        if (capacity < shares) {
            status = Status.INSUFFISANT;
        } else if (children > 0 && covered - children <= 1) {
            status = Status.LIMITE;
        } else {
            status = Status.CONFORME;
        }
        return new Load(children, adults, required, Math.max(covered, 0), status, missingRule);
    }

    private static long ceilDiv(long a, long b) {
        return -Math.floorDiv(-a, b);
    }

    private static long gcd(long a, long b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    private static long lcm(long a, long b) {
        return a / gcd(a, b) * b;
    }
}
