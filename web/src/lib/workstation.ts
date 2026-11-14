/**
 * Identité du poste pour l'abonnement (ADR 0011) : un identifiant tiré au hasard à la première ouverture, gardé dans ce
 * navigateur, et un nom lisible affiché à la direction (Paramètres › Tablettes et abonnement). Le serveur compte les
 * postes à la connexion et refuse ceux qui dépassent l'abonnement ou ont été retirés.
 * Dans l'application Direction, l'identifiant est gardé dans le coffre du téléphone, par crèche (ADR 0024, 0028) :
 * il est lu au démarrage par {@link loadNativeWorkstation}.
 */
import { vaultKey } from "./native/creches";
import { isNativeApp, nativePlatform } from "./native/platform";
import { vault } from "./native/vault";

const ID_KEY = "ticagou.poste";

function storage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

function randomId(): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

let nativeId: string | null = null;

/** Application : lit (ou tire) l'identifiant de poste de la crèche courante dans le coffre. */
export async function loadNativeWorkstation(): Promise<void> {
  if (!isNativeApp()) return;
  const key = vaultKey("poste");
  try {
    nativeId = await vault().get(key);
    if (!nativeId) {
      nativeId = randomId();
      await vault().set(key, nativeId);
    }
  } catch {
    // Coffre indisponible : identifiant le temps de la session ; le serveur verra un nouveau poste à la prochaine ouverture.
    nativeId ??= randomId();
  }
}

export function workstationId(): string | null {
  if (isNativeApp()) return nativeId;
  const store = storage();
  if (!store) return null;
  let id = store.getItem(ID_KEY);
  if (!id) {
    id = randomId();
    store.setItem(ID_KEY, id);
  }
  return id;
}

/** « Chrome sur Windows » : assez pour que la direction reconnaisse un poste. */
export function workstationName(): string {
  const agent = navigator.userAgent;
  if (isNativeApp()) {
    if (nativePlatform() === "ios") return /iPad/.test(agent) ? "iPad" : "iPhone";
    return /Mobile/.test(agent) ? "Téléphone Android" : "Tablette Android";
  }
  const browser = /Edg\//.test(agent) ? "Edge" : /Firefox\//.test(agent) ? "Firefox" : /Chrome\//.test(agent) ? "Chrome" : /Safari\//.test(agent) ? "Safari" : "Navigateur";
  const system = /Windows/.test(agent) ? "Windows" : /Mac OS/.test(agent) ? "Mac" : /Android/.test(agent) ? "Android" : /Linux/.test(agent) ? "Linux" : null;
  return system ? `${browser} sur ${system}` : browser;
}

/** En-têtes envoyés avec les requêtes des PC du bureau. */
export function workstationHeaders(): Record<string, string> {
  const id = workstationId();
  if (!id) return {};
  return { "X-Ticagou-Poste": id, "X-Ticagou-Poste-Nom": encodeURIComponent(workstationName()) };
}
