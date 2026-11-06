package nc.ticagou.staffing;

import java.util.UUID;

/**
 * Pointage, règle ou personnel modifié : le taux d'encadrement de la structure est recalculé et diffusé
 * une fois la transaction validée.
 */
public record StaffingChanged(UUID structureId) {
}
