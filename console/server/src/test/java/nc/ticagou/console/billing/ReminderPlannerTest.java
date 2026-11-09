package nc.ticagou.console.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import nc.ticagou.console.billing.ReminderPlanner.Candidate;
import nc.ticagou.console.billing.ReminderPlanner.Rule;

/** Calendrier des relances : jours ouvrés, fériés, délai propre d'un client, rattrapage, pauses, modèles. */
class ReminderPlannerTest {

    static final Set<LocalDate> HOLIDAYS = Set.of(LocalDate.of(2026, 11, 11), LocalDate.of(2026, 12, 25));

    static Rule rule(int level, int days, boolean active) {
        return new Rule(level, "Relance " + level, days, active, "Objet", "Texte", level >= 2, level >= 1, level == 3);
    }

    static final List<Rule> RULES = List.of(rule(0, -3, true), rule(1, 7, true), rule(2, 15, true), rule(3, 30, true));

    static Candidate invoice(String dueOn, Set<Integer> done) {
        return new Candidate(LocalDate.parse(dueOn), 174_960, false, false, 0, done);
    }

    @Test
    void joursOuvresEtFeries() {
        assertThat(ReminderPlanner.workingDay(LocalDate.of(2026, 11, 9), HOLIDAYS)).as("lundi").isTrue();
        assertThat(ReminderPlanner.workingDay(LocalDate.of(2026, 11, 14), HOLIDAYS)).as("samedi").isFalse();
        assertThat(ReminderPlanner.workingDay(LocalDate.of(2026, 11, 11), HOLIDAYS)).as("11 novembre").isFalse();
        assertThat(ReminderPlanner.nextWorkingDay(LocalDate.of(2026, 12, 25), HOLIDAYS)).as("Noël un vendredi : lundi 28")
                .isEqualTo(LocalDate.of(2026, 12, 28));
    }

    @Test
    void datePrevueReporteeAuJourOuvreEtDecaleePourUnClient() {
        // Échéance mercredi 04/11/2026 : J+7 = mercredi 11/11, férié → jeudi 12/11.
        assertThat(ReminderPlanner.scheduled(LocalDate.of(2026, 11, 4), rule(1, 7, true), 0, HOLIDAYS)).isEqualTo(LocalDate.of(2026, 11, 12));
        // Collectivité, relances décalées de 15 jours : J+22 = jeudi 26/11 ; le rappel courtois ne bouge pas.
        assertThat(ReminderPlanner.scheduled(LocalDate.of(2026, 11, 4), rule(1, 7, true), 15, HOLIDAYS)).isEqualTo(LocalDate.of(2026, 11, 26));
        assertThat(ReminderPlanner.scheduled(LocalDate.of(2026, 11, 4), rule(0, -3, true), 15, HOLIDAYS)).isEqualTo(LocalDate.of(2026, 11, 2));
    }

    @Test
    void niveauDuRattrapageEtUnicite() {
        var due = "2026-10-24";
        assertThat(ReminderPlanner.due(invoice(due, Set.of()), RULES, LocalDate.of(2026, 10, 30), HOLIDAYS)).as("J+6 : rien").isEmpty();
        assertThat(ReminderPlanner.due(invoice(due, Set.of()), RULES, LocalDate.of(2026, 11, 2), HOLIDAYS).orElseThrow().level())
                .as("J+7 tombé un samedi : lundi").isEqualTo(1);
        assertThat(ReminderPlanner.due(invoice(due, Set.of(1)), RULES, LocalDate.of(2026, 11, 3), HOLIDAYS)).as("déjà préparée").isEmpty();
        // Serveur arrêté trois semaines : on rattrape au niveau le plus élevé atteint, pas trois messages.
        assertThat(ReminderPlanner.due(invoice(due, Set.of()), RULES, LocalDate.of(2026, 11, 24), HOLIDAYS).orElseThrow().level()).isEqualTo(3);
        assertThat(ReminderPlanner.due(invoice(due, Set.of(2)), RULES, LocalDate.of(2026, 11, 9), HOLIDAYS))
                .as("un niveau plus élevé est passé : on ne revient pas en arrière").isEmpty();
    }

    @Test
    void rappelCourtoisAvantLEcheanceSeulement() {
        assertThat(ReminderPlanner.due(invoice("2026-11-20", Set.of()), RULES, LocalDate.of(2026, 11, 17), HOLIDAYS).orElseThrow().level())
                .isZero();
        var inactive = List.of(rule(0, -3, false), rule(1, 7, true));
        assertThat(ReminderPlanner.due(invoice("2026-11-20", Set.of()), inactive, LocalDate.of(2026, 11, 17), HOLIDAYS)).isEmpty();
        assertThat(ReminderPlanner.due(invoice("2026-11-20", Set.of()), List.of(rule(0, -3, true)), LocalDate.of(2026, 11, 23), HOLIDAYS))
                .as("échéance passée : plus de rappel courtois").isEmpty();
    }

    @Test
    void factureSoldeeOuEnPause() {
        var today = LocalDate.of(2026, 11, 24);
        assertThat(ReminderPlanner.due(new Candidate(LocalDate.of(2026, 10, 24), 0, false, false, 0, Set.of()), RULES, today, HOLIDAYS))
                .as("soldée la veille").isEmpty();
        assertThat(ReminderPlanner.due(new Candidate(LocalDate.of(2026, 10, 24), 10, true, false, 0, Set.of()), RULES, today, HOLIDAYS))
                .as("relances suspendues sur la facture").isEmpty();
        assertThat(ReminderPlanner.due(new Candidate(LocalDate.of(2026, 10, 24), 10, false, true, 0, Set.of()), RULES, today, HOLIDAYS))
                .as("client en pause").isEmpty();
        assertThat(ReminderPlanner.next(invoice("2026-10-24", Set.of(1)), RULES, HOLIDAYS)).contains(LocalDate.of(2026, 11, 9));
    }

    @Test
    void modeles() {
        assertThat(ReminderPlanner.render("Facture {numero}, {total} dus ({inconnue})", Map.of("numero", "F2026-0047", "total", "174 960 F")))
                .isEqualTo("Facture F2026-0047, 174 960 F dus ({inconnue})");
        assertThat(ReminderPlanner.render("Coût : {total}$", Map.of("total", "5 $"))).isEqualTo("Coût : 5 $$");
    }
}
