package nc.ticagou.dataimport;

import java.time.LocalDate;
import java.util.List;

import nc.ticagou.child.ChildDtos.AllergyDto;

public final class ImportDtos {

    private ImportDtos() {
    }

    /** Remarque sur le fichier ; {@code row} est absent quand elle porte sur toute une feuille. */
    public record Issue(String sheet, Integer row, String message) {
    }

    public record Counts(int families, int guardians, int children, int allergies, int pais, int authorizedPersons) {
    }

    /** Ce que la direction relit avant de valider, enfant par enfant. */
    public record ChildReview(String reference, String firstName, String lastName, LocalDate birthDate, String section,
                              List<AllergyDto> allergies, boolean pai, String paiReason, String diet,
                              List<String> pickupPersons) {
    }

    /** Aperçu : rien n'est écrit. {@code children} n'est rempli que si le fichier ne contient aucune erreur. */
    public record Preview(String fileHash, Counts counts, List<Issue> errors, List<Issue> warnings,
                          List<ChildReview> children) {
    }
}
