package nc.ticagou.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import nc.ticagou.attendance.PickupRules.Candidate;
import nc.ticagou.attendance.PickupRules.Kind;

class PickupRulesTest {

    private final Candidate mother = new Candidate(Kind.RESPONSABLE, UUID.randomUUID(), "Sandrine Wahéo", "Mère",
            "77 12 34", true);
    private final Candidate stepFather = new Candidate(Kind.RESPONSABLE, UUID.randomUUID(), "Marc Tein",
            "Beau-père", "79 45 18", false);
    private final Candidate grandMother = new Candidate(Kind.AUTORISEE, UUID.randomUUID(), "Odile Wahéo",
            "Grand-mère", null, false);

    private final List<Candidate> list = PickupRules.pickupList(List.of(mother, stepFather), List.of(grandMother));

    @Test
    void responsableLegalEtPersonneAutoriseeFigurentSurLaListe() {
        assertThat(list).containsExactly(mother, grandMother);
        assertThat(PickupRules.find(list, Kind.RESPONSABLE, mother.id())).contains(mother);
        assertThat(PickupRules.find(list, Kind.AUTORISEE, grandMother.id())).contains(grandMother);
    }

    @Test
    void responsableNonLegalNEstPasAutorise() {
        assertThat(PickupRules.find(list, Kind.RESPONSABLE, stepFather.id())).isEmpty();
    }

    @Test
    void autrePersonneOuIdentifiantInconnuDemandentLaDirection() {
        assertThat(PickupRules.find(list, Kind.AUTRE, null)).isEmpty();
        assertThat(PickupRules.find(list, Kind.AUTORISEE, UUID.randomUUID())).isEmpty();
        // Bon identifiant, mauvais type : pas de confusion entre responsable et personne autorisée.
        assertThat(PickupRules.find(list, Kind.AUTORISEE, mother.id())).isEmpty();
        assertThat(PickupRules.find(list, null, mother.id())).isEmpty();
    }
}
