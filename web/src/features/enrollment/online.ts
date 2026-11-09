/** Pré-inscription en ligne (lot 3, ADR 0020) : formulaire public et réglages de la direction. */
import { api } from "@/lib/api";
import type { RequestFormBody } from "./api";

export type QuestionKind = "TEXTE" | "OUI_NON" | "CHOIX";

export type Question = {
  id: string;
  label: string;
  kind: QuestionKind;
  options: string[];
  required: boolean;
  displayOrder: number;
  validFrom: string;
  validTo: string | null;
};

export type PublicForm = {
  enabled: boolean;
  structureName: string;
  intro: string | null;
  criteria: { id: string; label: string }[];
  questions: Question[];
};

export type OnlineSettings = { enabled: boolean; intro: string | null };

export type PublicSubmission = {
  request: Omit<RequestFormBody, "sectionId" | "familyId">;
  answers: Record<string, string>;
  consent: boolean;
  /** Champ piège : un humain ne le voit pas et le laisse vide. */
  website: string;
  startedAt: number;
};

export const onlineKeys = {
  publicForm: ["public", "preinscription"] as const,
  settings: ["enrollment", "online"] as const,
  questions: ["enrollment", "questions"] as const,
};

export const onlineApi = {
  publicForm: (signal?: AbortSignal) => api<PublicForm>("/public/preinscription", { signal, auth: false }),
  submit: (body: PublicSubmission) => api<{ message: string }>("/public/preinscription", { method: "POST", body, auth: false }),
  settings: (signal?: AbortSignal) => api<OnlineSettings>("/enrollment/online", { signal }),
  updateSettings: (body: OnlineSettings) => api<OnlineSettings>("/enrollment/online", { method: "PUT", body }),
  questions: (signal?: AbortSignal) => api<Question[]>("/enrollment/questions", { signal }),
  addQuestion: (body: { label: string; kind: QuestionKind; options: string[]; required: boolean; displayOrder: number }) =>
    api<Question>("/enrollment/questions", { method: "POST", body }),
  retireQuestion: (id: string) => api<void>(`/enrollment/questions/${id}`, { method: "DELETE" }),
};
