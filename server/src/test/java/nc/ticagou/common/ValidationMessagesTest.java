package nc.ticagou.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ValidationMessagesTest {

    @Test
    void libelleDuChampAvecLaLigneDUneListe() {
        assertThat(ValidationMessages.label("contract.planning.days[1].arrivesAt")).isEqualTo("Heure d’arrivée (ligne 2)");
        assertThat(ValidationMessages.label("birthDate")).isEqualTo("Date de naissance");
        assertThat(ValidationMessages.label("champInconnu")).isEqualTo("champInconnu");
    }

    @Test
    void uneErreurSeuleOuLaListe() {
        assertThat(ValidationMessages.detail(List.of())).isEqualTo(ValidationMessages.GENERIC);
        assertThat(ValidationMessages.detail(List.of("Nom : à renseigner."))).isEqualTo("Nom : à renseigner.");
        assertThat(ValidationMessages.detail(List.of("Nom : à renseigner.", "E-mail : adresse e-mail invalide.", "Nom : à renseigner.")))
                .isEqualTo("Certaines informations sont à corriger : Nom : à renseigner. E-mail : adresse e-mail invalide.");
    }
}
