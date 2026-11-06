package nc.ticagou.staffing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import nc.ticagou.staffing.RatioCalculator.Load;
import nc.ticagou.staffing.RatioCalculator.PresentAdult;
import nc.ticagou.staffing.RatioCalculator.PresentChild;
import nc.ticagou.staffing.RatioCalculator.Rule;
import nc.ticagou.staffing.RatioCalculator.Status;

class RatioCalculatorTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 30);
    private static final UUID BEBES = UUID.randomUUID();
    private static final UUID MOYENS = UUID.randomUUID();
    private static final UUID GRANDS = UUID.randomUUID();

    /** Règles de la maquette Paramètres : 1 pour 5 enfants qui ne marchent pas, 1 pour 8 qui marchent. */
    private static final List<Rule> RULES = List.of(
            new Rule("Enfants qui ne marchent pas", 0, 18, 5, LocalDate.of(2024, 1, 1), null),
            new Rule("Enfants qui marchent", 18, null, 8, LocalDate.of(2024, 1, 1), null));

    private static final LocalDate BABY = DAY.minusMonths(9);
    private static final LocalDate WALKER = DAY.minusMonths(26);

    private static List<PresentChild> children(UUID section, LocalDate birth, int count) {
        return Collections.nCopies(count, new PresentChild(section, birth));
    }

    private static List<PresentAdult> adults(UUID section, int count) {
        return Collections.nCopies(count, new PresentAdult(section));
    }

    private static Load single(List<Rule> rules, UUID section, List<PresentChild> children, int adults) {
        return RatioCalculator.compute(DAY, rules, List.of(section), children, adults(section, adults))
                .sections().get(section);
    }

    @Test
    void exactementAuRatioEstALaLimite() {
        var load = single(RULES, BEBES, children(BEBES, BABY, 5), 1);
        assertThat(load).isEqualTo(new Load(5, 1, 1, 5, Status.LIMITE, false));
    }

    @Test
    void unEnfantDeTropRendLEncadrementInsuffisant() {
        var load = single(RULES, BEBES, children(BEBES, BABY, 6), 1);
        assertThat(load.required()).isEqualTo(2);
        assertThat(load.status()).isEqualTo(Status.INSUFFISANT);
        assertThat(load.coveredPlaces()).isEqualTo(5);
    }

    @Test
    void valeursDuTableauDeBord() {
        var children = new ArrayList<PresentChild>();
        children.addAll(children(BEBES, BABY, 9));
        children.addAll(children(MOYENS, WALKER, 12));
        children.addAll(children(GRANDS, DAY.minusMonths(34), 12));
        var adults = new ArrayList<PresentAdult>();
        adults.addAll(adults(BEBES, 2));
        adults.addAll(adults(MOYENS, 1));
        adults.addAll(adults(GRANDS, 2));

        var result = RatioCalculator.compute(DAY, RULES, List.of(BEBES, MOYENS, GRANDS), children, adults);

        assertThat(result.sections().get(BEBES)).isEqualTo(new Load(9, 2, 2, 10, Status.LIMITE, false));
        assertThat(result.sections().get(MOYENS)).isEqualTo(new Load(12, 1, 2, 8, Status.INSUFFISANT, false));
        assertThat(result.sections().get(GRANDS)).isEqualTo(new Load(12, 2, 2, 16, Status.CONFORME, false));
        // Global : 9/5 + 24/8 = 4,8 parts pour 5 adultes.
        assertThat(result.global().required()).isEqualTo(5);
        assertThat(result.global().status()).isEqualTo(Status.LIMITE);
    }

    @Test
    void tranchesMeleesArrondiesAuSuperieur() {
        var children = new ArrayList<PresentChild>();
        children.addAll(children(MOYENS, BABY, 3));
        children.addAll(children(MOYENS, WALKER, 4));
        // 3/5 + 4/8 = 1,1 adulte : il en faut 2.
        var load = single(RULES, MOYENS, children, 1);
        assertThat(load.required()).isEqualTo(2);
        assertThat(load.status()).isEqualTo(Status.INSUFFISANT);
        assertThat(single(RULES, MOYENS, children, 2).status()).isEqualTo(Status.CONFORME);
    }

    @Test
    void dixHuitMoisLeJourMemeChangeDeTranche() {
        assertThat(RatioCalculator.childrenPerAdult(RULES, DAY.minusMonths(18), DAY)).isEqualTo(8);
        assertThat(RatioCalculator.childrenPerAdult(RULES, DAY.minusMonths(18).plusDays(1), DAY)).isEqualTo(5);
    }

    @Test
    void regleAVenirRemplaceLAncienneASaDate() {
        var rules = List.of(
                RULES.get(0),
                new Rule("Enfants qui marchent", 18, null, 8, LocalDate.of(2024, 1, 1), LocalDate.of(2026, 12, 31)),
                new Rule("Enfants qui marchent", 18, null, 6, LocalDate.of(2027, 1, 1), null));
        var birth = LocalDate.of(2024, 6, 1);
        assertThat(RatioCalculator.childrenPerAdult(rules, birth, LocalDate.of(2026, 12, 31))).isEqualTo(8);
        assertThat(RatioCalculator.childrenPerAdult(rules, birth, LocalDate.of(2027, 1, 1))).isEqualTo(6);

        var sevenWalkers = Collections.nCopies(7, new PresentChild(GRANDS, birth));
        var before = RatioCalculator.compute(LocalDate.of(2026, 12, 31), rules, List.of(GRANDS), sevenWalkers,
                adults(GRANDS, 1));
        var after = RatioCalculator.compute(LocalDate.of(2027, 1, 1), rules, List.of(GRANDS), sevenWalkers,
                adults(GRANDS, 1));
        assertThat(before.sections().get(GRANDS).status()).isEqualTo(Status.LIMITE);
        assertThat(after.sections().get(GRANDS).status()).isEqualTo(Status.INSUFFISANT);
    }

    @Test
    void sansEnfantRienNEstRequis() {
        var empty = single(RULES, BEBES, List.of(), 0);
        assertThat(empty).isEqualTo(new Load(0, 0, 0, 0, Status.CONFORME, false));
        var staffOnly = single(RULES, BEBES, List.of(), 2);
        assertThat(staffOnly.required()).isZero();
        assertThat(staffOnly.coveredPlaces()).isEqualTo(10);
        assertThat(staffOnly.status()).isEqualTo(Status.CONFORME);
    }

    @Test
    void sansAdulteUnSeulEnfantSuffitALAlerte() {
        var load = single(RULES, GRANDS, children(GRANDS, WALKER, 1), 0);
        assertThat(load.required()).isEqualTo(1);
        assertThat(load.status()).isEqualTo(Status.INSUFFISANT);
        assertThat(load.coveredPlaces()).isZero();
    }

    @Test
    void adulteDUneAutreSectionNeCompteQueDansLeGlobal() {
        var result = RatioCalculator.compute(DAY, RULES, List.of(BEBES, MOYENS),
                children(BEBES, BABY, 4), adults(MOYENS, 1));
        assertThat(result.sections().get(BEBES).status()).isEqualTo(Status.INSUFFISANT);
        assertThat(result.sections().get(MOYENS)).isEqualTo(new Load(0, 1, 0, 5, Status.CONFORME, false));
        assertThat(result.global()).isEqualTo(new Load(4, 1, 1, 5, Status.LIMITE, false));
    }

    @Test
    void enfantSansRegleRetientLaPlusExigeante() {
        var rules = List.of(new Rule("Enfants qui marchent", 18, null, 8, LocalDate.of(2024, 1, 1), null),
                new Rule("Passerelle", 36, null, 10, LocalDate.of(2024, 1, 1), null));
        var load = single(rules, BEBES, children(BEBES, BABY, 8), 1);
        assertThat(load.missingRule()).isTrue();
        assertThat(load.required()).isEqualTo(1);
        assertThat(load.status()).isEqualTo(Status.LIMITE);
    }

    @Test
    void regleExpireeIgnoree() {
        var rules = List.of(new Rule("Enfants qui marchent", 18, null, 10, LocalDate.of(2022, 1, 1),
                LocalDate.of(2023, 12, 31)), RULES.get(1));
        assertThat(RatioCalculator.childrenPerAdult(rules, WALKER, DAY)).isEqualTo(8);
    }
}
