/**
 * Identité du poste pour l'abonnement (ADR 0011) : un identifiant tiré au hasard à la première ouverture, gardé dans ce
 * navigateur, et un nom lisible affiché à la direction (Paramètres › Tablettes et abonnement). Le serveur compte les
 * postes à la connexion et refuse ceux qui dépassent l'abonnement ou ont été retirés.
 */

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

export function workstationId(): string | null {
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
