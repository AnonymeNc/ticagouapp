/** Présences au poste bureau (miroir des DTO serveur, en attendant le client généré). */
import { api } from "@/lib/api";
import type { Role } from "@/features/auth/types";
import type { Instruction, PickupKind } from "@/features/kiosk/api";

export type ChildRow = {
  id: string;
  day: string;
  childId: string;
  firstName: string;
  lastName: string;
  sectionId: string | null;
  arrivedAt: string;
  departedAt: string | null;
  droppedOffBy: string | null;
  pickedUpByKind: PickupKind | null;
  pickedUpByName: string | null;
  unplanned: boolean;
  arrivalRecordedBy: string;
  departureRecordedBy: string | null;
  source: "TABLETTE" | "BUREAU";
  needsReview: boolean;
  reviewReason: string | null;
  instructions: Instruction[];
  override: {
    personName: string;
    relationship: string;
    idChecked: boolean;
    validatedBy: string;
    validatedAt: string;
    pinVerified: boolean;
    reason: string | null;
  } | null;
  version: number;
};

export type ExpectedRow = {
  childId: string;
  firstName: string;
  lastName: string;
  sectionId: string | null;
  expectedAt: string;
  late: boolean;
};

export type StaffRow = {
  id: string;
  day: string;
  userId: string;
  displayName: string;
  initials: string;
  jobTitle: string | null;
  role: Role | null;
  countsInRatio: boolean;
  sectionId: string | null;
  arrivedAt: string;
  departedAt: string | null;
  source: "PIN" | "PIN_HORS_LIGNE" | "BUREAU";
  pinVerified: boolean;
  needsReview: boolean;
  reviewReason: string | null;
  version: number;
};

export type AbsentStaff = {
  userId: string;
  displayName: string;
  initials: string;
  jobTitle: string | null;
  role: Role;
  countsInRatio: boolean;
  homeSectionId: string | null;
};

export type DayView = {
  day: string;
  children: ChildRow[];
  notArrived: ExpectedRow[];
  staff: StaffRow[];
  staffNotArrived: AbsentStaff[];
  expected: number;
};

export type RefusedOperation = {
  clientId: string;
  receivedAt: string;
  occurredAt: string;
  type: string;
  code: string | null;
  message: string | null;
};

export type ReviewList = { children: ChildRow[]; staff: StaffRow[]; refused: RefusedOperation[] };

export type Correction = {
  field: string;
  oldValue: string | null;
  newValue: string | null;
  reason: string;
  author: string;
  createdAt: string;
};

export type Target = "children" | "staff";

export const attendanceKeys = {
  day: (day: string) => ["attendance", day] as const,
  review: ["attendance", "review"] as const,
  all: ["attendance"] as const,
};

export const attendanceApi = {
  day: (day: string, signal?: AbortSignal) => api<DayView>(`/attendance?date=${day}`, { signal }),
  review: (signal?: AbortSignal) => api<ReviewList>("/attendance/review", { signal }),
  corrections: (target: Target, id: string, signal?: AbortSignal) =>
    api<Correction[]>(`/attendance/${target}/${id}/corrections`, { signal }),
  arrive: (childId: string, body: { sectionId?: string | null; droppedOffBy?: string | null }) =>
    api<string>(`/attendance/children/${childId}/arrival`, { method: "POST", body }),
  depart: (
    id: string,
    body: {
      pickup?: { kind: PickupKind; personId: string | null };
      other?: { personName: string; relationship: string; idChecked: boolean; reason: string };
    },
  ) => api<void>(`/attendance/children/records/${id}/departure`, { method: "POST", body }),
  correctChild: (
    id: string,
    body: { arrivedAt: string; departedAt: string | null; sectionId: string | null; pickedUpBy?: string | null; reason: string },
  ) => api<void>(`/attendance/children/records/${id}`, { method: "PUT", body }),
  addChild: (body: {
    childId: string;
    arrivedAt: string;
    departedAt: string | null;
    sectionId: string | null;
    pickedUpBy: string | null;
    reason: string;
  }) => api<string>("/attendance/children/records", { method: "POST", body }),
  correctStaff: (id: string, body: { arrivedAt: string; departedAt: string | null; sectionId: string | null; reason: string }) =>
    api<void>(`/attendance/staff/records/${id}`, { method: "PUT", body }),
  addStaff: (body: { userId: string; arrivedAt: string; departedAt: string | null; sectionId: string | null; reason: string }) =>
    api<string>("/attendance/staff/records", { method: "POST", body }),
  markReviewed: (target: Target, id: string, note: string) =>
    api<void>(`/attendance/${target}/${id}/review`, { method: "PUT", body: { note } }),
};

/** « 2026-11-02 » + « 07:42 » → instant ISO (Nouvelle-Calédonie : UTC+11, sans heure d'été). */
export function toInstant(day: string, time: string): string {
  return new Date(`${day}T${time}:00+11:00`).toISOString();
}

export const sourceLabels: Record<ChildRow["source"] | StaffRow["source"], string> = {
  TABLETTE: "Tablette",
  BUREAU: "Bureau",
  PIN: "Code PIN",
  PIN_HORS_LIGNE: "Code PIN hors connexion",
};

export const fieldLabels: Record<string, string> = {
  arrivee: "Arrivée",
  depart: "Départ",
  section: "Section",
  recupere_par: "Récupéré par",
  ajout: "Ajout",
  verification: "Vérification",
};
