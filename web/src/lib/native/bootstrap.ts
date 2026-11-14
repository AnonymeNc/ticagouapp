/**
 * Démarrage de l'application mobile, avant le premier rendu : crèche courante (Preferences), session et identifiant
 * de poste de cette crèche (coffre). Dans le navigateur, il n'y a rien à faire.
 */
import { loadVaultSession } from "@/features/auth/tokens";
import { loadNativeWorkstation } from "@/lib/workstation";
import { loadNativeDevice } from "@/features/kiosk/device";
import { loadCreches } from "./creches";
import { enablePrivacyScreen } from "./device";
import { isNativeApp } from "./platform";

/** Relit tout ce qui dépend de la crèche courante (démarrage, changement de crèche). */
export async function loadCurrentCreche(): Promise<void> {
  await Promise.all([loadVaultSession(), loadNativeWorkstation()]);
}

export async function bootstrapNative(): Promise<void> {
  if (!isNativeApp()) return;
  // Aperçu masqué dans le sélecteur d’applications : des données de santé d’enfants sont à l’écran (ADR 0024).
  void enablePrivacyScreen();
  await loadCreches();
  await Promise.all([loadCurrentCreche(), loadNativeDevice()]);
}
