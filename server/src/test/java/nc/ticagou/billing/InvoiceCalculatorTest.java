package nc.ticagou.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import nc.ticagou.billing.InvoiceCalculator.Absence;
import nc.ticagou.billing.InvoiceCalculator.AdaptationBilling;
import nc.ticagou.billing.InvoiceCalculator.AnomalyType;
import nc.ticagou.billing.InvoiceCalculator.Calculation;
import nc.ticagou.billing.InvoiceCalculator.ChildInput;
import nc.ticagou.billing.InvoiceCalculator.ContractInput;
import nc.ticagou.billing.InvoiceCalculator.DayPlan;
import nc.ticagou.billing.InvoiceCalculator.ExtraRateMode;
import nc.ticagou.billing.InvoiceCalculator.Funding;
import nc.ticagou.billing.InvoiceCalculator.Kind;
import nc.ticagou.billing.InvoiceCalculator.Line;
import nc.ticagou.billing.InvoiceCalculator.MonthInput;
import nc.ticagou.billing.InvoiceCalculator.Passage;
import nc.ticagou.billing.InvoiceCalculator.RateBase;
import nc.ticagou.billing.InvoiceCalculator.Result;
import nc.ticagou.billing.InvoiceCalculator.Rules;
import nc.ticagou.billing.InvoiceCalculator.Service;
import nc.ticagou.billing.InvoiceCalculator.Session;
import nc.ticagou.billing.InvoiceCalculator.VersionPeriod;
import nc.ticagou.billing.Xpf.Mode;
import nc.ticagou.contract.BillingMode;

/** Octobre 2026 : 22 jours d'ouverture (du lundi au vendredi), fermeture à 17:30. */
class InvoiceCalculatorTest {

    private static final YearMonth OCTOBRE = YearMonth.of(2026, 10);
    private static final Set<Integer> WEEKDAYS = Set.of(1, 2, 3, 4, 5);
    private static final Map<Integer, LocalTime> CLOSING = Map.of(1, t("17:30"), 2, t("17:30"), 3, t("17:30"),
            4, t("17:30"), 5, t("17:30"));
    private static final Rules RULES = new Rules(ExtraRateMode.CONTRAT, 1_500, 10, 15, 1_000, 10, 1, 15, 1, Mode.PROCHE);
    private static final UUID CHILD = UUID.randomUUID();
    private static final UUID PROVINCE = UUID.randomUUID();
    private static final UUID CAFAT = UUID.randomUUID();

    private static LocalTime t(String hhmm) {
        return LocalTime.parse(hhmm);
    }

    private static LocalDate d(int day) {
        return OCTOBRE.atDay(day);
    }

    private static List<DayPlan> days(int... isoDays) {
        var list = new ArrayList<DayPlan>();
        for (int day : isoDays) {
            list.add(new DayPlan(day, t("07:00"), t("17:00")));
        }
        return list;
    }

    private static VersionPeriod version(int from, int to, long fee, long rate) {
        return new VersionPeriod("5 jours par semaine, 07:00 à 17:00", d(from), d(to), true, fee, rate,
                days(1, 2, 3, 4, 5), 0);
    }

    private static ContractInput contract(VersionPeriod... versions) {
        return new ContractInput("2026-001", List.of(versions), false, 0, List.of(), AdaptationBilling.AU_TEMPS, 0, false);
    }

    private static ChildInput child(ContractInput contract, List<Passage> passages, List<Absence> absences,
                                    List<Funding> fundings) {
        return new ChildInput(CHILD, "Maëlys Dupré", List.of(contract), passages, absences, fundings);
    }

    private static Result compute(Rules rules, Map<Service, Integer> tgc, ChildInput... children) {
        return InvoiceCalculator.compute(new MonthInput(OCTOBRE, WEEKDAYS, CLOSING, rules, tgc, List.of(children)));
    }

