/** Espace pro (lot 4) : demandes du personnel, compteurs, cahier d'équipe, agenda. */
import { api } from "@/lib/api";

export type RequestKind = "CONGES" | "RECUPERATION" | "HEURES_SUP";

export type StaffRequest = {
  id: string;
  userId: string;
  userName: string;
  kind: RequestKind;
  fromDay: string;
  toDay: string;
  minutes: number | null;
  note: string | null;
  status: "DEMANDEE" | "ACCEPTEE" | "REFUSEE" | "ANNULEE";
  answer: string | null;
  requestedAt: string;
  handledBy: string | null;
};

export type Counters = { year: number; leaveDaysTaken: number; overtimeMinutes: number; recoveredMinutes: number; balanceMinutes: number };

export type TeamNote = { id: string; sectionId: string | null; body: string; authorName: string; createdAt: string; read: boolean; readers: number };

export const spaceKeys = {
  mine: ["staff-space", "mine"] as const,
  pending: ["staff-space", "pending"] as const,
  counters: ["staff-space", "counters"] as const,
  notes: ["staff-space", "notes"] as const,
  calendar: ["staff-space", "calendar"] as const,
};

export const spaceApi = {
  request: (body: { kind: RequestKind; fromDay: string; toDay: string; minutes: number | null; note: string | null }) =>
    api<StaffRequest>("/staff-requests", { method: "POST", body }),
  mine: (signal?: AbortSignal) => api<StaffRequest[]>("/staff-requests/mine", { signal }),
  cancel: (id: string) => api<void>(`/staff-requests/${id}/cancel`, { method: "POST" }),
  pending: (signal?: AbortSignal) => api<StaffRequest[]>("/staff-requests", { signal }),
  accept: (id: string) => api<{ request: StaffRequest; shortageDays: string[] }>(`/staff-requests/${id}/accept`, { method: "POST" }),
  refuse: (id: string, reason: string) => api<StaffRequest>(`/staff-requests/${id}/refuse`, { method: "POST", body: { reason } }),
  counters: (signal?: AbortSignal) => api<Counters>("/staff-counters", { signal }),
  notes: (signal?: AbortSignal) => api<TeamNote[]>("/team-notes", { signal }),
  writeNote: (body: string) => api<TeamNote>("/team-notes", { method: "POST", body: { body, sectionId: null } }),
  readNote: (id: string) => api<void>(`/team-notes/${id}/read`, { method: "POST" }),
  calendar: (signal?: AbortSignal) => api<{ active: boolean }>("/staff-calendar", { signal }),
  newCalendar: () => api<{ token: string }>("/staff-calendar", { method: "POST" }),
  revokeCalendar: () => api<void>("/staff-calendar", { method: "DELETE" }),
};

export const kindLabels: Record<RequestKind, string> = { CONGES: "Congé", RECUPERATION: "Récupération", HEURES_SUP: "Heures supplémentaires" };
