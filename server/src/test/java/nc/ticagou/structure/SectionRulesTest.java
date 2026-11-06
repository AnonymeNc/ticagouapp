package nc.ticagou.structure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import nc.ticagou.structure.SectionRules.Range;

class SectionRulesTest {

    static Range range(String name, int min, Integer max, int capacity) {
        return new Range(null, name, min, max, capacity);
    }

    @Test
    void tranchesContiguesAcceptees() {
        var ranges = List.of(range("Bébés", 2, 18, 12), range("Moyens", 18, 30, 16), range("Grands", 30, null, 20));

        assertThat(SectionRules.findOverlap(ranges)).isEmpty();
    }

    @Test
    void chevauchementDetecte() {
        var ranges = List.of(range("Bébés", 2, 20, 12), range("Moyens", 18, 30, 16));

        assertThat(SectionRules.findOverlap(ranges)).hasValueSatisfying(
                message -> assertThat(message).contains("Bébés", "Moyens"));
    }

    @Test
    void trancheSansLimiteChevaucheToutCeQuiSuit() {
        var ranges = List.of(range("Grands", 30, null, 20), range("Passerelle", 48, 60, 4));

        assertThat(SectionRules.findOverlap(ranges)).isPresent();
    }

    @Test
    void trancheIncluseDansUneAutreDetectee() {
        var ranges = List.of(range("Moyens", 18, 36, 16), range("Mini", 20, 24, 2));

        assertThat(SectionRules.findOverlap(ranges)).isPresent();
    }

    @Test
    void capaciteTotaleComparéeALAgrement() {
        var ranges = List.of(range("Bébés", 2, 18, 12), range("Moyens", 18, 30, 16), range("Grands", 30, null, 20));

        assertThat(SectionRules.checkCapacity(ranges, 48)).isEmpty();
        assertThat(SectionRules.checkCapacity(ranges, 47)).hasValueSatisfying(
                message -> assertThat(message).contains("48", "47"));
    }

    @Test
    void trouEntreDeuxSections() {
        var ranges = List.of(range("Bébés", 2, 18, 12), range("Moyens", 20, 30, 16), range("Grands", 30, null, 20));
        assertThat(SectionRules.findGaps(ranges)).containsExactly(
                "Aucune section n’accueille les enfants de 18 à 20 mois, entre « Bébés » et « Moyens ».");
        assertThat(SectionRules.findGaps(List.of(range("Bébés", 2, 18, 12), range("Moyens", 18, null, 16)))).isEmpty();
    }

    @Test
    void passagesProposesAuPremierDuMoisEtBloquesSiComplet() {
        var bebes = java.util.UUID.randomUUID();
        var moyens = java.util.UUID.randomUUID();
        var sections = List.of(new Range(bebes, "Bébés", 2, 18, 12), new Range(moyens, "Moyens", 18, 30, 1));
        var today = java.time.LocalDate.of(2026, 11, 1);
        var noa = new SectionRules.EnrolledChild(java.util.UUID.randomUUID(), "Noa", java.time.LocalDate.of(2025, 5, 12), bebes);
        var kaina = new SectionRules.EnrolledChild(java.util.UUID.randomUUID(), "Kaïna", java.time.LocalDate.of(2025, 9, 1), bebes);
        var leo = new SectionRules.EnrolledChild(java.util.UUID.randomUUID(), "Léo", java.time.LocalDate.of(2024, 9, 1), moyens);

        var grouped = SectionRules.passages(sections, List.of(noa, kaina, leo), today, true, true);
        assertThat(grouped).hasSize(1);
        var p = grouped.getFirst();
        assertThat(p.name()).isEqualTo("Noa");
        assertThat(p.on()).as("18 mois le 12/11, passage le 01/12").isEqualTo(java.time.LocalDate.of(2026, 12, 1));
        assertThat(p.toSectionId()).isEqualTo(moyens);
        assertThat(p.blocked()).as("Moyens : 1 place, déjà prise par Léo").isTrue();

        var exact = SectionRules.passages(sections, List.of(noa), today, false, false).getFirst();
        assertThat(exact.on()).isEqualTo(java.time.LocalDate.of(2026, 11, 12));
        assertThat(exact.blocked()).isFalse();
    }
}
