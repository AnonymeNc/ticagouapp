package nc.ticagou.license;

import java.time.LocalDate;
import java.util.Set;

/**
 * Abonnement de la crèche (ADR 0013). {@code holder} : nom de la structure ; {@code places} : capacité agréée maximale ;
 * {@code modules} : modules optionnels compris, nul pour tous ; {@code desktops}, {@code tablets} : postes autorisés.
 */
public record License(String number, String holder, String plan, int places, Set<LicenseModule> modules,
                      int desktops, int tablets, LocalDate validUntil) {

    public boolean includes(LicenseModule module) {
        return modules == null || modules.contains(module);
    }
}