    private static Result compute(ChildInput... children) {
        return compute(RULES, Map.of(), children);
    }

    private static List<Line> of(Result result, Kind kind) {
        return result.lines().stream().filter(l -> l.kind() == kind).toList();
    }

    private static final ContractInput FULL = contract(version(1, 31, 68_000, 500));

    private static Absence absence(int from, int to, String reason, boolean proofRequired, boolean hasProof,
                                   boolean waiting, boolean notice, LocalDate notified) {
        return new Absence(d(from), d(to), reason, true, proofRequired, hasProof, waiting, notice, notified);
    }

    private static Funding funding(UUID id, String name, int priority, Calculation calc, long amount, int rate,
                                   RateBase base, Long cap, LocalDate from) {
        return new Funding(id, name, priority, calc, amount, rate, base, cap, from, null);
    }

    // --- Forfait ---------------------------------------------------------------------------------

    /** Un seul jour par semaine, 9 h, à 650 F de l'heure. */
    private static VersionPeriod oneDay(int isoDay, int from, BillingMode mode) {
        return new VersionPeriod("1 jour par semaine, 08:00 à 17:00", d(from), d(31), true, 25_350, 650,
                List.of(new DayPlan(isoDay, t("08:00"), t("17:00"))), 39 * 60, mode);
    }

    private static long forfait(VersionPeriod version) {
        return of(compute(child(contract(version), List.of(), List.of(), List.of())), Kind.FORFAIT).stream()
                .mapToLong(Line::amount).sum();
    }

    @Test
    void mensualiseLeMemeMontantChaqueMoisEtExpliqueLeCalcul() {
        // 9 h × 52 semaines ÷ 12 = 39 h par mois, à 650 F : 25 350 F, que le mois compte 4 ou 5 mardis.
        var line = of(compute(child(contract(oneDay(2, 1, BillingMode.MENSUALISE)), List.of(), List.of(), List.of())),
                Kind.FORFAIT).getFirst();
        assertThat(line.amount()).isEqualTo(25_350);
        assertThat(line.detail()).contains("mensualisé : 39 h par mois à").contains("le même chaque mois");
        assertThat(forfait(oneDay(4, 1, BillingMode.MENSUALISE))).isEqualTo(25_350);
    }

    @Test
    void auReelLesJoursDuPlanningDuMois() {
        // Octobre 2026 : 4 mardis (6, 13, 20, 27) et 5 jeudis.
        var line = of(compute(child(contract(oneDay(2, 1, BillingMode.REEL)), List.of(), List.of(), List.of())),
                Kind.FORFAIT).getFirst();
        assertThat(line.amount()).as("4 × 9 h × 650 F").isEqualTo(23_400);
        assertThat(line.label()).isEqualTo("Accueil d’octobre (au réel)");
        assertThat(line.detail()).contains("4 jours du planning dans le mois, 36 h");
        assertThat(forfait(oneDay(4, 1, BillingMode.REEL))).as("5 jeudis").isEqualTo(29_250);
        assertThat(forfait(oneDay(2, 14, BillingMode.REEL))).as("contrat commencé le 14 : 20 et 27").isEqualTo(11_700);
    }

    @Test
    void moisPleinAuForfait() {
        var result = compute(child(FULL, List.of(), List.of(), List.of()));
        var forfait = of(result, Kind.FORFAIT);
        assertThat(forfait).hasSize(1);
        assertThat(forfait.getFirst().label()).isEqualTo("Accueil d’octobre (forfait mensuel)");
        assertThat(forfait.getFirst().amount()).isEqualTo(68_000);
        assertThat(result.careTotal()).isEqualTo(68_000);
        assertThat(result.familyTotal()).isEqualTo(68_000);
        assertThat(result.blocked()).isFalse();
    }

