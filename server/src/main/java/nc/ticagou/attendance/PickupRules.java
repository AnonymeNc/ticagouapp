package nc.ticagou.attendance;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Qui peut venir chercher un enfant : ses responsables légaux et les personnes autorisées de sa fiche.
 * Toute autre personne bloque le départ jusqu'à la validation de la direction.
 */
public final class PickupRules {

    private PickupRules() {
    }

    public enum Kind { RESPONSABLE, AUTORISEE, AUTRE }

    /** Personne connue de la fiche ; {@code legalGuardian} n'a de sens que pour un responsable. */
    public record Candidate(Kind kind, UUID id, String name, String relationship, String phone,
                            boolean legalGuardian) {
    }

    /** Liste affichée au départ : responsables légaux puis personnes autorisées. */
    public static List<Candidate> pickupList(List<Candidate> guardians, List<Candidate> authorized) {
        return Stream.concat(
                guardians.stream().filter(g -> g.kind() == Kind.RESPONSABLE && g.legalGuardian()),
                authorized.stream().filter(a -> a.kind() == Kind.AUTORISEE)).toList();
    }

    /** Personne retenue si elle figure sur la liste, vide sinon (départ à valider par la direction). */
    public static java.util.Optional<Candidate> find(List<Candidate> pickupList, Kind kind, UUID id) {
        if (kind == null || kind == Kind.AUTRE || id == null) {
            return java.util.Optional.empty();
        }
        return pickupList.stream().filter(c -> c.kind() == kind && Objects.equals(c.id(), id)).findFirst();
    }
}
