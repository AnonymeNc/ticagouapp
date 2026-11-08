/**
 * Accès à la console (`/api/v1`, même origine). La session est un cookie HttpOnly ; toute écriture porte le jeton CSRF
 * lu dans le cookie `XSRF-TOKEN` (ADR 0017).
 */

/** Erreur RFC 7807 renvoyée par le serveur. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly lockedUntil?: string,
  ) {
    super(message);
  }
}

type RequestOptions = {
  method?: "GET" | "POST" | "PUT" | "DELETE";
  body?: unknown;
  signal?: AbortSignal;
};

/** Appelé quand le serveur répond 401 hors connexion : la session a expiré. */
let onUnauthenticated: (() => void) | null = null;

export function registerUnauthenticated(handler: (() => void) | null) {
  onUnauthenticated = handler;
}

export function csrfToken(): string | null {
  const match = document.cookie.split("; ").find((c) => c.startsWith("XSRF-TOKEN="));
  return match ? decodeURIComponent(match.slice("XSRF-TOKEN=".length)) : null;
}

export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = options.method ?? "GET";
  const headers: Record<string, string> = { Accept: "application/json" };
  if (options.body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  if (method !== "GET") {
    const token = csrfToken();
    if (token) {
      headers["X-XSRF-TOKEN"] = token;
    }
  }
  let response: Response;
  try {
    response = await fetch(`/api/v1${path}`, {
      method,
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      credentials: "same-origin",
      signal: options.signal,
    });
  } catch (e) {
    if (e instanceof DOMException && e.name === "AbortError") {
      throw e;
    }
    throw new ApiError(0, "network", "La console ne répond pas. Vérifiez la connexion.");
  }
  if (!response.ok) {
    const problem = (await response.json().catch(() => ({}))) as { code?: string; detail?: string; lockedUntil?: string };
    const error = new ApiError(response.status, problem.code ?? "error", problem.detail ?? "Une erreur est survenue.", problem.lockedUntil);
    if (response.status === 401 && !path.startsWith("/auth/")) {
      onUnauthenticated?.();
    }
    throw error;
  }
  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.lockedUntil) {
      return `${error.message} Réessayez après ${new Date(error.lockedUntil).toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit", timeZone: "Pacific/Noumea" })}.`;
    }
    return error.message;
  }
  return "Une erreur est survenue.";
}