    @Test
    void avenantEnCoursDeMoisAuProrataEtAuTarifDeChaqueVersion() {
        var amended = contract(version(1, 14, 68_000, 500), version(15, 31, 80_000, 600));
        var passages = List.of(
                new Passage(d(13), t("07:00"), t("17:20")),   // version 1 : 20 min → 15 min à 500 F
                new Passage(d(20), t("06:40"), t("17:00")));  // version 2 : 20 min → 15 min à 600 F
        var result = compute(child(amended, passages, List.of(), List.of()));

        var forfait = of(result, Kind.FORFAIT);
        assertThat(forfait).extracting(Line::amount).containsExactly(30_909L, 43_636L);
        assertThat(forfait.getFirst().detail()).contains("10 jours d’ouverture sur 22");
        assertThat(forfait.get(1).detail()).contains("12 jours d’ouverture sur 22");
        var extra = of(result, Kind.HEURES_COMPLEMENTAIRES);
        assertThat(extra).extracting(Line::unitPrice).containsExactly(500L, 600L);
        assertThat(extra).extracting(Line::amount).containsExactly(125L, 150L);
        assertThat(result.careTotal()).isEqualTo(30_909 + 43_636 + 125 + 150);
    }

    @Test
    void contratQuiCommenceOuFinitEnCoursDeMois() {
        var starts = compute(child(contract(version(15, 31, 68_000, 500)), List.of(), List.of(), List.of()));
        assertThat(of(starts, Kind.FORFAIT).getFirst().amount()).isEqualTo(37_091);
        assertThat(of(starts, Kind.FORFAIT).getFirst().label()).isEqualTo("Accueil du 15/10 au 31/10 (forfait au prorata)");
        var ends = compute(child(contract(version(1, 9, 68_000, 500)), List.of(), List.of(), List.of()));
        assertThat(of(ends, Kind.FORFAIT).getFirst().amount()).isEqualTo(21_636);
    }

    @Test
    void fraisDInscriptionSurLaPremiereFacture() {
        var first = new ContractInput("2026-001", List.of(version(1, 31, 68_000, 500)), true, 10_000, List.of(),
                AdaptationBilling.AU_TEMPS, 0, false);
        var result = compute(child(first, List.of(), List.of(), List.of()));
        assertThat(of(result, Kind.FRAIS_INSCRIPTION)).extracting(Line::amount).containsExactly(10_000L);
        assertThat(result.familyTotal()).isEqualTo(78_000);
    }

    // --- Adaptation --------------------------------------------------------------------------------

    private static ContractInput adaptation(AdaptationBilling billing, boolean startsThisMonth) {
        var sessions = List.of(new Session(d(5), t("09:00"), t("10:00")), new Session(d(6), t("09:00"), t("11:00")),
                new Session(LocalDate.of(2026, 9, 30), t("09:00"), t("10:00")));
        return new ContractInput("2026-001", List.of(version(12, 31, 68_000, 500)), false, 0, sessions, billing, 500,
                startsThisMonth);
    }

    @Test
    void adaptationEnCoursDeMoisSelonLeMode() {
        var auTemps = compute(child(adaptation(AdaptationBilling.AU_TEMPS, false), List.of(), List.of(), List.of()));
        assertThat(of(auTemps, Kind.ADAPTATION)).extracting(Line::amount).containsExactly(1_500L);
        assertThat(of(auTemps, Kind.ADAPTATION).getFirst().label()).isEqualTo("Adaptation (2 séances)");

        var firstHour = compute(child(adaptation(AdaptationBilling.PREMIERE_HEURE_OFFERTE, true), List.of(), List.of(), List.of()));
        assertThat(of(firstHour, Kind.ADAPTATION)).extracting(Line::amount).containsExactly(1_000L);
        assertThat(of(firstHour, Kind.ADAPTATION).getFirst().detail()).startsWith("Première heure offerte");

        // La première heure a été offerte le mois précédent.
        var later = compute(child(adaptation(AdaptationBilling.PREMIERE_HEURE_OFFERTE, false), List.of(), List.of(), List.of()));
        assertThat(of(later, Kind.ADAPTATION)).extracting(Line::amount).containsExactly(1_500L);

        var offered = compute(child(adaptation(AdaptationBilling.OFFERTE, true), List.of(), List.of(), List.of()));
        assertThat(of(offered, Kind.ADAPTATION)).isEmpty();
    }

