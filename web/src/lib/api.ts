/**
 * Accès au serveur (`/api/v1`, même origine que la page). Le jeton d'accès est ajouté automatiquement ; sur un 401,
 * la session est rafraîchie une fois (un seul rafraîchissement à la fois) puis la requête rejouée.
 * Le client typé généré depuis l'OpenAPI remplacera les déclarations de types manuelles.
 */
import { workstationHeaders } from "./workstation";

/** Erreur RFC 7807 renvoyée par le serveur. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly errors: Record<string, string> = {},
    readonly lockedUntil?: string,
  ) {
    super(message);
  }
}

type AuthHooks = {
  getAccessToken: () => string | null;
  /** Rafraîchit la session ; renvoie false si elle est perdue. */
  refresh: () => Promise<boolean>;
};

let authHooks: AuthHooks | null = null;
/** Jeton d'appareil de la tablette appairée (ADR 0005). */
let deviceHooks: AuthHooks | null = null;

export function registerAuthHooks(hooks: AuthHooks | null) {
  authHooks = hooks;
}

export function registerDeviceHooks(hooks: AuthHooks | null) {
  deviceHooks = hooks;
}

/** true : jeton de l'utilisateur ; "device" : jeton de la tablette ; false : route publique. */
type AuthMode = boolean | "device";

type RequestOptions = {
  method?: "GET" | "POST" | "PUT" | "DELETE";
  body?: unknown;
  signal?: AbortSignal;
  /** false pour les routes publiques (connexion, statut…), "device" pour la tablette appairée. */
  auth?: AuthMode;
  headers?: Record<string, string>;
};

export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const response = await request(path, options);
  if (response.status === 204) {
    return undefined as T;
  }
  // Corps vide (réponse sans contenu) : pas de JSON à lire.
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** Envoi de fichier (multipart) : photo, document joint, logo. */
export async function apiUpload<T>(path: string, form: FormData, method: "POST" | "PUT" = "POST", auth: AuthMode = true): Promise<T> {
  const response = await request(path, { method, form, auth });
  return (response.status === 204 ? undefined : await response.json()) as T;
}

/** Contenu binaire authentifié (photo, document, export) avec le nom de fichier proposé par le serveur. */
export async function apiBlob(path: string, signal?: AbortSignal, auth: AuthMode = true): Promise<{ blob: Blob; fileName: string | null }> {
  // Tout type accepté : une route qui déclare son format (PDF d'un contrat) refuse « application/json ».
  const response = await request(path, { signal, auth, accept: "*/*" });
  return { blob: await response.blob(), fileName: fileNameFrom(response.headers.get("Content-Disposition")) };
}

/** Réponse brute authentifiée, lue en flux (SSE de l'encadrement). */
export function apiStream(path: string, options: { signal?: AbortSignal; auth?: AuthMode } = {}) {
  return request(path, { ...options, accept: "text/event-stream" });
}

async function request(path: string, options: RequestOptions & { form?: FormData; accept?: string }): Promise<Response> {
  const { auth = true } = options;
  const hooks = auth === "device" ? deviceHooks : auth ? authHooks : null;
  let response = await send(path, options, auth);
  if (response.status === 401 && hooks && (await hooks.refresh())) {
    response = await send(path, options, auth);
  }
  if (!response.ok) {
    throw await toApiError(response);
  }
  return response;
}

function send(
  path: string,
  { method = "GET", body, form, signal, accept = "application/json", headers: extra }: RequestOptions & { form?: FormData; accept?: string },
  auth: AuthMode,
) {
  // La tablette s'identifie par son jeton d'appareil ; les PC du bureau par leur identifiant de poste (licence).
  const headers: Record<string, string> = { Accept: accept, ...(auth === "device" ? {} : workstationHeaders()), ...extra };
  const token = auth === "device" ? deviceHooks?.getAccessToken() : auth ? authHooks?.getAccessToken() : null;
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  return fetch(`/api/v1${path}`, {
    method,
    headers,
    body: form ?? (body === undefined ? undefined : JSON.stringify(body)),
    signal,
  });
}

/** « attachment; filename="a.pdf"; filename*=UTF-8''PAI%20No%C3%A9.pdf » → « PAI Noé.pdf » */
export function fileNameFrom(disposition: string | null): string | null {
  if (!disposition) return null;
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  if (encoded) return decodeURIComponent(encoded[1]);
  const plain = /filename="?([^";]+)"?/i.exec(disposition);
  return plain ? plain[1] : null;
}

/** Propose l'enregistrement d'un fichier reçu du serveur. */
export function saveBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

async function toApiError(response: Response): Promise<ApiError> {
  try {
    const problem = await response.json();
    return new ApiError(
      response.status,
      problem.code ?? "error",
      problem.detail ?? response.statusText,
      problem.errors ?? {},
      problem.lockedUntil,
    );
  } catch {
    return new ApiError(response.status, "error", `Erreur ${response.status}`);
  }
}

/** Message à afficher pour une erreur quelconque (réseau compris). */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  return "Le serveur de la crèche ne répond pas. Vérifiez qu’il est allumé et connecté au réseau.";
}

// --- Santé et installation ---------------------------------------------------

export type HealthResponse = {
  status: "UP" | "DEGRADED";
  version: string;
  serverTime: string;
  zone: string;
  database: "UP" | "DOWN";
};

export function getHealth(signal?: AbortSignal) {
  return api<HealthResponse>("/health", { signal, auth: false });
}

export type SetupStatus = { initialized: boolean; structureName: string | null };

export function getSetupStatus(signal?: AbortSignal) {
  return api<SetupStatus>("/setup/status", { signal, auth: false });
}
