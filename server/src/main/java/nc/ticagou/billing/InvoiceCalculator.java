package nc.ticagou.billing;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import nc.ticagou.billing.Xpf.Mode;
import nc.ticagou.contract.BillingMode;

/**
 * Facture du mois d'une famille, sans dépendance à Spring. Pour chaque enfant :
 * <ul>
 *   <li>forfait de chaque version de contrat, au prorata des jours d'ouverture qu'elle couvre (avenant, début ou
 *       fin de contrat en cours de mois) ; frais d'inscription sur la première facture ; adaptation du mois ;</li>
 *   <li>heures complémentaires : temps pointé hors du planning au-delà de la tolérance, arrondi ;</li>
 *   <li>dépassements : départ après la fermeture au-delà de la tolérance, au forfait par occurrence ;</li>
 *   <li>absences déductibles : jours prévus, carence, préavis, justificatif, jamais plus que le forfait ;</li>
 *   <li>aides des financeurs par priorité, plafonnées, sans que le reste à charge devienne négatif ;</li>
 *   <li>TGC par ligne selon la prestation.</li>
 * </ul>
 * Tous les arrondis passent par {@link Xpf}.
 */
public final class InvoiceCalculator {

    private InvoiceCalculator() {
    }

    // --- Entrées --------------------------------------------------------------------------------

    public enum Service { FORFAIT, HEURES_COMPLEMENTAIRES, DEPASSEMENT, ADAPTATION, FRAIS_INSCRIPTION }

    public enum ExtraRateMode { CONTRAT, FIXE }

    public enum AdaptationBilling { AU_TEMPS, PREMIERE_HEURE_OFFERTE, OFFERTE }

    public enum Calculation { MONTANT_FIXE, POURCENTAGE }

    public enum RateBase { FRAIS_GARDE, FORFAIT }

    public record Rules(ExtraRateMode extraRateMode, long fixedExtraRate, int extraToleranceMinutes,
                        int extraRoundingMinutes, long overtimeFee, int overtimeToleranceMinutes, int sickWaitingDays,
                        int leaveNoticeDays, int totalRoundingStep, Mode totalRoundingMode) {
    }

    public record DayPlan(int dayOfWeek, LocalTime arrives, LocalTime leaves) {

        int minutes() {
            return (int) Duration.between(arrives, leaves).toMinutes();
        }
    }

    /**
     * Version de contrat signée, ramenée aux jours qu'elle couvre dans le mois. {@code billingMode} : forfait mensualisé
     * (même montant chaque mois) ou facturation au réel des jours du planning du mois (planning fixe seulement).
     * {@code formula} : formule de facturation du barème choisie au contrat (forfait mensuel nommé), nulle sinon.
     */
    public record VersionPeriod(String label, LocalDate from, LocalDate to, boolean fixed, long monthlyFee,
                                long hourlyRate, List<DayPlan> days, int monthlyReservedMinutes,
                                BillingMode billingMode, String formula) {

        public VersionPeriod(String label, LocalDate from, LocalDate to, boolean fixed, long monthlyFee,
                             long hourlyRate, List<DayPlan> days, int monthlyReservedMinutes, BillingMode billingMode) {
            this(label, from, to, fixed, monthlyFee, hourlyRate, days, monthlyReservedMinutes, billingMode, null);
        }

        public VersionPeriod(String label, LocalDate from, LocalDate to, boolean fixed, long monthlyFee,
                             long hourlyRate, List<DayPlan> days, int monthlyReservedMinutes) {
            this(label, from, to, fixed, monthlyFee, hourlyRate, days, monthlyReservedMinutes, BillingMode.MENSUALISE);
        }

        boolean actual() {
            return fixed && billingMode == BillingMode.REEL && formula == null;
        }

        boolean covers(LocalDate day) {
            return !day.isBefore(from) && !day.isAfter(to);
        }

        DayPlan plan(LocalDate day) {
            return days.stream().filter(d -> d.dayOfWeek() == day.getDayOfWeek().getValue()).findFirst().orElse(null);
        }
    }

