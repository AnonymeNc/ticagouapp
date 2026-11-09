/** Portail parents côté crèche (ADR 0010) : types et appels, miroir des DTO serveur. */
import { api, apiBlob, apiUpload } from "@/lib/api";
import type { EntryKind } from "@/features/kiosk/api";

export type PortalState = "DESACTIVE" | "ACTIF" | "PURGE_EN_COURS";

export type Shares = {
  entries: boolean;
  photos: boolean;
  invoices: boolean;
  documents: boolean;
  messages: boolean;
  absences: boolean;
  paymentInfo: string | null;
  /** Heure (HH:MM) avant laquelle une absence signalée évite la facturation du repas ; vide : pas de message aux parents. */
  mealAbsenceCutoff: string | null;
};

export type PortalStatus = {
  moduleIncluded: boolean;
  state: PortalState;
  portalUrl: string | null;
  activatedAt: string | null;
  shares: Shares;
  lastSuccessAt: string | null;
  lastAttemptAt: string | null;
  lastError: string | null;
  pending: number;
  invitedGuardians: number;
};

export type SyncNow = { sent: number; received: number; error: string | null; status: PortalStatus };

export type GuardianAccess = {
  guardianId: string;
  firstName: string;
  lastName: string;
  email: string | null;
  invited: boolean;
  invitedAt: string | null;
  invitedBy: string | null;
};

export type FamilyAccess = { familyId: string; portalActive: boolean; guardians: GuardianAccess[] };

export type HealthShare = { id: string; label: string; text: string; sharedBy: string; sharedAt: string };

export type ConsentType = "PHOTOS" | "SORTIES" | "SOINS_URGENCE";

export type PortalDocument = {
  id: string;
  familyId: string;
  childId: string;
  childName: string | null;
  kind: "CONTRAT" | "AUTORISATION";
  title: string;
  status: "A_SIGNER" | "SIGNE" | "ENREGISTRE" | "RETIRE";
  proposedBy: string;
  proposedAt: string;
  signerName: string | null;
  signedAt: string | null;
  signerIp: string | null;
  sha256: string | null;
  recordedBy: string | null;
  recordedAt: string | null;
};

export type Message = { id: string; direction: "FAMILLE" | "EQUIPE"; authorName: string; body: string; sentAt: string; readAt: string | null };

export type ThreadSummary = {
  familyId: string;
  familyLabel: string;
  lastBody: string | null;
  lastAt: string | null;
  unread: number;
  portalAccess: boolean;
};

export type Thread = { familyId: string; familyLabel: string; portalAccess: boolean; messages: Message[] };

export type ReportedAbsence = {
  id: string;
  childId: string;
  childName: string | null;
  guardianName: string | null;
  fromDay: string;
  toDay: string;
  reason: string | null;
  reportedAt: string;
  status: "SIGNALEE" | "CONFIRMEE" | "ECARTEE";
};

export type JournalEntryDto = {
  id: string;
  day: string;
  occurredAt: string;
  kind: EntryKind;
  text: string | null;
  details: Record<string, string>;
  hasPhoto: boolean;
  shared: boolean;
  children: { id: string; firstName: string; lastName: string }[];
  authorName: string;
  updatedAt: string;
};

export type News = {
  id: string;
  sectionId: string | null;
  sectionName: string | null;
  title: string;
  body: string;
  fileName: string | null;
  publishedBy: string;
  publishedAt: string;
  expiresOn: string | null;
  withdrawnAt: string | null;
  status: "PUBLIEE" | "EXPIREE" | "RETIREE";
  /** Familles qui la voient sur le portail (publiée seulement). */
  families: number;
};

export type NewsInput = { title: string; body: string; sectionId: string | null; expiresOn: string | null; file: File | null };

export const portalKeys = {
  news: ["portal", "news"] as const,
  status: ["portal"] as const,
  family: (familyId: string) => ["portal", "family", familyId] as const,
  healthShares: (childId: string) => ["portal", "health", childId] as const,
  documents: (familyId?: string) => ["portal", "documents", familyId ?? "signed"] as const,
  threads: ["messages"] as const,
  unread: ["messages", "unread"] as const,
  thread: (familyId: string) => ["messages", familyId] as const,
  absences: ["portal", "reported-absences"] as const,
  journal: (day: string, childId?: string) => ["journal", day, childId ?? "all"] as const,
};

