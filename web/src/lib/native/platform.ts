/**
 * Où tourne le front (ADR 0023) : navigateur (servi par l'instance), ou application Capacitor Direction / Pointage.
 * La cible est fixée au build (`vite build --mode direction|pointage`) ; dans le navigateur, elle vaut « web ».
 * `VITE_TICAGOU_APP_SIMULEE=1` fait passer le navigateur pour l'application (essais dans le navigateur de
 * prévisualisation : choix de la crèche, session dans le coffre), sans plugin natif.
 */
import { Capacitor } from "@capacitor/core";

export type Cible = "web" | "direction" | "pointage";

export const CIBLE: Cible = parseCible(import.meta.env.VITE_TICAGOU_CIBLE);

/** Version de l'application, celle du serveur dont elle est construite (ADR 0027). */
export const APP_VERSION: string = import.meta.env.VITE_TICAGOU_APP_VERSION || "0.0.0";

const SIMULATED = import.meta.env.VITE_TICAGOU_APP_SIMULEE === "1";

function parseCible(value: string | undefined): Cible {
  return value === "direction" || value === "pointage" ? value : "web";
}

/** Plugins natifs disponibles (coque Capacitor iOS ou Android). */
export function hasNativePlugins(): boolean {
  return Capacitor.isNativePlatform();
}

/** Application Direction ou Pointage : hors de l'instance, adresse de la crèche choisie, session dans le coffre. */
export function isNativeApp(): boolean {
  return CIBLE !== "web" && (hasNativePlugins() || SIMULATED);
}

export function nativePlatform(): "ios" | "android" | null {
  const platform = Capacitor.getPlatform();
  if (platform === "ios" || platform === "android") return platform;
  // Simulation dans le navigateur : se présente comme Android (même origine https://localhost).
  return SIMULATED && CIBLE !== "web" ? "android" : null;
}

/** En-tête qui annonce l'application au serveur (ADR 0024) : « direction/1.4.0 (ios) ». */
export function appHeaders(): Record<string, string> {
  const platform = nativePlatform();
  if (!isNativeApp() || !platform) return {};
  return { "X-Ticagou-App": `${CIBLE}/${APP_VERSION} (${platform})` };
}

/** « 1.10.0 » est plus récent que « 1.9.0 » ; négatif si a < b. */
export function compareVersions(a: string, b: string): number {
  const left = a.split(".").map(Number);
  const right = b.split(".").map(Number);
  for (let i = 0; i < Math.max(left.length, right.length); i++) {
    const diff = (left[i] || 0) - (right[i] || 0);
    if (diff !== 0) return diff;
  }
  return 0;
}
