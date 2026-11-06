package nc.ticagou.dataimport;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import nc.ticagou.child.Allergy;
import nc.ticagou.child.Consent;
import nc.ticagou.dataimport.ImportDtos.Issue;
import nc.ticagou.dataimport.ImportPlanner.Plan;

/** Contrôle d'un fichier de reprise : ce qui est créé, et surtout ce qui est refusé plutôt que deviné. */
class ImportPlannerTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);
    private static final UUID MOYENS = UUID.randomUUID();

    private final Map<String, List<String>> headers = new HashMap<>();
    private final Map<String, List<ImportFile.Row>> rows = new HashMap<>();
    private Set<String> existing = Set.of();

    /** Fichier minimal valide : une famille, son responsable légal, un enfant. */
    ImportPlannerTest() {
        header("Familles", "Réf famille *", "Libellé *", "Ville");
        header("Responsables", "Réf famille *", "Prénom *", "Nom *", "Lien *", "Responsable légal *", "Payeur *", "Téléphone mobile");
        header("Enfants", "Réf enfant *", "Réf famille *", "Prénom *", "Nom *", "Date de naissance *", "Section", "Date d’entrée");
        row("Familles", "F1", "Famille Gope", "Dumbéa");
        row("Responsables", "F1", "Marie", "Gope", "Mère", "oui", "oui", "75 12 34");
        row("Enfants", "E1", "F1", "Lila", "Gope", "15/03/2024", "Moyens", "01/02/2025");
    }

    private void header(String sheet, String... columns) {
        headers.put(sheet, List.of(columns));
        rows.put(sheet, new ArrayList<>());
    }

    private void row(String sheet, String... values) {
        var map = new HashMap<String, String>();
        var columns = headers.get(sheet);
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null && !values[i].isEmpty()) {
                map.put(ImportModel.key(columns.get(i)), values[i]);
            }
        }
        rows.get(sheet).add(new ImportFile.Row(rows.get(sheet).size() + 2, map));
    }

    private Plan plan() {
        var sheets = headers.keySet().stream()
                .map(name -> new ImportFile.Sheet(name, headers.get(name), rows.get(name))).toList();
        return new ImportPlanner(VALIDATOR, Map.of("moyens", MOYENS), existing, TODAY)
                .plan(new ImportFile(sheets, LocalDate.of(1899, 12, 30)));
    }

    private static List<String> messages(List<Issue> issues) {
        return issues.stream().map(Issue::message).toList();
    }

    @Test
    void unFichierMinimalCreeLaFamilleEtLEnfant() {
        var plan = plan();

        assertThat(plan.errors()).isEmpty();
        var family = plan.families().getFirst();
        assertThat(family.family().label()).isEqualTo("Famille Gope");
        var child = family.children().getFirst();
        assertThat(child.identity().sectionId()).isEqualTo(MOYENS);
        assertThat(child.identity().birthDate()).isEqualTo(LocalDate.of(2024, 3, 15));
        assertThat(child.health()).as("sans information, pas de fiche santé").isNull();
    }

    @Test
    void lesResponsablesLegauxPassentDevant() {
        row("Responsables", "F1", "Paul", "Gope", "Oncle", "non", "non");
        rows.get("Responsables").addFirst(rows.get("Responsables").removeLast());

        var guardians = plan().families().getFirst().guardians();

        assertThat(guardians).extracting("firstName").containsExactly("Marie", "Paul");
        assertThat(guardians).extracting("displayOrder").containsExactly(0, 1);
    }

    @Test
    void lesDatesExcelEtTexteSontComprises() {
        row("Familles", "F2", "Famille Wahéo");
        row("Responsables", "F2", "Jo", "Wahéo", "Père", "x", "1");
        row("Enfants", "E2", "F2", "Noa", "Wahéo", "45000", "Moyens", "2024-09-02");
        row("Enfants", "E3", "F2", "Tim", "Wahéo", "1-2-2025", "Moyens");

        var children = plan().families().get(1).children();

        assertThat(children.get(0).identity().birthDate()).isEqualTo(LocalDate.of(2023, 3, 15));
        assertThat(children.get(0).identity().entryDate()).isEqualTo(LocalDate.of(2024, 9, 2));
        assertThat(children.get(1).identity().birthDate()).isEqualTo(LocalDate.of(2025, 2, 1));
    }

    @Test
    void uneDateAmbigueEstRefusee() {
        rows.get("Enfants").clear();
        row("Enfants", "E1", "F1", "Lila", "Gope", "15/03/24", "Moyens");
        row("Enfants", "E2", "F1", "Noa", "Gope", "2024", "Moyens");
        row("Enfants", "E3", "F1", "Tim", "Gope", "31/02/2024", "Moyens");

        var plan = plan();

        assertThat(plan.errors()).hasSize(3).allMatch(i -> i.message().startsWith("Date de naissance : date invalide"));
        assertThat(plan.families()).as("la moindre erreur bloque tout le fichier").isEmpty();
    }

    @Test
    void lAllergieEtLaSanteSontRegroupeesParEnfant() {
        header("Santé", "Réf enfant *", "Régime", "PAI", "Motif du PAI", "PAI valable jusqu’au");
        header("Allergies", "Réf enfant *", "Allergène *", "Réaction", "Gravité *");
        header("Personnes autorisées", "Réf enfant *", "Prénom *", "Nom *", "Lien *", "Téléphone");
        header("Autorisations", "Réf enfant *", "Photos", "Sorties", "Soins d’urgence", "Signées le");
        row("Santé", "E1", "Sans porc", "oui", "Asthme", "31/12/2026");
        row("Allergies", "E1", "Arachide", "Œdème", "Sévère");
        row("Allergies", "E1", "Lait", "", "legere");
        row("Personnes autorisées", "E1", "Tina", "Gope", "Tante", "75 00 00");
        row("Autorisations", "E1", "oui", "non", "", "01/02/2025");

        var plan = plan();

        assertThat(plan.errors()).isEmpty();
        var child = plan.families().getFirst().children().getFirst();
        assertThat(child.health().diet()).isEqualTo("Sans porc");
        assertThat(child.health().paiActive()).isTrue();
        assertThat(child.health().allergies()).extracting("severity")
                .containsExactly(Allergy.Severity.SEVERE, Allergy.Severity.LEGERE);
        assertThat(child.authorizedPersons().getFirst().idVerified()).as("vérification faite à la crèche").isFalse();
        assertThat(child.consents()).extracting("type").containsExactly(Consent.Type.PHOTOS, Consent.Type.SORTIES);
        assertThat(child.consents()).extracting("granted").containsExactly(true, false);
    }

    @Test
    void uneAllergieSansGraviteEstRefuseeSansValeurParDefaut() {
        header("Allergies", "Réf enfant *", "Allergène *", "Réaction", "Gravité *");
        row("Allergies", "E1", "Arachide");
        row("Allergies", "E1", "Lait", "", "Très forte");

        var plan = plan();

        assertThat(messages(plan.errors())).containsExactly("Gravité : obligatoire.",
                "Gravité inconnue : Très forte. Indiquez Légère, Modérée ou Sévère.");
        assertThat(plan.errors().getFirst().row()).isEqualTo(2);
    }

    @Test
    void aucuneNEstPasUneAllergie() {
        header("Allergies", "Réf enfant *", "Allergène *", "Réaction", "Gravité *");
        row("Allergies", "E1", "Néant", "", "Légère");

        assertThat(messages(plan().errors())).containsExactly("« Néant » n’est pas une allergie : supprimez cette ligne.");
    }

    @Test
    void plusieursAllergiesDansUneCelluleSontSignalees() {
        header("Allergies", "Réf enfant *", "Allergène *", "Réaction", "Gravité *");
        row("Allergies", "E1", "Arachide, lait", "", "Sévère");

        var plan = plan();

        assertThat(plan.errors()).isEmpty();
        assertThat(messages(plan.warnings())).contains("Une allergie par ligne : « Arachide, lait » est repris tel quel.");
    }

    @Test
    void unPaiDecritSansOuiNiNonEstRefuse() {
        header("Santé", "Réf enfant *", "PAI", "Motif du PAI");
        row("Santé", "E1", "", "Asthme");

        assertThat(messages(plan().errors())).containsExactly("PAI : indiquez oui ou non, cette ligne décrit un PAI.");
    }

    @Test
    void unPaiExpireEstSignale() {
        header("Santé", "Réf enfant *", "PAI", "PAI valable jusqu’au");
        row("Santé", "E1", "oui", "31/08/2026");

        var plan = plan();

        assertThat(plan.errors()).isEmpty();
        assertThat(messages(plan.warnings())).contains("PAI expiré le 31/08/2026 : à renouveler.");
    }

    @Test
    void lesReferencesInconnuesOuEnDoubleSontRefusees() {
        row("Familles", "f1", "Doublon");
        row("Responsables", "F9", "Paul", "Inconnu", "Père", "oui", "oui");
        row("Enfants", "E1", "F1", "Noa", "Gope", "01/01/2025", "Moyens");
        header("Allergies", "Réf enfant *", "Allergène *", "Réaction", "Gravité *");
        row("Allergies", "E9", "Lait", "", "Légère");

        assertThat(messages(plan().errors())).containsExactlyInAnyOrder(
                "Référence déjà utilisée : f1.",
                "Famille inconnue : F9. Elle doit figurer dans la feuille Familles.",
                "Référence déjà utilisée : E1.",
                "Enfant inconnu : E9. Il doit figurer dans la feuille Enfants.");
    }

    @Test
    void uneFamilleDoitAvoirUnResponsableLegalEtUnEnfant() {
        rows.get("Responsables").clear();
        row("Responsables", "F1", "Paul", "Gope", "Oncle", "non", "oui");
        row("Familles", "F2", "Famille sans enfant");
        row("Responsables", "F2", "Jo", "Kaloï", "Père", "oui", "oui");

        assertThat(messages(plan().errors())).containsExactlyInAnyOrder(
                "Cette famille n’a aucun responsable légal dans la feuille Responsables.",
                "Aucun enfant ne se rattache à cette famille.");
    }

    @Test
    void uneFamilleSansPayeurEstSignalee() {
        rows.get("Responsables").clear();
        row("Responsables", "F1", "Marie", "Gope", "Mère", "oui", "non");

        var plan = plan();

        assertThat(plan.errors()).isEmpty();
        assertThat(messages(plan.warnings())).contains("Aucun payeur : à désigner avant la première facture.");
    }

    @Test
    void unEnfantDejaPresentOuEnDoubleEstRefuse() {
        existing = Set.of(ImportPlanner.childKey("LILA", "Gopé", LocalDate.of(2024, 3, 15)));
        row("Enfants", "E2", "F1", "Noa", "Gope", "01/01/2025", "Moyens");
        row("Enfants", "E3", "F1", "Noa", "Gope", "01/01/2025", "Moyens");

        assertThat(messages(plan().errors())).containsExactly(
                "Cet enfant existe déjà dans Ti’Cagou : Lila Gope.",
                "Cet enfant figure deux fois dans le fichier : Noa Gope.");
    }

    @Test
    void uneSectionInconnueEstRefuseeEtUneSectionVideSignalee() {
        rows.get("Enfants").clear();
        row("Enfants", "E1", "F1", "Lila", "Gope", "15/03/2024", "Papillons");
        row("Enfants", "E2", "F1", "Noa", "Gope", "01/01/2025");

        var plan = plan();

        assertThat(messages(plan.errors())).containsExactly("Section inconnue : Papillons. Les sections se règlent dans Paramètres.");
        assertThat(messages(plan.warnings())).contains("Aucune section : à compléter sur la fiche de l’enfant.");
    }

    @Test
    void lesReglesDesFormulairesSAppliquent() {
        rows.get("Responsables").clear();
        row("Responsables", "F1", "Marie", "Gope", "Mère", "oui", "oui", "abc");

        var plan = plan();

        assertThat(plan.errors()).extracting(Issue::sheet).containsExactly("Responsables");
        assertThat(messages(plan.errors())).containsExactly("Téléphone mobile : Numéro de téléphone invalide.");
    }

    @Test
    void uneColonneOuUneFeuilleInconnueEstSignaleePourNeRienPerdreEnSilence() {
        header("Enfants", "Réf enfant *", "Réf famille *", "Prénom *", "Nom *", "Date de naissance *", "Section", "Allergies");
        row("Enfants", "E1", "F1", "Lila", "Gope", "15/03/2024", "Moyens", "Arachide");
        header("Factures", "Numéro");
        row("Factures", "2026-001");

        var plan = plan();

        assertThat(plan.errors()).isEmpty();
        assertThat(messages(plan.warnings())).containsExactlyInAnyOrder("Colonne ignorée, non reprise : Allergies.",
                "Feuille ignorée, non reprise.");
    }

    @Test
    void uneFeuilleOuUneColonneObligatoireManquanteArreteLeControle() {
        headers.remove("Responsables");
        header("Enfants", "Réf enfant *", "Prénom *", "Nom *", "Date de naissance *");

        assertThat(messages(plan().errors())).containsExactlyInAnyOrder("Feuille manquante.", "Colonne manquante : Réf famille.");
    }

    @Test
    void sansEnfantLeFichierEstRefuse() {
        rows.values().forEach(List::clear);

        assertThat(messages(plan().errors())).contains("Aucun enfant dans cette feuille.");
    }
}
