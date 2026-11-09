/** Appels au portail (même origine, cookie de session HttpOnly) et types des réponses. */

export class ApiError extends Error {
  constructor(public status: number, public code: string | null, message: string) {
    super(message);
  }
}

async function call<T>(path: string, init: { method?: string; body?: unknown; signal?: AbortSignal } = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  // Toute écriture porte cet en-tête : un formulaire d'un autre site ne peut pas l'ajouter (CSRF).
  if (init.method && init.method !== "GET") headers["X-Ticagou-Portail"] = "1";
  if (init.body !== undefined) headers["Content-Type"] = "application/json";
  let response: Response;
  try {
    response = await fetch(`/api/v1${path}`, {
      method: init.method ?? "GET",
      headers,
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
      credentials: "same-origin",
      signal: init.signal,
    });
  } catch (error) {
    if ((error as Error).name === "AbortError") throw error;
    throw new ApiError(0, "offline", "Pas de connexion internet : réessayez dans un instant.");
  }
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new ApiError(response.status, problem?.code ?? null, problem?.detail ?? "Une erreur est survenue.");
  }
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : "Une erreur est survenue.";
}

// --- Types ---------------------------------------------------------------------------------

export type SharedHealth = { label: string; text: string };

export type Child = {
  id: string;
  firstName: string;
  lastInitial: string | null;
  section: string | null;
  hasPhoto: boolean;
  sharedHealth: SharedHealth[];
};

export type Family = {
  structureId: string;
  structureName: string;
  structurePhone: string | null;
  paymentInfo: string | null;
  /** HH:MM ; null : la crèche ne pratique pas la non-facturation du repas pour une absence signalée tôt. */
  mealAbsenceCutoff: string | null;
  familyId: string;
  guardianId: string;
  firstName: string;
  children: Child[];
  unreadMessages: number;
  documentsToSign: number;
};

export type Me = { email: string; families: Family[] };

/** Élément reçu de la crèche : `data` est le contenu partagé, tel quel. */
export type Item<T> = { id: string; type: string; at: string | null; data: T; hasFile: boolean; structureId?: string; familyId?: string };

export type AttendanceData = { day: string; arrivedAt: string | null; departedAt: string | null; childId: string };
export type EntryData = { day: string; at: string; kind: "REPAS" | "SIESTE" | "CHANGE" | "MEDICAMENT" | "ACTIVITE" | "PHOTO" | "VIDEO" | "MOT"; text: string | null; details: Record<string, string> };
export type Day = { day: string; items: Item<AttendanceData | EntryData>[]; days: string[] };

export type InvoiceData = {
  number: string;
  kind: "FACTURE" | "AVOIR";
  month: string;
  issuedOn: string;
  dueOn: string | null;
  total: number;
  remaining: number;
  lines?: { label: string; amount: number }[];
};

export type DocumentData = {
  childId: string | null;
  kind: "CONTRAT" | "AUTORISATION";
  title: string;
  sha256: string;
  status: "A_SIGNER" | "SIGNE" | "REFUSE";
  signedAt?: string;
  signerName?: string;
};

export type MessageData = { author: string; body: string; sentAt: string; fromFamily: boolean; readAt?: string };
/** Actualité de la crèche ; la pièce jointe (PDF ou image) se lit par fileUrl. */
export type NewsData = { title: string; body: string; author: string; publishedAt: string; expiresOn: string | null; fileName?: string | null };
export type NewsItem = Item<NewsData> & { fileType: string | null };

export type AbsenceData = { childId: string; fromDay: string; toDay: string; reason: string | null; reportedAt: string };

// --- Appels --------------------------------------------------------------------------------

export const portal = {
  requestLink: (email: string) => call<{ message: string }>("/auth/request", { method: "POST", body: { email } }),
  verifyToken: (token: string) => call<{ email: string }>("/auth/verify", { method: "POST", body: { token } }),
  verifyCode: (email: string, code: string) => call<{ email: string }>("/auth/verify", { method: "POST", body: { email, code } }),
  logout: () => call<void>("/auth/logout", { method: "POST" }),

  me: (signal?: AbortSignal) => call<Me>("/me", { signal }),
  day: (structureId: string, childId: string, date?: string, signal?: AbortSignal) =>
    call<Day>(`/me/children/${structureId}/${childId}/day${date ? `?date=${date}` : ""}`, { signal }),
  photos: (structureId: string, childId: string, signal?: AbortSignal) =>
    call<(Item<EntryData> & { fileType: string | null })[]>(`/me/children/${structureId}/${childId}/photos`, { signal }),
  news: (signal?: AbortSignal) => call<NewsItem[]>("/me/news", { signal }),
  pushState: (endpoint?: string, signal?: AbortSignal) =>
    call<{ publicKey: string; categories: string[] | null }>(`/me/push${endpoint ? `?endpoint=${encodeURIComponent(endpoint)}` : ""}`, { signal }),
  subscribePush: (body: { endpoint: string; p256dh: string; auth: string; categories: string[] }) =>
    call<void>("/me/push", { method: "POST", body }),
  unsubscribePush: (endpoint: string) => call<void>("/me/push/unsubscribe", { method: "POST", body: { endpoint } }),
  invoices: (signal?: AbortSignal) => call<Item<InvoiceData>[]>("/me/invoices", { signal }),
  documents: (signal?: AbortSignal) => call<Item<DocumentData>[]>("/me/documents", { signal }),
  absences: (signal?: AbortSignal) => call<Item<AbsenceData>[]>("/me/absences", { signal }),
  sign: (structureId: string, documentId: string, decision: "ACCEPTE" | "REFUSE") =>
    call<{ status: string }>(`/me/documents/${structureId}/${documentId}/sign`, { method: "POST", body: { decision, certified: true } }),
  messages: (structureId: string, familyId: string, signal?: AbortSignal) =>
    call<Item<MessageData>[]>(`/me/messages?structureId=${structureId}&familyId=${familyId}`, { signal }),
  send: (structureId: string, familyId: string, body: string) =>
    call<Item<MessageData>>("/me/messages", { method: "POST", body: { structureId, familyId, body } }),
  reportAbsence: (body: { structureId: string; childId: string; fromDay: string; toDay: string; reason: string | null }) =>
    call<Item<AbsenceData>>("/me/absences", { method: "POST", body }),
};

export const fileUrl = (structureId: string, itemId: string) => `/api/v1/me/files/${structureId}/${itemId}`;
export const childPhotoUrl = (structureId: string, childId: string) => `/api/v1/me/children/${structureId}/${childId}/photo`;
