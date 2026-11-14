/**
 * Fonctions de l'appareil, par les plugins Capacitor (ADR 0023) : scan de QR, verrouillage biométrique (ADR 0024),
 * aperçu masqué dans le sélecteur d'applications. Les plugins ne sont chargés que dans l'application native.
 */
import { hasNativePlugins, isNativeApp } from "./platform";

/** Scanne un QR code avec la caméra ; null si la personne annule ou si l'appareil ne sait pas le faire. */
export async function scanQr(instructions: string): Promise<string | null> {
  if (!hasNativePlugins()) return null;
  const { CapacitorBarcodeScanner, CapacitorBarcodeScannerTypeHint } = await import("@capacitor/barcode-scanner");
  try {
    const result = await CapacitorBarcodeScanner.scanBarcode({ hint: CapacitorBarcodeScannerTypeHint.QR_CODE, scanInstructions: instructions });
    return result.ScanResult || null;
  } catch {
    return null;
  }
}

export type LockSupport = {
  /** Face ID, empreinte… */
  biometry: boolean;
  /** Un code, un schéma ou une biométrie protège le téléphone. */
  secure: boolean;
};

/**
 * Ce que le téléphone permet pour verrouiller l'application. Simulation dans le navigateur (sans plugin) : on fait
 * comme si le téléphone avait un code, le bouton « Déverrouiller » suffit.
 */
export async function lockSupport(): Promise<LockSupport> {
  if (!hasNativePlugins()) return { biometry: false, secure: true };
  const { BiometricAuth } = await import("@aparajita/capacitor-biometric-auth");
  const result = await BiometricAuth.checkBiometry();
  return { biometry: result.isAvailable, secure: result.deviceIsSecure || result.isAvailable };
}

/** Demande Face ID / l'empreinte, ou le code du téléphone. true si la personne s'est identifiée. */
export async function unlockWithDevice(reason: string): Promise<boolean> {
  if (!hasNativePlugins()) return true;
  const { BiometricAuth } = await import("@aparajita/capacitor-biometric-auth");
  try {
    await BiometricAuth.authenticate({
      reason,
      cancelTitle: "Annuler",
      allowDeviceCredential: true,
      iosFallbackTitle: "Utiliser le code",
      androidTitle: "Ti’Cagou",
      androidSubtitle: reason,
      androidConfirmationRequired: false,
    });
    return true;
  } catch {
    return false;
  }
}

/**
 * Action irréversible depuis l’application (validation de factures, avoir, purge d’une famille, maquette C3) : Face ID
 * ou code du téléphone redemandé. Dans le navigateur, la confirmation de l’écran suffit.
 */
export async function confirmIrreversible(reason: string): Promise<boolean> {
  return isNativeApp() ? unlockWithDevice(reason) : true;
}

/** Contenu masqué dans le sélecteur d'applications, captures d'écran bloquées sur Android (ADR 0024). */
export async function enablePrivacyScreen(): Promise<void> {
  if (!hasNativePlugins()) return;
  const { PrivacyScreen } = await import("@capacitor/privacy-screen");
  await PrivacyScreen.enable().catch(() => {});
}

/** Retour au premier plan et passage en arrière-plan de l'application. */
export async function onAppStateChange(listener: (active: boolean) => void): Promise<() => void> {
  if (!hasNativePlugins()) return () => {};
  const { App } = await import("@capacitor/app");
  const handle = await App.addListener("appStateChange", ({ isActive }) => listener(isActive));
  return () => void handle.remove();
}
