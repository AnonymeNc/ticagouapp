package nc.ticagou.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import nc.ticagou.contract.ForfaitCalculator.AdaptationBilling;
import nc.ticagou.contract.ForfaitCalculator.Band;
import nc.ticagou.contract.ForfaitCalculator.OpeningWindow;
import nc.ticagou.contract.ForfaitCalculator.Session;
import nc.ticagou.contract.ForfaitCalculator.Slot;
import nc.ticagou.contract.ForfaitCalculator.Tariff;
import nc.ticagou.funder.TariffCriterion;

class ForfaitCalculatorTest {

    /** Barème de la maquette Contrat : moins de 20 h, de 20 à 35 h, plus de 35 h. */
    static final Tariff VOLUME = new Tariff(TariffCriterion.HEURES_SEMAINE, 47,
            List.of(new Band(0, 620), new Band(20 * 60, 560), new Band(35 * 60 + 15, 510)));

    /** Barème de la maquette Paramètres : tranches A à D du quotient familial. */
    static final Tariff QUOTIENT = new Tariff(TariffCriterion.QUOTIENT_FAMILIAL, 47,
            List.of(new Band(0, 280), new Band(80_001, 360), new Band(150_001, 450), new Band(250_001, 520)));

    static Slot slot(int day, String arrives, String leaves) {
        return new Slot(day, LocalTime.parse(arrives), LocalTime.parse(leaves));
    }

    /** Planning type de Maëlys : lundi, mardi, jeudi 07:30–16:30, mercredi 07:30–12:30, vendredi 07:30–15:00. */
    static final List<Slot> MAELYS = List.of(slot(1, "07:30", "16:30"), slot(2, "07:30", "16:30"),
            slot(3, "07:30", "12:30"), slot(4, "07:30", "16:30"), slot(5, "07:30", "15:00"));

    static List<Slot> weekOf(int minutesPerDay) {
        var leaves = LocalTime.of(7, 0).plusMinutes(minutesPerDay);
        return List.of(slot(1, "07:00", leaves.toString()));
    }

    @Test
    void forfaitDeLaMaquette() {
        var quote = ForfaitCalculator.fixed(MAELYS, VOLUME, null);

        assertThat(quote.weeklyMinutes()).isEqualTo(39 * 60 + 30);
        assertThat(quote.monthlyMinutes()).isEqualTo(154 * 60 + 42);
        assertThat(quote.hourlyRate()).isEqualTo(510);
        assertThat(quote.monthlyFee()).isEqualTo(78_901);
    }

    @Test
    void avenantMercrediALaJournee() {
        var slots = List.of(slot(1, "07:30", "16:30"), slot(2, "07:30", "16:30"), slot(3, "07:30", "16:30"),
                slot(4, "07:30", "16:30"), slot(5, "07:30", "15:00"));

        var quote = ForfaitCalculator.fixed(slots, VOLUME, null);

        assertThat(quote.weeklyMinutes()).isEqualTo(43 * 60 + 30);
        assertThat(quote.monthlyFee()).isEqualTo(86_891);
    }

    @Test
    void bornesDesTranchesHoraires() {
        var nineteenThreeQuarters = List.of(slot(1, "07:00", "16:00"), slot(2, "07:00", "16:00"), slot(3, "07:00", "08:45"));
        assertThat(ForfaitCalculator.fixed(nineteenThreeQuarters, VOLUME, null).hourlyRate()).as("19 h 45").isEqualTo(620);
        assertThat(ForfaitCalculator.fixed(List.of(slot(1, "06:30", "16:30"), slot(2, "06:30", "16:30")), VOLUME, null)
                .hourlyRate()).as("20 h tout juste").isEqualTo(560);
        var thirtyFive = List.of(slot(1, "07:00", "14:00"), slot(2, "07:00", "14:00"), slot(3, "07:00", "14:00"),
                slot(4, "07:00", "14:00"), slot(5, "07:00", "14:00"));
        assertThat(ForfaitCalculator.fixed(thirtyFive, VOLUME, null).hourlyRate()).as("35 h tout juste").isEqualTo(560);
        var thirtyFiveQuarter = List.of(slot(1, "07:00", "14:15"), slot(2, "07:00", "14:00"), slot(3, "07:00", "14:00"),
                slot(4, "07:00", "14:00"), slot(5, "07:00", "14:00"));
        assertThat(ForfaitCalculator.fixed(thirtyFiveQuarter, VOLUME, null).hourlyRate()).isEqualTo(510);
    }

