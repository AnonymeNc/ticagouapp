package nc.ticagou.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import nc.ticagou.billing.Xpf.Mode;

class XpfTest {

    @Test
    void divisionArrondieSelonLeMode() {
        assertThat(Xpf.divide(7, 2, Mode.PROCHE)).isEqualTo(4);
        assertThat(Xpf.divide(7, 2, Mode.INFERIEUR)).isEqualTo(3);
        assertThat(Xpf.divide(7, 2, Mode.SUPERIEUR)).isEqualTo(4);
        assertThat(Xpf.divide(13, 4, Mode.PROCHE)).isEqualTo(3);
        assertThat(Xpf.divide(12, 4, Mode.SUPERIEUR)).isEqualTo(3);
        assertThatThrownBy(() -> Xpf.divide(1, 0, Mode.PROCHE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void montantsNegatifsSymetriques() {
        assertThat(Xpf.divide(-7, 2, Mode.PROCHE)).isEqualTo(-4);
        assertThat(Xpf.divide(-7, 2, Mode.INFERIEUR)).isEqualTo(-4);
        assertThat(Xpf.divide(-7, 2, Mode.SUPERIEUR)).isEqualTo(-3);
        assertThat(Xpf.tgc(-4_400, 60)).isEqualTo(-264);
    }

    @Test
    void dureesAuTarifHoraire() {
        assertThat(Xpf.ofMinutes(75, 560)).isEqualTo(700);
        assertThat(Xpf.ofMinutes(15, 1_500)).isEqualTo(375);
        // 10 min à 625 F : 104,17 → 104
        assertThat(Xpf.ofMinutes(10, 625)).isEqualTo(104);
        // 1 min à 30 F : 0,5 → 1 (la moitié s'éloigne de zéro)
        assertThat(Xpf.ofMinutes(1, 30)).isEqualTo(1);
        assertThat(Xpf.ofMinutes(0, 560)).isZero();
    }

    @Test
    void pourcentagesEnDixiemes() {
        assertThat(Xpf.percent(65_600, 200)).isEqualTo(13_120);
        assertThat(Xpf.percent(65_605, 125)).isEqualTo(8_201);
        assertThat(Xpf.percent(1_000, 0)).isZero();
        assertThat(Xpf.tgc(10_000, 60)).isEqualTo(600);
    }

    @Test
    void prorataDeJours() {
        assertThat(Xpf.prorata(68_000, 10, 22)).isEqualTo(30_909);
        assertThat(Xpf.prorata(68_000, 22, 22)).isEqualTo(68_000);
        assertThat(Xpf.prorata(68_000, 0, 22)).isZero();
        assertThat(Xpf.prorata(68_000, 5, 0)).isEqualTo(68_000);
    }

    @Test
    void arrondiDuTotal() {
        assertThat(Xpf.roundTotal(43_604, 10, Mode.PROCHE)).isEqualTo(43_600);
        assertThat(Xpf.roundTotal(43_605, 10, Mode.PROCHE)).isEqualTo(43_610);
        assertThat(Xpf.roundTotal(43_601, 10, Mode.SUPERIEUR)).isEqualTo(43_610);
        assertThat(Xpf.roundTotal(43_699, 100, Mode.INFERIEUR)).isEqualTo(43_600);
        assertThat(Xpf.roundTotal(43_699, 1, Mode.INFERIEUR)).isEqualTo(43_699);
    }

    @Test
    void plafondsEtMinutes() {
        assertThat(Xpf.cap(15_000, 12_000L)).isEqualTo(12_000);
        assertThat(Xpf.cap(15_000, null)).isEqualTo(15_000);
        assertThat(Xpf.cap(-10, null)).isZero();
        assertThat(Xpf.roundMinutes(17, 15)).isEqualTo(15);
        assertThat(Xpf.roundMinutes(23, 15)).isEqualTo(30);
        assertThat(Xpf.roundMinutes(7, 15)).isZero();
        assertThat(Xpf.roundMinutes(8, 15)).isEqualTo(15);
        assertThat(Xpf.roundMinutes(17, 1)).isEqualTo(17);
    }
}