    // --- Heures complémentaires et dépassements --------------------------------------------------------

    @Test
    void heuresComplementairesALaTolerancePileEtAuDela() {
        var atTolerance = compute(child(FULL, List.of(new Passage(d(5), t("07:00"), t("17:10"))), List.of(), List.of()));
        assertThat(of(atTolerance, Kind.HEURES_COMPLEMENTAIRES)).isEmpty();
        var under = compute(child(FULL, List.of(new Passage(d(5), t("07:00"), t("17:02"))), List.of(), List.of()));
        assertThat(of(under, Kind.HEURES_COMPLEMENTAIRES)).isEmpty();

        var beyond = compute(child(FULL, List.of(new Passage(d(5), t("07:00"), t("17:11"))), List.of(), List.of()));
        var line = of(beyond, Kind.HEURES_COMPLEMENTAIRES).getFirst();
        assertThat(line.quantity()).isEqualTo("15 min");
        assertThat(line.amount()).isEqualTo(125);
        assertThat(line.detail()).isEqualTo("Hors du planning du contrat, le 05/10");
    }

    @Test
    void arrondiAuQuartDHeureSurLeTotalDuJour() {
        // Exemple de la maquette : départ prévu 16:45, réel 17:02 → 17 min, facturées 15 min.
        var plan = new VersionPeriod("Planning", d(1), d(31), true, 68_000, 600,
                List.of(new DayPlan(1, t("08:00"), t("16:45"))), 0);
        var result = compute(child(contract(plan), List.of(new Passage(d(5), t("08:00"), t("17:02"))), List.of(), List.of()));
        assertThat(of(result, Kind.HEURES_COMPLEMENTAIRES).getFirst().quantity()).isEqualTo("15 min");
        // Arrivée 15 min plus tôt et départ 23 min plus tard : 38 min → 45 min.
        var both = compute(child(FULL, List.of(new Passage(d(6), t("06:45"), t("17:23"))), List.of(), List.of()));
        assertThat(of(both, Kind.HEURES_COMPLEMENTAIRES).getFirst().quantity()).isEqualTo("45 min");
        assertThat(of(both, Kind.HEURES_COMPLEMENTAIRES).getFirst().amount()).isEqualTo(375);
    }

    @Test
    void jourNonPrevuAuContratEtTarifFixe() {
        var fourDays = contract(new VersionPeriod("4 jours", d(1), d(31), true, 60_000, 500, days(1, 2, 3, 4), 0));
        var friday = List.of(new Passage(d(9), t("08:00"), t("12:00")));
        var result = compute(child(fourDays, friday, List.of(), List.of()));
        assertThat(of(result, Kind.HEURES_COMPLEMENTAIRES)).extracting(Line::quantity, Line::amount)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("4 h 00", 2_000L));

