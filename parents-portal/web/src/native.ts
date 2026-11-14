/**
 * Application Ti’Cagou Parents (ADR 0023, 0024) : même interface que la PWA, dans une coque Capacitor. Elle parle au
 * portail par son adresse publique (autre origine), avec un jeton porteur gardé dans le trousseau du téléphone, et
 * ajoute un jeton de média aux adresses des photos et fichiers. Dans le navigateur : rien de tout cela.
 * `VITE_PORTAIL_APP_SIMULEE=1` fait passer le navigateur pour l'application (essais), sans plugin natif.
 */
import { Capacitor } from "@capacitor/core";

const SIMULATED = import.meta.env.VITE_PORTAIL_APP_SIMULEE === "1";

export const IS_APP = Capacitor.isNativePlatform() || SIMULATED;

/** Préfixe des appels : vide dans le navigateur (même origine), adresse du portail dans l'application. */
export const PORTAL_URL = IS_APP ? (import.meta.env.VITE_PORTAIL_URL || "https://parents.ticagou.nc").replace(/\/$/, "") : "";

export const APP_VERSION: string = import.meta.env.VITE_TICAGOU_APP_VERSION || "0.0.0";

export function hasPlugins() {
  return Capacitor.isNativePlatform();
}

export function appHeaders(): Record<string, string> {
  if (!IS_APP) return {};
  const platform = Capacitor.getPlatform() === "ios" ? "ios" : "android";
  return { "X-Ticagou-App": `parents/${APP_VERSION} (${platform})` };
}

// --- Coffre : Keychain / Keystore, ou mémoire (simulation, tests) -------------------------------

const memory = new Map<string, string>();

async function secure() {
  const { SecureStorage, KeychainAccess } = await import("@aparajita/capacitor-secure-storage");
  await SecureStorage.setKeyPrefix("ticagou.parents.");
  await SecureStorage.setDefaultKeychainAccess(KeychainAccess.whenUnlockedThisDeviceOnly);
  return SecureStorage;
}

export const vault = {
  get: async (key: string) => (hasPlugins() ? (await secure()).getItem(key) : memory.get(key) ?? null),
  set: async (key: string, value: string) => (hasPlugins() ? (await secure()).setItem(key, value) : void memory.set(key, value)),
  remove: async (key: string) => (hasPlugins() ? (await secure()).removeItem(key) : void memory.delete(key)),
};

/** Préférences sans secret (verrouillage choisi) : Preferences, ou stockage local du navigateur. */
export const preferences = {
  get: async (key: string): Promise<string | null> => {
    if (hasPlugins()) return (await (await import("@capacitor/preferences")).Preferences.get({ key })).value;
    try {
      return window.localStorage.getItem(key);
    } catch {
      return null;
    }
  },
  set: async (key: string, value: string) => {
    if (hasPlugins()) return (await import("@capacitor/preferences")).Preferences.set({ key, value });
    try {
      window.localStorage.setItem(key, value);
    } catch {
      // Indisponible : réglage le temps de la session.
    }
  },
};

// --- Session ------------------------------------------------------------------------------

let sessionToken: string | null = null;
let mediaToken: string | null = null;
let mediaTimer: ReturnType<typeof setInterval> | null = null;

export function getSessionToken() {
  return sessionToken;
}

export function getMediaToken() {
  return mediaToken;
}

export function setMediaToken(token: string | null) {
  mediaToken = token;
}

/** Démarrage : relit la session du trousseau. */
export async function loadAppSession(): Promise<void> {
  if (IS_APP) sessionToken = await vault.get("session").catch(() => null);
}

export async function storeAppSession(token: string | null): Promise<void> {
  sessionToken = token;
  if (token) await vault.set("session", token).catch(() => {});
  else {
    mediaToken = null;
    await vault.remove("session").catch(() => {});
  }
}

/** Jeton de média renouvelé toutes les 30 minutes tant que l'application tourne (il vaut une heure). */
export function keepMediaToken(refresh: () => Promise<void>) {
  if (!IS_APP || mediaTimer) return;
  mediaTimer = setInterval(() => void refresh().catch(() => {}), 30 * 60_000);
}
