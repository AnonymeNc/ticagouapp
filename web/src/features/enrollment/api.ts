/** Types et appels du module Inscriptions (miroir des DTO serveur, en attendant le client généré). */
import { api } from "@/lib/api";

export type Stage = "NOUVELLE" | "A_RECONTACTER" | "VISITE_PREVUE" | "PLACE_PROPOSEE" | "SIGNEE" | "REFUSEE";
export type CareType = "REGULIER" | "OCCASIONNEL";
export type Verdict = "ACCEPTABLE" | "LATER" | "NONE" | "OUT_OF_RANGE";

/** Étapes dans l'ordre du tableau (maquette Inscriptions › Demandes). */
export const STAGES: Stage[] = ["NOUVELLE", "A_RECONTACTER", "VISITE_PREVUE", "PLACE_PROPOSEE", "SIGNEE", "REFUSEE"];
export const OPEN_STAGES: Stage[] = ["NOUVELLE", "A_RECONTACTER", "VISITE_PREVUE", "PLACE_PROPOSEE"];
export const stageLabels: Record<Stage, string> = {
  NOUVELLE: "Nouvelle demande",
  A_RECONTACTER: "À recontacter",
  VISITE_PREVUE: "Visite prévue",
  PLACE_PROPOSEE: "Place proposée",
  SIGNEE: "Contrat signé",
  REFUSEE: "Refusée",
};
export const isClosed = (stage: Stage) => stage === "SIGNEE" || stage === "REFUSEE";

export type CapacityTest = { verdict: Verdict; firstMonth: string | null; fullDays: number[] };

export type RequestCard = {
  id: string;
  childFirstName: string;
  childLastName: string;
  birthDate: string | null;
  expectedBirthDate: string | null;
  sectionId: string | null;
  desiredStart: string;
  days: number[];
  stage: Stage;
  receivedOn: string;
  stageChangedOn: string;
  followUpOn: string | null;
  visitAt: string | null;
  visitDone: boolean;
  offerExpiresOn: string | null;
  refusalReason: string | null;
  score: number;
  tags: string[];
  capacity: CapacityTest | null;
};

export type BoardStats = { inProgress: number; overdueFollowUps: number; visitsThisWeek: number; awaitingAnswers: number };
export type Board = { stats: BoardStats; requests: RequestCard[] };

export type Criterion = {
  id: string;
  label: string;
  points: number;
  validFrom: string;
  validTo: string | null;
  displayOrder: number;
};

export type Sibling = {
  id: string;
  firstName: string;
  lastName: string;
  birthDate: string;
  sectionId: string | null;
  entryDate: string | null;
  exitDate: string | null;
};

export type Note = { id: string; body: string; author: string; createdAt: string };
export type HistoryEvent = { id: string; label: string; author: string; createdAt: string };

export type RequestRecord = {
  id: string;
  receivedOn: string;
  childFirstName: string;
  childLastName: string;
  birthDate: string | null;
  expectedBirthDate: string | null;
  sectionId: string | null;
  desiredStart: string;
  careType: CareType;
  arrivesAt: string | null;
  leavesAt: string | null;
  days: number[];
  guardianFirstName: string;
  guardianLastName: string;
  relationship: string;
  phone: string | null;
  email: string | null;
  familyId: string | null;
  familyLabel: string | null;
  siblings: Sibling[];
  stage: Stage;
  stageChangedOn: string;
  followUpOn: string | null;
  visitAt: string | null;
  visitDone: boolean;
  offerExpiresOn: string | null;
  refusalReason: string | null;
  childId: string | null;
  contracts: { id: string; number: string }[];
  criteria: string[];
  availableCriteria: Criterion[];
  score: number;
  ranking: { rank: number; of: number } | null;
  capacity: CapacityTest | null;
  notes: Note[];
  history: HistoryEvent[];
  version: number;
  /** BUREAU ou EN_LIGNE (formulaire public, ADR 0020). */
  source: "BUREAU" | "EN_LIGNE";
  consentAt: string | null;
  answers: { label: string; value: string }[];
};

export type RequestFormBody = {
  childFirstName: string;
  childLastName: string;
  birthDate: string | null;
  expectedBirthDate: string | null;
  sectionId: string | null;
  desiredStart: string;
  careType: CareType;
  arrivesAt: string | null;
  leavesAt: string | null;
  days: number[];
  guardianFirstName: string;
  guardianLastName: string;
  relationship: string;
  phone: string | null;
  email: string | null;
  familyId: string | null;
  criteria: string[];
};

export type StageChange = {
  stage: Stage;
  followUpOn?: string | null;
  visitAt?: string | null;
  offerExpiresOn?: string | null;
  reason?: string | null;
};

export type CapacityGrid = {
  months: string[];
  openDays: number[];
  sections: {
    sectionId: string;
    name: string;
    color: string;
    capacity: number;
    days: { dayOfWeek: number; free: number[] }[];
  }[];
};

export const enrollmentKeys = {
  board: (filters: object) => ["enrollment-board", filters] as const,
  record: (id: string) => ["enrollment-request", id] as const,
  capacity: ["capacity"] as const,
  criteria: ["enrollment-criteria"] as const,
};

export const enrollmentApi = {
  board: (params: { includeClosed?: boolean }, signal?: AbortSignal) =>
    api<Board>(`/enrollment-requests${params.includeClosed ? "?includeClosed=true" : ""}`, { signal }),
  record: (id: string, signal?: AbortSignal) => api<RequestRecord>(`/enrollment-requests/${id}`, { signal }),
  create: (body: RequestFormBody) => api<{ id: string }>("/enrollment-requests", { method: "POST", body }),
  update: (id: string, body: RequestFormBody) => api<void>(`/enrollment-requests/${id}`, { method: "PUT", body }),
  changeStage: (id: string, body: StageChange) => api<void>(`/enrollment-requests/${id}/stage`, { method: "PUT", body }),
  moveVisit: (id: string, visitAt: string) => api<void>(`/enrollment-requests/${id}/visit`, { method: "PUT", body: { visitAt } }),
  visitDone: (id: string) => api<void>(`/enrollment-requests/${id}/visit/done`, { method: "POST" }),
  addNote: (id: string, body: string) => api<Note>(`/enrollment-requests/${id}/notes`, { method: "POST", body: { body } }),
  capacity: (signal?: AbortSignal) => api<CapacityGrid>("/capacity", { signal }),
  testCapacity: (body: { birthDate: string | null; sectionId: string | null; days: number[]; desiredStart: string; excludeRequestId?: string | null }) =>
    api<CapacityTest>("/capacity/test", { method: "POST", body }),
  criteria: (signal?: AbortSignal) => api<Criterion[]>("/enrollment-criteria", { signal }),
};