        var fixed = new Rules(ExtraRateMode.FIXE, 1_500, 10, 15, 1_000, 10, 1, 15, 1, Mode.PROCHE);
        var atFixedRate = compute(fixed, Map.of(), child(fourDays, friday, List.of(), List.of()));
        assertThat(of(atFixedRate, Kind.HEURES_COMPLEMENTAIRES).getFirst().amount()).isEqualTo(6_000);
    }

    @Test
    void depassementApresLaFermeture() {
        var passages = List.of(new Passage(d(14), t("07:00"), t("17:41")), new Passage(d(22), t("07:00"), t("17:40")));
        var result = compute(child(FULL, passages, List.of(), List.of()));
        var overtime = of(result, Kind.DEPASSEMENT);
        assertThat(overtime).hasSize(1);
        assertThat(overtime.getFirst().amount()).isEqualTo(1_000);
        assertThat(overtime.getFirst().detail()).startsWith("le 14/10 à 17:41");
        // Le temps jusqu'à la fermeture reste en heures complémentaires (30 min les deux jours).
        assertThat(of(result, Kind.HEURES_COMPLEMENTAIRES).getFirst().quantity()).isEqualTo("1 h 00");
    }

    @Test
    void planningVariableAuDelaDesHeuresReservees() {
        var variable = new VersionPeriod("Planning variable", d(1), d(31), false, 40_000, 500, List.of(), 80 * 60);
        var few = new ArrayList<Passage>();
        for (int day : new int[] {5, 6, 7}) {
            few.add(new Passage(d(day), t("08:00"), t("17:00")));
        }
        assertThat(of(compute(child(contract(variable), few, List.of(), List.of())), Kind.HEURES_COMPLEMENTAIRES)).isEmpty();

        var many = new ArrayList<Passage>();
        for (int day : new int[] {5, 6, 7, 8, 9, 12, 13, 14, 15, 16}) {
            many.add(new Passage(d(day), t("08:00"), t("17:00")));
        }
        var result = compute(child(contract(variable), many, List.of(), List.of()));
        assertThat(of(result, Kind.FORFAIT).getFirst().amount()).isEqualTo(40_000);
        assertThat(of(result, Kind.HEURES_COMPLEMENTAIRES)).extracting(Line::quantity, Line::amount)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("10 h 00", 5_000L));
    }

    // --- Absences ----------------------------------------------------------------------------------

    @Test
    void maladieAvecCarence() {
        var sick = absence(20, 21, "Maladie avec certificat médical", true, true, true, false, d(20));
        var result = compute(child(FULL, List.of(), List.of(sick), List.of()));
        var line = of(result, Kind.ABSENCE).getFirst();
        assertThat(line.amount()).isEqualTo(-5_000);
        assertThat(line.detail()).isEqualTo("Maladie avec certificat médical, le 21/10 · 1 jour");
        assertThat(result.notes()).anyMatch(n -> n.contains("1 jour de carence"));

        var hospital = absence(20, 21, "Hospitalisation de l’enfant", true, true, false, false, d(20));
        assertThat(of(compute(child(FULL, List.of(), List.of(hospital), List.of())), Kind.ABSENCE).getFirst().amount())
                .isEqualTo(-10_000);
    }

    @Test
    void carenceCommenceeLeMoisPrecedent() {
        var sick = new Absence(LocalDate.of(2026, 9, 30), d(2), "Maladie avec certificat médical", true, true, true,
                true, false, LocalDate.of(2026, 9, 30));
        var result = compute(child(FULL, List.of(), List.of(sick), List.of()));
        // Carence le 30/09 : le 01/10 et le 02/10 sont déduits.
        assertThat(of(result, Kind.ABSENCE).getFirst().amount()).isEqualTo(-10_000);
    }

    @Test
    void congesSelonLePreavis() {
        var late = absence(26, 30, "Congés de la famille", false, false, false, true, d(20));
        var lateResult = compute(child(FULL, List.of(), List.of(late), List.of()));
        assertThat(of(lateResult, Kind.ABSENCE)).isEmpty();
        assertThat(lateResult.notes()).anyMatch(n -> n.contains("prévenus 6 jours avant"));

        var early = absence(26, 30, "Congés de la famille", false, false, false, true, d(1));
        assertThat(of(compute(child(FULL, List.of(), List.of(early), List.of())), Kind.ABSENCE).getFirst().amount())
                .isEqualTo(-25_000);
    }

    @Test
    void justificatifManquantBloqueLaFacture() {
        var sick = absence(20, 21, "Maladie avec certificat médical", true, false, false, false, d(20));
        var result = compute(child(FULL, List.of(), List.of(sick), List.of()));
        assertThat(of(result, Kind.ABSENCE)).isEmpty();
        assertThat(result.anomalies()).extracting(InvoiceCalculator.Anomaly::type)
                .containsExactly(AnomalyType.JUSTIFICATIF_MANQUANT);
        assertThat(result.blocked()).isTrue();
    }

    @Test
    void absenceJamaisSuperieureAuForfaitNiUnJourPointe() {
        var shortContract = contract(version(26, 31, 68_000, 500));
        var leave = absence(1, 31, "Congés de la famille", false, false, false, true, LocalDate.of(2026, 9, 1));
        var result = compute(child(shortContract, List.of(), List.of(leave), List.of()));
        assertThat(of(result, Kind.FORFAIT).getFirst().amount()).isEqualTo(15_455);
        assertThat(of(result, Kind.ABSENCE).getFirst().amount()).isEqualTo(-15_455);
        assertThat(result.familyTotal()).isZero();

        // Venu malgré l'absence déclarée : le jour n'est pas déduit.
        var sick = absence(20, 21, "Hospitalisation", true, true, false, false, d(20));
        var present = compute(child(FULL, List.of(new Passage(d(21), t("07:00"), t("17:00"))), List.of(sick), List.of()));
        assertThat(of(present, Kind.ABSENCE).getFirst().amount()).isEqualTo(-5_000);
    }

    // --- Financeurs -----------------------------------------------------------------------------------

    @Test
    void plusieursFinanceursParPrioriteEtPlafondGlobal() {
        var province = funding(PROVINCE, "Province Sud", 0, Calculation.POURCENTAGE, 0, 200, RateBase.FRAIS_GARDE, null, d(1));
        var cafat = funding(CAFAT, "CAFAT", 1, Calculation.MONTANT_FIXE, 60_000, 0, RateBase.FRAIS_GARDE, null, d(1));
        var result = compute(child(FULL, List.of(), List.of(), List.of(cafat, province)));
        var aids = of(result, Kind.AIDE);
        assertThat(aids).extracting(Line::funderName).containsExactly("Province Sud", "CAFAT");
        assertThat(aids).extracting(Line::amount).containsExactly(-13_600L, -54_400L);
        assertThat(result.aidTotal()).isEqualTo(68_000);
        assertThat(result.familyTotal()).isZero();
        assertThat(result.aidByFunder()).containsEntry(PROVINCE, 13_600L).containsEntry(CAFAT, 54_400L);
    }

    @Test
    void montantFixeAuProrataEtPlafondDuFinanceur() {
        var fromMidMonth = funding(CAFAT, "CAFAT", 0, Calculation.MONTANT_FIXE, 12_000, 0, RateBase.FRAIS_GARDE, null, d(16));
        var prorated = of(compute(child(FULL, List.of(), List.of(), List.of(fromMidMonth))), Kind.AIDE).getFirst();
        assertThat(prorated.amount()).isEqualTo(-6_194);
        assertThat(prorated.detail()).contains("16 jours sur 31");

        var capped = funding(PROVINCE, "Province Sud", 0, Calculation.POURCENTAGE, 0, 500, RateBase.FRAIS_GARDE, 20_000L, d(1));
        var line = of(compute(child(FULL, List.of(), List.of(), List.of(capped))), Kind.AIDE).getFirst();
        assertThat(line.amount()).isEqualTo(-20_000);
        assertThat(line.detail()).contains("plafond");
    }

    @Test
    void pourcentageDuSeulForfait() {
        var onForfait = funding(PROVINCE, "Ville de Nouméa", 0, Calculation.POURCENTAGE, 0, 200, RateBase.FORFAIT, null, d(1));
        var passages = List.of(new Passage(d(5), t("07:00"), t("18:00")));
        var result = compute(child(FULL, passages, List.of(), List.of(onForfait)));
        assertThat(result.careTotal()).isGreaterThan(68_000);
        assertThat(of(result, Kind.AIDE).getFirst().amount()).isEqualTo(-13_600);
        assertThat(of(result, Kind.AIDE).getFirst().detail()).isEqualTo("20 % du forfait");
    }

    // --- TGC et arrondis ------------------------------------------------------------------------------

    @Test
    void tgcParPrestation() {
        var sick = absence(20, 21, "Hospitalisation", true, true, false, false, d(20));
        var exempt = compute(child(FULL, List.of(), List.of(sick), List.of()));
        assertThat(exempt.tgcTotal()).isZero();

        var taxed = compute(RULES, Map.of(Service.FORFAIT, 60), child(FULL, List.of(), List.of(sick), List.of()));
        assertThat(of(taxed, Kind.FORFAIT).getFirst().tgcAmount()).isEqualTo(4_080);
        assertThat(of(taxed, Kind.ABSENCE).getFirst().tgcAmount()).isEqualTo(-600);
        assertThat(taxed.tgcTotal()).isEqualTo(3_480);
        assertThat(taxed.familyTotal()).isEqualTo(58_000 + 3_480);
    }

    @Test
    void arrondiDuTotalSelonLaRegle() {
        var midMonth = child(contract(version(15, 31, 68_000, 500)), List.of(), List.of(), List.of());
        var near = compute(new Rules(ExtraRateMode.CONTRAT, 0, 10, 15, 1_000, 10, 1, 15, 10, Mode.PROCHE), Map.of(), midMonth);
        assertThat(near.familyTotal()).isEqualTo(37_090);
        assertThat(near.rounding()).isEqualTo(-1);
        var up = compute(new Rules(ExtraRateMode.CONTRAT, 0, 10, 15, 1_000, 10, 1, 15, 100, Mode.SUPERIEUR), Map.of(), midMonth);
        assertThat(up.familyTotal()).isEqualTo(37_100);
        assertThat(up.rounding()).isEqualTo(9);
    }

    // --- Famille et anomalies ----------------------------------------------------------------------------

    @Test
    void familleDeDeuxEnfants() {
        var second = UUID.randomUUID();
        var cafat = funding(CAFAT, "CAFAT", 0, Calculation.MONTANT_FIXE, 12_000, 0, RateBase.FRAIS_GARDE, null, d(1));
        var first = child(FULL, List.of(), List.of(), List.of(cafat));
        var other = new ChildInput(second, "Tom Dupré", List.of(contract(version(1, 31, 40_000, 400))), List.of(),
                List.of(), List.of(cafat));
        var result = compute(first, other);
        assertThat(result.careTotal()).isEqualTo(108_000);
        assertThat(result.aidTotal()).isEqualTo(24_000);
        assertThat(result.familyTotal()).isEqualTo(84_000);
        assertThat(result.children()).hasSize(2);
        assertThat(result.aidByFunder()).containsEntry(CAFAT, 24_000L);
        assertThat(result.lines().stream().filter(l -> l.childId().equals(second)).count()).isEqualTo(2);
    }

    @Test
    void anomaliesDepartManquantEtContratExpire() {
        var endsEarly = contract(version(1, 15, 68_000, 500));
        var passages = List.of(new Passage(d(14), t("07:00"), null), new Passage(d(20), t("07:00"), t("17:00")));
        var result = compute(child(endsEarly, passages, List.of(), List.of()));
        assertThat(result.anomalies()).extracting(InvoiceCalculator.Anomaly::type)
                .containsExactly(AnomalyType.DEPART_MANQUANT, AnomalyType.SANS_CONTRAT);
        assertThat(result.anomalies().getFirst().message()).contains("pas d’heure de départ le 14/10");
        assertThat(result.anomalies().get(1).days()).containsExactly(d(20));
        assertThat(result.blocked()).isTrue();
    }
}