export const portalApi = {
  status: (signal?: AbortSignal) => api<PortalStatus>("/portal", { signal }),
  activate: (portalUrl: string, activationCode: string) =>
    api<PortalStatus>("/portal/activate", { method: "POST", body: { portalUrl, activationCode } }),
  shares: (shares: Shares) => api<PortalStatus>("/portal/shares", { method: "PUT", body: shares }),
  deactivate: (confirmation: string) => api<SyncNow>("/portal/deactivate", { method: "POST", body: { confirmation } }),
  sync: () => api<SyncNow>("/portal/sync", { method: "POST" }),

  family: (familyId: string, signal?: AbortSignal) => api<FamilyAccess>(`/families/${familyId}/portal`, { signal }),
  invite: (guardianId: string) => api<FamilyAccess>(`/guardians/${guardianId}/portal-access`, { method: "POST" }),
  revoke: (guardianId: string) => api<FamilyAccess>(`/guardians/${guardianId}/portal-access`, { method: "DELETE" }),

  healthShares: (childId: string, signal?: AbortSignal) => api<HealthShare[]>(`/children/${childId}/health-shares`, { signal }),
  shareHealth: (childId: string, body: { label: string; text: string }) =>
    api<HealthShare>(`/children/${childId}/health-shares`, { method: "POST", body }),
  unshareHealth: (id: string) => api<void>(`/health-shares/${id}`, { method: "DELETE" }),

  documents: (familyId?: string, signal?: AbortSignal) =>
    api<PortalDocument[]>(`/portal/documents${familyId ? `?familyId=${familyId}` : ""}`, { signal }),
  proposeContract: (contractId: string, versionNumber: number) =>
    api<PortalDocument>("/portal/documents/contract", { method: "POST", body: { contractId, versionNumber } }),
  proposeConsent: (childId: string, type: ConsentType) =>
    api<PortalDocument>("/portal/documents/consent", { method: "POST", body: { childId, type } }),
  withdraw: (id: string) => api<PortalDocument>(`/portal/documents/${id}/withdraw`, { method: "POST" }),
  record: (id: string) => api<PortalDocument>(`/portal/documents/${id}/record`, { method: "POST" }),

  threads: (signal?: AbortSignal) => api<ThreadSummary[]>("/messages", { signal }),
  unread: (signal?: AbortSignal) => api<number>("/messages/unread", { signal }),
  thread: (familyId: string, signal?: AbortSignal) => api<Thread>(`/families/${familyId}/messages`, { signal }),
  send: (familyId: string, body: string) => api<Thread>(`/families/${familyId}/messages`, { method: "POST", body: { body } }),

  broadcast: (sectionId: string | null, body: string) =>
    api<{ families: number }>("/messages/broadcast", { method: "POST", body: { sectionId, body } }),

  news: (signal?: AbortSignal) => api<News[]>("/portal/news", { signal }),
  publishNews: (input: NewsInput) => {
    const form = new FormData();
    form.append("title", input.title);
    form.append("body", input.body);
    if (input.sectionId) form.append("sectionId", input.sectionId);
    if (input.expiresOn) form.append("expiresOn", input.expiresOn);
    if (input.file) form.append("file", input.file);
    return apiUpload<News>("/portal/news", form);
  },
  withdrawNews: (id: string) => api<News>(`/portal/news/${id}/withdraw`, { method: "POST" }),
  newsFile: (id: string) => apiBlob(`/portal/news/${id}/file`),

  reportedAbsences: (signal?: AbortSignal) => api<ReportedAbsence[]>("/portal/reported-absences", { signal }),
  confirmAbsence: (id: string, reasonId: string) =>
    api<ReportedAbsence>(`/portal/reported-absences/${id}/confirm`, { method: "POST", body: { reasonId } }),
  dismissAbsence: (id: string) => api<ReportedAbsence>(`/portal/reported-absences/${id}/dismiss`, { method: "POST" }),

  journal: (day: string, childId?: string, signal?: AbortSignal) =>
    api<JournalEntryDto[]>(`/journal?day=${day}${childId ? `&childId=${childId}` : ""}`, { signal }),
  deleteEntry: (id: string) => api<void>(`/journal/${id}`, { method: "DELETE" }),
};

export const consentLabels: Record<ConsentType, string> = {
  PHOTOS: "Autorisation de photographier",
  SORTIES: "Autorisation de sorties",
  SOINS_URGENCE: "Autorisation de soins d’urgence",
};