    @Test
    void trancheSelonLeQuotientFamilial() {
        assertThat(ForfaitCalculator.fixed(MAELYS, QUOTIENT, 80_000L).hourlyRate()).isEqualTo(280);
        assertThat(ForfaitCalculator.fixed(MAELYS, QUOTIENT, 80_001L).hourlyRate()).isEqualTo(360);
        assertThat(ForfaitCalculator.fixed(MAELYS, QUOTIENT, 400_000L).hourlyRate()).isEqualTo(520);
        assertThat(ForfaitCalculator.fixed(MAELYS, QUOTIENT, 80_000L).monthlyFee())
                .as("2370 min × 47 × 280 / 720 = 43 318,3").isEqualTo(43_318);
    }

    @Test
    void quotientExigeQuandLeBaremeEnDepend() {
        assertThatThrownBy(() -> ForfaitCalculator.fixed(MAELYS, QUOTIENT, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void planningVariable() {
        var quote = ForfaitCalculator.variable(120 * 60, VOLUME, null);

        assertThat(quote.weeklyMinutes()).as("120 h × 12 ÷ 47").isEqualTo(1838);
        assertThat(quote.hourlyRate()).isEqualTo(560);
        assertThat(quote.monthlyFee()).isEqualTo(67_200);
    }

    @Test
    void arrondiAuFrancLePlusProche() {
        var tariff = new Tariff(TariffCriterion.HEURES_SEMAINE, 6, List.of(new Band(0, 1)));

        assertThat(ForfaitCalculator.fixed(weekOf(60), tariff, null).monthlyFee()).as("0,5 → 1").isEqualTo(1);
        assertThat(ForfaitCalculator.fixed(weekOf(45), tariff, null).monthlyFee()).as("0,375 → 0").isZero();
    }

    static Session session(String day, String start, String end) {
        return new Session(LocalDate.parse(day), LocalTime.parse(start), LocalTime.parse(end));
    }

    /** Adaptation de deux semaines de la maquette : 10 séances, 52 h au total. */
    static final List<Session> ADAPTATION = List.of(
            session("2027-01-18", "09:00", "10:00"), session("2027-01-19", "09:00", "10:00"),
            session("2027-01-20", "09:00", "11:00"), session("2027-01-21", "08:30", "12:30"),
            session("2027-01-22", "08:30", "14:30"), session("2027-01-25", "07:30", "15:00"),
            session("2027-01-26", "07:30", "16:30"), session("2027-01-27", "07:30", "12:30"),
            session("2027-01-28", "07:30", "16:30"), session("2027-01-29", "07:30", "15:00"));

    @Test
    void coutDeLAdaptation() {
        long rate = ForfaitCalculator.adaptationRate(VOLUME, null);

        assertThat(rate).isEqualTo(620);
        assertThat(ForfaitCalculator.adaptationMinutes(ADAPTATION)).isEqualTo(52 * 60);
        assertThat(ForfaitCalculator.adaptationCost(ADAPTATION, AdaptationBilling.AU_TEMPS, rate)).isEqualTo(32_240);
        assertThat(ForfaitCalculator.adaptationCost(ADAPTATION, AdaptationBilling.PREMIERE_HEURE_OFFERTE, rate))
                .isEqualTo(31_620);
        assertThat(ForfaitCalculator.adaptationCost(ADAPTATION, AdaptationBilling.OFFERTE, rate)).isZero();
    }

    @Test
    void adaptationAuQuotientPrendLaTrancheDeLaFamille() {
        assertThat(ForfaitCalculator.adaptationRate(QUOTIENT, 120_000L)).isEqualTo(360);
    }

    static final Map<Integer, OpeningWindow> WEEKDAYS = Map.of(
            1, new OpeningWindow(LocalTime.of(6, 30), LocalTime.of(18, 30)),
            2, new OpeningWindow(LocalTime.of(6, 30), LocalTime.of(18, 30)),
            3, new OpeningWindow(LocalTime.of(6, 30), LocalTime.of(18, 30)),
            4, new OpeningWindow(LocalTime.of(6, 30), LocalTime.of(18, 30)),
            5, new OpeningWindow(LocalTime.of(6, 30), LocalTime.of(18, 30)));

    @Test
    void controleDuPlanning() {
        assertThat(ForfaitCalculator.checkSlots(MAELYS, WEEKDAYS)).isEmpty();
        assertThat(ForfaitCalculator.checkSlots(List.of(slot(1, "16:30", "07:30")), WEEKDAYS))
                .containsExactly("Lundi : le départ doit suivre l’arrivée.");
        assertThat(ForfaitCalculator.checkSlots(List.of(slot(2, "07:40", "16:30")), WEEKDAYS))
                .containsExactly("Mardi : les horaires se saisissent au quart d’heure.");
        assertThat(ForfaitCalculator.checkSlots(List.of(slot(6, "08:00", "12:00")), WEEKDAYS))
                .containsExactly("Samedi : la structure est fermée ce jour-là.");
        assertThat(ForfaitCalculator.checkSlots(List.of(slot(3, "06:00", "12:00")), WEEKDAYS))
                .containsExactly("Mercredi : la structure est ouverte de 06:30 à 18:30.");
    }
}
