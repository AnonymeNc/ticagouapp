package nc.ticagou.license;

import java.time.LocalDate;

/**
 * Abonnement de la crèche (ADR 0013). Une seule formule, tout compris (ADR 0021) : {@code holder} : nom de la
 * structure ; {@code places} : capacité agréée maximale ; {@code desktops}, {@code tablets} : postes autorisés.
 */
public record License(String number, String holder, String plan, int places, int desktops, int tablets,
                      LocalDate validUntil) {
}
