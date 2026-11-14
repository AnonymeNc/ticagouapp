/**
 * Mode kiosque de l'application Ti'Cagou Pointage (Android, ADR 0023) : plugin local `KioskMode` de
 * mobile/pointage/android. Ailleurs (navigateur, autre application), il n'existe pas.
 */
import { registerPlugin } from "@capacitor/core";
import { CIBLE, hasNativePlugins } from "./platform";

export type KioskState = {
  /** La directrice l'a activé ; rétabli à chaque démarrage. */
  enabled: boolean;
  /** Le système a bien verrouillé l'écran sur Ti'Cagou. */
  locked: boolean;
  /** Tablette gérée (Ti'Cagou propriétaire de l'appareil) : verrouillage complet, sans confirmation. */
  managed: boolean;
};

type KioskModePlugin = {
  enable(): Promise<KioskState>;
  disable(): Promise<KioskState>;
  status(): Promise<KioskState>;
};

const KioskMode = registerPlugin<KioskModePlugin>("KioskMode");

export function kioskModeAvailable(): boolean {
  return CIBLE === "pointage" && hasNativePlugins();
}

export async function kioskStatus(): Promise<KioskState | null> {
  return kioskModeAvailable() ? KioskMode.status() : null;
}

export async function enableKioskMode(): Promise<KioskState | null> {
  return kioskModeAvailable() ? KioskMode.enable() : null;
}

export async function disableKioskMode(): Promise<KioskState | null> {
  return kioskModeAvailable() ? KioskMode.disable() : null;
}
