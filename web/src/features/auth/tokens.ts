/**
 * Jetons de la session. Le jeton d'accès reste en mémoire. Le jeton de rafraîchissement dépend du poste :
 * - navigateur du bureau : cookie HttpOnly que le JavaScript ne lit jamais (ADR 0013) ;
 * - application Direction : coffre du téléphone (Keychain / Keystore), par crèche (ADR 0024) ;
 * - tablette (kiosque) : en mémoire, le temps de la session.
 */
import { api, ApiError } from "@/lib/api";
import { vaultKey } from "@/lib/native/creches";
import { isNativeApp } from "@/lib/native/platform";
import { vault } from "@/lib/native/vault";
import type { SessionResponse } from "./types";

export type TokenMode = "cookie" | "vault" | "memory";

let accessToken: string | null = null;
let refreshToken: string | null = null;
let mode: TokenMode = "cookie";
let inFlight: Promise<SessionResponse | null> | null = null;
/** Jeton lu dans le coffre au démarrage de l'application, avant le premier rendu. */
let vaultToken: string | null = null;
/** Écritures du coffre dans l'ordre : une rotation rapide ne doit pas être écrasée par l'ancienne valeur. */
let vaultWrites: Promise<void> = Promise.resolve();

/** persist : session gardée à la fermeture (bureau, application) ; false : tablette partagée. */
export function configureTokenStorage(options: { persist: boolean }) {
  mode = !options.persist ? "memory" : isNativeApp() ? "vault" : "cookie";
  refreshToken = mode === "vault" ? vaultToken : null;
}

export function tokenMode(): TokenMode {
  return mode;
}

/** Démarrage de l'application : relit la session de la crèche courante dans le coffre. */
export async function loadVaultSession(): Promise<void> {
  vaultToken = isNativeApp() ? await vault().get(vaultKey("session")).catch(() => null) : null;
  restored = vaultToken !== null;
}

let restored = false;

/** La session vient du coffre (application rouverte) : elle se déverrouille avant d’être utilisée (ADR 0024). */
export function sessionRestoredFromVault() {
  return restored;
}

/** En-têtes de la connexion : le navigateur demande le cookie, l'application reçoit le jeton dans la réponse. */
export function sessionHeaders(): Record<string, string> {
  return mode === "cookie" ? { "X-Ticagou-Session": "cookie" } : {};
}

export function getAccessToken() {
  return accessToken;
}

/** Au bureau, seul le serveur sait si le cookie est valable : il faut essayer. */
export function mayHaveSession() {
  return mode === "cookie" || refreshToken !== null;
}

function persist(token: string | null) {
  if (mode !== "vault") return;
  vaultToken = token;
  const key = vaultKey("session");
  vaultWrites = vaultWrites
    .then(() => (token ? vault().set(key, token) : vault().remove(key)))
    .catch(() => {
      // Coffre indisponible : la session vaut jusqu'à la fermeture de l'application.
    });
}

export function storeSession(session: SessionResponse) {
  accessToken = session.accessToken;
  refreshToken = session.refreshToken ?? null;
  persist(refreshToken);
}

export function clearSession() {
  accessToken = null;
  refreshToken = null;
  persist(null);
}

/** Tests : attend la fin des écritures du coffre. */
export function vaultSettled() {
  return vaultWrites;
}

/**
 * Échange le jeton de rafraîchissement (usage unique côté serveur). Les appels simultanés partagent
 * la même requête : rejouer un jeton déjà utilisé fermerait la session.
 */
export function refreshSession(): Promise<SessionResponse | null> {
  if (!mayHaveSession()) return Promise.resolve(null);
  if (!inFlight) {
    const token = refreshToken;
    inFlight = api<SessionResponse>("/auth/refresh", { method: "POST", body: token ? { refreshToken: token } : undefined, auth: false })
      .then((session) => {
        storeSession(session);
        return session;
      })
      .catch((error) => {
        // Ne pas effacer une session plus récente ouverte entre-temps. Dans l’application, une coupure réseau ne
        // perd pas la session gardée dans le coffre : seul un refus du serveur l’efface.
        const refused = mode !== "vault" || error instanceof ApiError;
        if (refreshToken === token && refused) clearSession();
        else if (refreshToken === token) accessToken = null;
        return null;
      })
      .finally(() => {
        inFlight = null;
      });
  }
  return inFlight;
}

export function currentRefreshToken() {
  return refreshToken;
}