    public record Session(LocalDate date, LocalTime startsAt, LocalTime endsAt) {

        int minutes() {
            return (int) Duration.between(startsAt, endsAt).toMinutes();
        }
    }

    /**
     * Contrat de l'enfant pour le mois. {@code firstInvoice} : frais d'inscription dus ;
     * {@code adaptationStartsThisMonth} : la première heure offerte s'applique ce mois-ci.
     */
    public record ContractInput(String number, List<VersionPeriod> versions, boolean firstInvoice,
                                long registrationFee, List<Session> adaptation, AdaptationBilling adaptationBilling,
                                long adaptationRate, boolean adaptationStartsThisMonth) {
    }

    /** Passage pointé ; {@code departedAt} nul = départ non pointé. */
    public record Passage(LocalDate day, LocalTime arrivedAt, LocalTime departedAt) {
    }

    public record Absence(LocalDate from, LocalDate to, String reason, boolean deductible, boolean proofRequired,
                          boolean hasProof, boolean waitingDaysApply, boolean noticeApplies, LocalDate notifiedOn) {
    }

    /** Aide accordée à l'enfant, déjà ramenée à sa période de validité (accord et financeur). */
    public record Funding(UUID funderId, String funderName, int priority, Calculation calculation, long amount,
                          int rate, RateBase base, Long monthlyCap, LocalDate from, LocalDate to) {
    }

    public record ChildInput(UUID childId, String name, List<ContractInput> contracts, List<Passage> passages,
                             List<Absence> absences, List<Funding> fundings) {
    }

    /**
     * @param openDays     jours d'ouverture de la semaine (ISO, 1 = lundi)
     * @param closingTimes heure de fermeture par jour de la semaine
     * @param tgcRates     taux de TGC en dixièmes de pour cent, par prestation
     */
    public record MonthInput(YearMonth month, Set<Integer> openDays, Map<Integer, LocalTime> closingTimes,
                             Rules rules, Map<Service, Integer> tgcRates, List<ChildInput> children) {
    }

    // --- Résultat -------------------------------------------------------------------------------

    public enum Kind { FORFAIT, ADAPTATION, FRAIS_INSCRIPTION, HEURES_COMPLEMENTAIRES, DEPASSEMENT, ABSENCE, AIDE }

    public record Line(UUID childId, Kind kind, String label, String detail, String quantity, Long unitPrice,
                       long amount, int tgcRate, long tgcAmount, UUID funderId, String funderName) {

        public long total() {
            return amount + tgcAmount;
        }
    }

    public enum AnomalyType { DEPART_MANQUANT, SANS_CONTRAT, JUSTIFICATIF_MANQUANT }

    public record Anomaly(UUID childId, String childName, AnomalyType type, String message, List<LocalDate> days) {
    }

    /** Totaux par enfant, pour la prévisualisation. */
    public record ChildTotals(UUID childId, long forfait, long extraHours, long overtime, long absences,
                              long other, Map<UUID, Long> aids) {
    }

