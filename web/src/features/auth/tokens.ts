/**
 * Jetons de la session. Le jeton d'accès reste en mémoire. Au bureau, le jeton de rafraîchissement est dans un cookie
 * HttpOnly que le JavaScript ne lit jamais (ADR 0013) ; sur la tablette, il reste en mémoire, le temps de la session.
 */
import { api } from "@/lib/api";
import type { SessionResponse } from "./types";

let accessToken: string | null = null;
let refreshToken: string | null = null;
let cookieSession = true;
let inFlight: Promise<SessionResponse | null> | null = null;

export function configureTokenStorage(options: { persist: boolean }) {
  cookieSession = options.persist;
  refreshToken = null;
}

export function getAccessToken() {
  return accessToken;
}

/** Au bureau, seul le serveur sait si le cookie est valable : il faut essayer. */
export function mayHaveSession() {
  return cookieSession || refreshToken !== null;
}

export function storeSession(session: SessionResponse) {
  accessToken = session.accessToken;
  refreshToken = session.refreshToken ?? null;
}

export function clearSession() {
  accessToken = null;
  refreshToken = null;
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
      .catch(() => {
        // Ne pas effacer une session plus récente ouverte entre-temps.
        if (refreshToken === token) clearSession();
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
