package nc.ticagou.license;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.EnumSet;

import org.junit.jupiter.api.Test;

import nc.ticagou.license.LicenseTerms.State;

class LicenseTermsTest {

    static final LicenseTerms TERMS = new LicenseTerms(30, 30);
    static final LocalDate INSTALLED = LocalDate.of(2026, 11, 1);

    static License until(LocalDate validUntil) {
        return new License("TC-1", "Crèche", "Structure", 60, EnumSet.allOf(LicenseModule.class), 2, 5, validUntil);
    }

    @Test
    void evaluationJusquAuTrentiemeJourInclus() {
        var last = TERMS.status(INSTALLED, LocalDate.of(2026, 11, 30), null);
        assertThat(last.state()).isEqualTo(State.EVALUATION);
        assertThat(last.daysLeft()).isEqualTo(1);
        assertThat(last.message()).contains("30/11/2026", "encore 1 jour");
        var ended = TERMS.status(INSTALLED, LocalDate.of(2026, 12, 1), null);
        assertThat(ended.state()).isEqualTo(State.EVALUATION_TERMINEE);
        assertThat(ended.state().readOnly()).isTrue();
    }

    @Test
    void renouvellementPuisGracePuisLectureSeule() {
        var license = until(LocalDate.of(2027, 3, 31));
        assertThat(TERMS.status(INSTALLED, LocalDate.of(2027, 3, 1), license).state()).isEqualTo(State.ACTIVE);
        var renew = TERMS.status(INSTALLED, LocalDate.of(2027, 3, 2), license);
        assertThat(renew.state()).isEqualTo(State.A_RENOUVELER);
        assertThat(renew.daysLeft()).isEqualTo(29);
        assertThat(TERMS.status(INSTALLED, LocalDate.of(2027, 3, 31), license).state()).as("dernier jour valable")
                .isEqualTo(State.A_RENOUVELER);
        var grace = TERMS.status(INSTALLED, LocalDate.of(2027, 4, 1), license);
        assertThat(grace.state()).isEqualTo(State.EXPIREE_GRACE);
        assertThat(grace.deadline()).isEqualTo(LocalDate.of(2027, 5, 1));
        assertThat(grace.daysLeft()).isEqualTo(30);
        assertThat(grace.state().readOnly()).isFalse();
        assertThat(grace.state().expired()).isTrue();
        assertThat(TERMS.status(INSTALLED, LocalDate.of(2027, 4, 30), license).state()).isEqualTo(State.EXPIREE_GRACE);
        var blocked = TERMS.status(INSTALLED, LocalDate.of(2027, 5, 1), license);
        assertThat(blocked.state()).isEqualTo(State.LECTURE_SEULE);
        assertThat(blocked.message()).contains("31/03/2027", "lecture seule");
    }

    @Test
    void uneLicenceRemplaceLEvaluationTerminee() {
        var status = TERMS.status(INSTALLED, LocalDate.of(2027, 1, 15), until(LocalDate.of(2028, 1, 14)));
        assertThat(status.state()).isEqualTo(State.ACTIVE);
        assertThat(status.deadline()).isNull();
    }
}