    /**
     * @param careTotal    frais de garde hors TGC (forfaits, suppléments, déductions)
     * @param aidTotal     total des aides (positif)
     * @param familyTotal  reste à charge, arrondi selon la règle
     * @param rounding     écart d'arrondi du total (positif ou négatif)
     */
    public record Result(List<Line> lines, long careTotal, long tgcTotal, long aidTotal, long rounding,
                         long familyTotal, Map<UUID, Long> aidByFunder, List<ChildTotals> children,
                         List<Anomaly> anomalies, List<String> notes) {

        public boolean blocked() {
            return !anomalies.isEmpty();
        }
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM");

    // --- Calcul ---------------------------------------------------------------------------------

    public static Result compute(MonthInput input) {
        var lines = new ArrayList<Line>();
        var anomalies = new ArrayList<Anomaly>();
        var notes = new ArrayList<String>();
        var totals = new ArrayList<ChildTotals>();
        for (var child : input.children()) {
            totals.add(child(input, child, lines, anomalies, notes));
        }
        long care = lines.stream().filter(l -> l.kind() != Kind.AIDE).mapToLong(Line::amount).sum();
        long tgc = lines.stream().filter(l -> l.kind() != Kind.AIDE).mapToLong(Line::tgcAmount).sum();
        long aids = -lines.stream().filter(l -> l.kind() == Kind.AIDE).mapToLong(Line::amount).sum();
        long raw = Math.max(care + tgc - aids, 0);
        long rounded = Math.max(Xpf.roundTotal(raw, input.rules().totalRoundingStep(), input.rules().totalRoundingMode()), 0);
        var byFunder = new LinkedHashMap<UUID, Long>();
        lines.stream().filter(l -> l.kind() == Kind.AIDE)
                .forEach(l -> byFunder.merge(l.funderId(), -l.amount(), Long::sum));
        return new Result(List.copyOf(lines), care, tgc, aids, rounded - raw, rounded, byFunder, totals,
                List.copyOf(anomalies), List.copyOf(notes));
    }

    private static ChildTotals child(MonthInput input, ChildInput child, List<Line> out, List<Anomaly> anomalies,
                                     List<String> notes) {
        var rules = input.rules();
        var month = input.month();
        var childLines = new ArrayList<Line>();
        var versions = child.contracts().stream().flatMap(c -> c.versions().stream())
                .sorted(Comparator.comparing(VersionPeriod::from)).toList();
        int openDaysInMonth = openDays(input, month.atDay(1), month.atEndOfMonth());

        // Forfait : mensualisé au prorata des jours d'ouverture couverts par chaque version, ou au réel des jours du
        // planning dans le mois.
        long forfait = 0;
        for (var version : versions) {
            if (version.actual()) {
                long amount = actualFee(input, version, childLines, child);
                forfait += amount;
                continue;
            }
            int covered = openDays(input, version.from(), version.to());
            long amount = Xpf.prorata(version.monthlyFee(), covered, openDaysInMonth);
            boolean full = covered >= openDaysInMonth;
            var label = full ? "Accueil %s (forfait mensuel)".formatted(ofMonth(month))
                    : "Accueil du %s au %s (forfait au prorata)".formatted(DAY.format(version.from()), DAY.format(version.to()));
            var smoothing = version.formula() != null ? " · formule « %s »".formatted(version.formula())
                    : version.fixed() && version.hourlyRate() > 0 && version.monthlyReservedMinutes() > 0
                    ? " · mensualisé : %s par mois à %s de l’heure, le même chaque mois".formatted(
                            hours(version.monthlyReservedMinutes()), xpf(version.hourlyRate()))
                    : "";
            var detail = full ? version.label() + smoothing
                    : "%s · %d jour%s d’ouverture sur %d".formatted(version.label(), covered, covered > 1 ? "s" : "", openDaysInMonth);
            childLines.add(line(input, child, Kind.FORFAIT, Service.FORFAIT, label, detail, null, null, amount));
            forfait += amount;
        }

        // Frais d'inscription et adaptation.
        for (var contract : child.contracts()) {
            if (contract.firstInvoice() && contract.registrationFee() > 0) {
                childLines.add(line(input, child, Kind.FRAIS_INSCRIPTION, Service.FRAIS_INSCRIPTION,
                        "Frais d’inscription", "Contrat n° " + contract.number(), null, null, contract.registrationFee()));
            }
            var sessions = contract.adaptation().stream().filter(s -> YearMonth.from(s.date()).equals(month)).toList();
            if (!sessions.isEmpty() && contract.adaptationBilling() != AdaptationBilling.OFFERTE) {
                int minutes = sessions.stream().mapToInt(Session::minutes).sum();
                int billed = contract.adaptationBilling() == AdaptationBilling.PREMIERE_HEURE_OFFERTE
                        && contract.adaptationStartsThisMonth() ? Math.max(minutes - 60, 0) : minutes;
                long amount = Xpf.ofMinutes(billed, contract.adaptationRate());
                if (amount > 0) {
                    childLines.add(line(input, child, Kind.ADAPTATION, Service.ADAPTATION,
                            "Adaptation (%d séance%s)".formatted(sessions.size(), sessions.size() > 1 ? "s" : ""),
                            (billed < minutes ? "Première heure offerte · " : "") + duration(billed) + " de présence",
                            duration(billed), contract.adaptationRate(), amount));
                }
            }
        }

        // Pointages : départs manquants, jours sans contrat, heures complémentaires, dépassements.
        var missingDeparture = new ArrayList<LocalDate>();
        var withoutContract = new ArrayList<LocalDate>();
        var extraByRate = new LinkedHashMap<Long, int[]>();
        var extraDays = new LinkedHashMap<Long, List<LocalDate>>();
        var overtime = new ArrayList<String>();
        var attendedDays = new HashSet<LocalDate>();
        int variableAttended = 0;
        var passagesByDay = child.passages().stream().filter(p -> YearMonth.from(p.day()).equals(month))
                .collect(Collectors.groupingBy(Passage::day, LinkedHashMap::new, Collectors.toList()));
        for (var entry : passagesByDay.entrySet()) {
            var day = entry.getKey();
            attendedDays.add(day);
            var version = versions.stream().filter(v -> v.covers(day)).findFirst().orElse(null);
            if (version == null) {
                withoutContract.add(day);
                continue;
            }
            if (entry.getValue().stream().anyMatch(p -> p.departedAt() == null)) {
                missingDeparture.add(day);
                continue;
            }
            var closing = input.closingTimes().get(day.getDayOfWeek().getValue());
            int extra = 0;
            for (var passage : entry.getValue()) {
                var leaves = passage.departedAt();
                if (closing != null && leaves.isAfter(closing.plusMinutes(rules.overtimeToleranceMinutes()))) {
                    overtime.add("le %s à %s".formatted(DAY.format(day), leaves));
                }
                // Le temps après la fermeture relève du dépassement, pas des heures complémentaires.
                var effectiveLeave = closing != null && leaves.isAfter(closing) ? closing : leaves;
                if (!version.fixed()) {
                    variableAttended += minutesBetween(passage.arrivedAt(), effectiveLeave);
                    continue;
                }
                var plan = version.plan(day);
                if (plan == null) {
                    extra += minutesBetween(passage.arrivedAt(), effectiveLeave);
                } else {
                    extra += beyond(minutesBetween(passage.arrivedAt(), plan.arrives()), rules.extraToleranceMinutes());
                    extra += beyond(minutesBetween(plan.leaves(), effectiveLeave), rules.extraToleranceMinutes());
                }
            }
            int billed = Xpf.roundMinutes(extra, rules.extraRoundingMinutes());
            if (billed > 0) {
                long rate = rules.extraRateMode() == ExtraRateMode.FIXE ? rules.fixedExtraRate() : version.hourlyRate();
                extraByRate.computeIfAbsent(rate, r -> new int[1])[0] += billed;
                extraDays.computeIfAbsent(rate, r -> new ArrayList<>()).add(day);
            }
        }
        // Planning variable : heures pointées au-delà des heures réservées du mois.
        for (var version : versions.stream().filter(v -> !v.fixed()).toList()) {
            int covered = openDays(input, version.from(), version.to());
            int reserved = (int) Xpf.prorata(version.monthlyReservedMinutes(), covered, openDaysInMonth);
            int over = Xpf.roundMinutes(Math.max(variableAttended - reserved, 0), rules.extraRoundingMinutes());
            if (over > 0) {
                long rate = rules.extraRateMode() == ExtraRateMode.FIXE ? rules.fixedExtraRate() : version.hourlyRate();
                extraByRate.computeIfAbsent(rate, r -> new int[1])[0] += over;
                notes.add("%s : %s pointées pour %s réservées.".formatted(child.name(), duration(variableAttended), duration(reserved)));
            }
        }
        long extraTotal = 0;
        for (var entry : extraByRate.entrySet()) {
            int minutes = entry.getValue()[0];
            long amount = Xpf.ofMinutes(minutes, entry.getKey());
            var days = extraDays.getOrDefault(entry.getKey(), List.of());
            childLines.add(line(input, child, Kind.HEURES_COMPLEMENTAIRES, Service.HEURES_COMPLEMENTAIRES,
                    "Heures complémentaires", days.isEmpty() ? "Au-delà des heures réservées"
                            : "Hors du planning du contrat, " + datesLabel(days),
                    duration(minutes), entry.getKey(), amount));
            extraTotal += amount;
        }
        long overtimeTotal = 0;
        if (!overtime.isEmpty() && rules.overtimeFee() > 0) {
            overtimeTotal = overtime.size() * rules.overtimeFee();
            childLines.add(line(input, child, Kind.DEPASSEMENT, Service.DEPASSEMENT, "Départs après la fermeture",
                    joinFr(overtime) + " · %d × %s".formatted(overtime.size(), xpf(rules.overtimeFee())),
                    String.valueOf(overtime.size()), rules.overtimeFee(), overtimeTotal));
        }

        // Absences déductibles.
        long absenceTotal = 0;
        var missingProof = new ArrayList<LocalDate>();
        for (var absence : child.absences()) {
            if (!absence.deductible()) {
                continue;
            }
            if (absence.noticeApplies()) {
                long notice = java.time.temporal.ChronoUnit.DAYS.between(absence.notifiedOn(), absence.from());
                if (notice < rules.leaveNoticeDays()) {
                    notes.add("%s : %s prévenus %d jour%s avant, préavis de %d jours : non déduits.".formatted(child.name(),
                            absence.reason().toLowerCase(Locale.FRANCE), notice, notice > 1 ? "s" : "", rules.leaveNoticeDays()));
                    continue;
                }
            }
            var waived = absence.waitingDaysApply() ? waitingDays(input, absence, rules.sickWaitingDays()) : Set.<LocalDate>of();
            var deducted = new ArrayList<LocalDate>();
            long amount = 0;
            for (var day = max(absence.from(), month.atDay(1)); !day.isAfter(min(absence.to(), month.atEndOfMonth())); day = day.plusDays(1)) {
                var current = day;
                var version = versions.stream().filter(v -> v.covers(current) && v.fixed()).findFirst().orElse(null);
                var plan = version == null ? null : version.plan(current);
                if (plan == null || attendedDays.contains(current) || waived.contains(current)
                        || !input.openDays().contains(current.getDayOfWeek().getValue())) {
                    continue;
                }
                if (absence.proofRequired() && !absence.hasProof()) {
                    missingProof.add(current);
                    continue;
                }
                deducted.add(current);
                amount += Xpf.ofMinutes(plan.minutes(), version.hourlyRate());
            }
            if (!waived.isEmpty() && waived.stream().anyMatch(d -> YearMonth.from(d).equals(month))) {
                notes.add("%s : %d jour%s de carence non déduit%s.".formatted(child.name(), waived.size(),
                        waived.size() > 1 ? "s" : "", waived.size() > 1 ? "s" : ""));
            }
            if (deducted.isEmpty()) {
                continue;
            }
            amount = Math.min(amount, Math.max(forfait + absenceTotal, 0));
            if (amount <= 0) {
                continue;
            }
            absenceTotal -= amount;
            childLines.add(line(input, child, Kind.ABSENCE, Service.FORFAIT, "Absences remboursées",
                    "%s, %s · %d jour%s".formatted(absence.reason(), datesLabel(deducted), deducted.size(),
                            deducted.size() > 1 ? "s" : ""),
                    String.valueOf(deducted.size()), null, -amount));
        }

        if (!missingDeparture.isEmpty()) {
            anomalies.add(new Anomaly(child.childId(), child.name(), AnomalyType.DEPART_MANQUANT,
                    "%s — pas d’heure de départ %s. Les heures complémentaires ne peuvent pas être calculées."
                            .formatted(child.name(), datesLabel(missingDeparture)), missingDeparture));
        }
        if (!withoutContract.isEmpty()) {
            anomalies.add(new Anomaly(child.childId(), child.name(), AnomalyType.SANS_CONTRAT,
                    "%s — pointé %s sans contrat signé en vigueur ce jour-là.".formatted(child.name(), datesLabel(withoutContract)),
                    withoutContract));
        }
        if (!missingProof.isEmpty()) {
            anomalies.add(new Anomaly(child.childId(), child.name(), AnomalyType.JUSTIFICATIF_MANQUANT,
                    "%s — absence %s sans le justificatif exigé : joignez-le pour la déduire.".formatted(child.name(),
                            datesLabel(missingProof)), missingProof));
        }

        // Aides des financeurs, par priorité.
        long care = childLines.stream().mapToLong(Line::total).sum();
        long forfaitBase = childLines.stream().filter(l -> l.kind() == Kind.FORFAIT || l.kind() == Kind.ABSENCE)
                .mapToLong(Line::amount).sum();
        long remaining = care;
        var aids = new LinkedHashMap<UUID, Long>();
        int daysInMonth = month.lengthOfMonth();
        for (var funding : child.fundings().stream()
                .sorted(Comparator.comparingInt(Funding::priority).thenComparing(Funding::funderName)).toList()) {
            var from = max(funding.from(), month.atDay(1));
            var to = min(funding.to() == null ? month.atEndOfMonth() : funding.to(), month.atEndOfMonth());
            if (to.isBefore(from) || remaining <= 0) {
                continue;
            }
            long covered = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
            long amount;
            String detail;
            if (funding.calculation() == Calculation.MONTANT_FIXE) {
                amount = Xpf.prorata(funding.amount(), covered, daysInMonth);
                detail = covered < daysInMonth ? "%s par mois, au prorata (%d jours sur %d)".formatted(xpf(funding.amount()), covered, daysInMonth)
                        : "Montant mensuel";
            } else {
                long base = funding.base() == RateBase.FORFAIT ? forfaitBase : care;
                amount = Xpf.prorata(Xpf.percent(base, funding.rate()), covered, daysInMonth);
                detail = "%s %% %s".formatted(rate(funding.rate()),
                        funding.base() == RateBase.FORFAIT ? "du forfait" : "des frais de garde");
            }
            long capped = Xpf.cap(amount, funding.monthlyCap());
            if (capped < amount) {
                detail += " · plafond de " + xpf(funding.monthlyCap());
            }
            long applied = Math.min(capped, remaining);
            if (applied <= 0) {
                continue;
            }
            remaining -= applied;
            aids.merge(funding.funderId(), applied, Long::sum);
            childLines.add(new Line(child.childId(), Kind.AIDE, funding.funderName(), detail, null, null, -applied, 0, 0,
                    funding.funderId(), funding.funderName()));
        }
        out.addAll(childLines);
        long other = childLines.stream().filter(l -> l.kind() == Kind.ADAPTATION || l.kind() == Kind.FRAIS_INSCRIPTION)
                .mapToLong(Line::amount).sum();
        return new ChildTotals(child.childId(), forfait, extraTotal, overtimeTotal, -absenceTotal, other, aids);
    }

    // --- Outils ---------------------------------------------------------------------------------

    private static Line line(MonthInput input, ChildInput child, Kind kind, Service service, String label,
                             String detail, String quantity, Long unitPrice, long amount) {
        int rate = input.tgcRates().getOrDefault(service, 0);
        return new Line(child.childId(), kind, label, detail, quantity, unitPrice, amount, rate, Xpf.tgc(amount, rate),
                null, null);
    }

    /** Jours d'ouverture entre deux dates incluses. */
    /**
     * Facturation au réel : chaque jour d'ouverture du mois qui figure au planning du contrat, à l'heure (4 mardis de
     * 9 h à 650 F → 36 h, 23 400 F ; 5 mardis → 45 h, 29 250 F).
     */
    private static long actualFee(MonthInput input, VersionPeriod version, List<Line> out, ChildInput child) {
        var month = input.month();
        var from = max(version.from(), month.atDay(1));
        var to = min(version.to(), month.atEndOfMonth());
        int days = 0;
        int minutes = 0;
        for (var day = from; !day.isAfter(to); day = day.plusDays(1)) {
            var plan = version.plan(day);
            if (plan != null && input.openDays().contains(day.getDayOfWeek().getValue())) {
                days++;
                minutes += plan.minutes();
            }
        }
        long amount = Xpf.ofMinutes(minutes, version.hourlyRate());
        var label = "Accueil %s (au réel)".formatted(ofMonth(month));
        var detail = "%s · %d jour%s du planning dans le mois, %s à %s de l’heure".formatted(version.label(), days,
                days > 1 ? "s" : "", hours(minutes), xpf(version.hourlyRate()));
        out.add(line(input, child, Kind.FORFAIT, Service.FORFAIT, label, detail, duration(minutes), version.hourlyRate(),
                amount));
        return amount;
    }

    static int openDays(MonthInput input, LocalDate from, LocalDate to) {
        int count = 0;
        for (var day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (input.openDays().contains(day.getDayOfWeek().getValue())) {
                count++;
            }
        }
        return count;
    }

    /** Premiers jours d'ouverture d'une absence soumis à la carence (même s'ils tombent le mois précédent). */
    private static Set<LocalDate> waitingDays(MonthInput input, Absence absence, int days) {
        var waived = new HashSet<LocalDate>();
        for (var day = absence.from(); !day.isAfter(absence.to()) && waived.size() < days; day = day.plusDays(1)) {
            if (input.openDays().contains(day.getDayOfWeek().getValue())) {
                waived.add(day);
            }
        }
        return waived;
    }

    private static int beyond(int minutes, int tolerance) {
        return minutes > tolerance ? minutes : 0;
    }

    private static int minutesBetween(LocalTime from, LocalTime to) {
        return Math.max((int) Duration.between(from, to).toMinutes(), 0);
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    static String monthName(YearMonth month) {
        return month.getMonth().getDisplayName(TextStyle.FULL, Locale.FRANCE);
    }

    /** « d’octobre », « de novembre » */
    static String ofMonth(YearMonth month) {
        var name = monthName(month);
        return ("aeiou".indexOf(name.charAt(0)) >= 0 ? "d’" : "de ") + name;
    }

    /** « le 20/10 et le 21/10 », « les 20/10, 21/10 et 22/10 » */
    static String datesLabel(List<LocalDate> days) {
        var sorted = days.stream().sorted().map(DAY::format).toList();
        if (sorted.size() == 1) {
            return "le " + sorted.getFirst();
        }
        return "les " + joinFr(sorted);
    }

    static String joinFr(List<String> parts) {
        if (parts.size() <= 1) {
            return String.join("", parts);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " et " + parts.getLast();
    }

    /** 75 → « 1 h 15 » ; 15 → « 15 min » */
    /** « 39 h », « 37 h 30 » : volume d'heures dans un texte (les quantités gardent « 4 h 00 »). */
    static String hours(int minutes) {
        return minutes % 60 == 0 ? (minutes / 60) + " h" : duration(minutes);
    }

    static String duration(int minutes) {
        if (minutes < 60) {
            return minutes + " min";
        }
        return "%d h %02d".formatted(minutes / 60, minutes % 60);
    }

    /** 200 → « 20 », 125 → « 12,5 » */
    static String rate(int tenths) {
        return tenths % 10 == 0 ? String.valueOf(tenths / 10) : "%d,%d".formatted(tenths / 10, tenths % 10);
    }

    static String xpf(long amount) {
        return nc.ticagou.common.Formats.xpf(amount);
    }
}
