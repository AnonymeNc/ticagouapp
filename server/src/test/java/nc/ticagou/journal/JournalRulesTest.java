package nc.ticagou.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import nc.ticagou.common.ApiException;
import nc.ticagou.journal.DailyEntry.Kind;

class JournalRulesTest {

    @Test
    void repasAvecRepasEtQuantite() {
        assertThat(JournalRules.details(Kind.REPAS, Map.of("meal", "DEJEUNER", "quantity", "BIEN")))
                .containsExactly(Map.entry("meal", "DEJEUNER"), Map.entry("quantity", "BIEN"));
        assertThatThrownBy(() -> JournalRules.details(Kind.REPAS, Map.of("meal", "DEJEUNER")))
                .isInstanceOf(ApiException.class).hasMessageContaining("mangé");
    }

    @Test
    void biberonAvecQuantiteEnMillilitres() {
        assertThat(JournalRules.details(Kind.REPAS, Map.of("meal", "BIBERON", "quantity", "TOUT", "volumeMl", " 150 ")))
                .containsEntry("volumeMl", "150");
        assertThat(JournalRules.details(Kind.REPAS, Map.of("meal", "BIBERON", "quantity", "TOUT"))).doesNotContainKey("volumeMl");
        assertThatThrownBy(() -> JournalRules.details(Kind.REPAS, Map.of("meal", "BIBERON")))
                .isInstanceOf(ApiException.class).hasMessageContaining("bu");
        assertThatThrownBy(() -> JournalRules.details(Kind.REPAS, Map.of("meal", "BIBERON", "quantity", "TOUT", "volumeMl", "900")))
                .isInstanceOf(ApiException.class).hasMessageContaining("millilitres");
        assertThatThrownBy(() -> JournalRules.details(Kind.REPAS, Map.of("meal", "DEJEUNER", "quantity", "TOUT", "volumeMl", "150")))
                .isInstanceOf(ApiException.class).hasMessageContaining("biberon");
    }

    @Test
    void medicamentNommeEtPourUnSeulEnfant() {
        assertThat(JournalRules.details(Kind.MEDICAMENT, Map.of("medicine", " Doliprane ", "dose", "2,5 ml")))
                .containsExactly(Map.entry("medicine", "Doliprane"), Map.entry("dose", "2,5 ml"));
        assertThatThrownBy(() -> JournalRules.details(Kind.MEDICAMENT, Map.of("dose", "2,5 ml")))
                .isInstanceOf(ApiException.class).hasMessageContaining("médicament");
        assertThatThrownBy(() -> JournalRules.details(Kind.MEDICAMENT, Map.of("medicine", "Doliprane", "allergie", "x")))
                .isInstanceOf(ApiException.class).hasMessageContaining("Détail inconnu");
        JournalRules.checkChildren(Kind.MEDICAMENT, 1);
        JournalRules.checkChildren(Kind.REPAS, 3);
        assertThatThrownBy(() -> JournalRules.checkChildren(Kind.MEDICAMENT, 2))
                .isInstanceOf(ApiException.class).hasMessageContaining("enfant par enfant");
    }

    @Test
    void heureDUneSaisieFaiteApresCoup() {
        var zone = java.time.ZoneId.of("Pacific/Noumea");
        var at = java.time.Instant.parse("2026-11-05T00:30:00Z"); // 11:30 à Nouméa
        assertThat(JournalRules.occurredAt(null, at, zone)).isEqualTo(at);
        assertThat(JournalRules.occurredAt("", at, zone)).isEqualTo(at);
        assertThat(JournalRules.occurredAt("10:15", at, zone)).isEqualTo(java.time.Instant.parse("2026-11-04T23:15:00Z"));
        assertThatThrownBy(() -> JournalRules.occurredAt("11:45", at, zone))
                .isInstanceOf(ApiException.class).hasMessageContaining("futur");
        assertThatThrownBy(() -> JournalRules.occurredAt("25:00", at, zone)).isInstanceOf(ApiException.class);
    }

    @Test
    void siesteReveilApresEndormissement() {
        assertThat(JournalRules.details(Kind.SIESTE, Map.of("from", "12:45", "to", "14:10")))
                .containsEntry("from", "12:45").containsEntry("to", "14:10");
        assertThat(JournalRules.details(Kind.SIESTE, Map.of("from", "12:45"))).doesNotContainKey("to");
        assertThatThrownBy(() -> JournalRules.details(Kind.SIESTE, Map.of("from", "14:00", "to", "13:00")))
                .hasMessageContaining("réveil");
        assertThatThrownBy(() -> JournalRules.details(Kind.SIESTE, Map.of("from", "midi")))
                .hasMessageContaining("endormissement");
    }

    @Test
    void detailInconnuRefuse() {
        // Rien d'imprévu (une consigne médicale glissée dans un détail) ne peut être enregistré puis partagé.
        assertThatThrownBy(() -> JournalRules.details(Kind.CHANGE, Map.of("change", "SELLES", "medicament", "Doliprane")))
                .hasMessageContaining("Détail inconnu");
        assertThatThrownBy(() -> JournalRules.details(Kind.ACTIVITE, Map.of("x", "y")))
                .hasMessageContaining("Détail inconnu");
        assertThat(JournalRules.details(Kind.PHOTO, null)).isEmpty();
    }

    @Test
    void texteObligatoirePourActiviteEtMot() {
        assertThatThrownBy(() -> JournalRules.text(Kind.ACTIVITE, "  ")).hasMessageContaining("activité");
        assertThatThrownBy(() -> JournalRules.text(Kind.MOT, null)).hasMessageContaining("mot");
        assertThat(JournalRules.text(Kind.REPAS, "  ")).isNull();
        assertThat(JournalRules.text(Kind.ACTIVITE, " Peinture au doigt ")).isEqualTo("Peinture au doigt");
        assertThatThrownBy(() -> JournalRules.text(Kind.MOT, "x".repeat(501))).hasMessageContaining("500");
    }
}
