package nc.ticagou.setup;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import nc.ticagou.structure.StructureDtos.SectionRequest;
import nc.ticagou.structure.StructureDtos.StructureIdentity;

public final class SetupDtos {

    private SetupDtos() {
    }

    /** Public : la tablette affiche le nom de la structure avant toute connexion. */
    public record SetupStatus(boolean initialized, String structureName) {
    }

    /** Compte de la direction créé à l'installation. */
    public record AdminAccount(
            @NotBlank @Size(max = 80) String firstName,
            @NotBlank @Size(max = 80) String lastName,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{3,60}$",
                    message = "L’identifiant comporte 3 à 60 caractères : lettres sans accent, chiffres, point, tiret")
            String login,
            @NotBlank @Size(max = 128) String password) {
    }

    public record SetupRequest(
            @NotNull @Valid StructureIdentity structure,
            @NotNull @Size(min = 1, max = 10, message = "Créez entre 1 et 10 sections") List<@Valid SectionRequest> sections,
            @NotNull @Valid AdminAccount admin) {
    }
}
