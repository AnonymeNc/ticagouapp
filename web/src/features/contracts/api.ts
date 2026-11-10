/** Types et appels des contrats d'accueil (miroir des DTO serveur, en attendant le client généré). */
import { api, apiBlob, apiUpload, saveBlob } from "@/lib/api";
import { formatMinutes, formatXpf } from "@/lib/format";
import type { ChildIdentity, FamilyInput, GuardianInput } from "@/features/children/api";

export type PlanningMode = "FIXE" | "VARIABLE";
export type AdaptationBilling = "AU_TEMPS" | "PREMIERE_HEURE_OFFERTE" | "OFFERTE";
export type TariffCriterion = "QUOTIENT_FAMILIAL" | "HEURES_SEMAINE";
export type ContractState = "BROUILLON" | "A_VENIR" | "EN_COURS" | "TERMINE";

export const billingLabels: Record<AdaptationBilling, string> = {
  AU_TEMPS: "Au temps de présence",
  PREMIERE_HEURE_OFFERTE: "Première heure offerte",
  OFFERTE: "Offerte",
};
export const stateLabels: Record<ContractState, string> = {
  BROUILLON: "Brouillon",
  A_VENIR: "À venir",
  EN_COURS: "En cours",
  TERMINE: "Terminé",
};

export type BillingMode = "MENSUALISE" | "REEL";

export type DayInput = { dayOfWeek: number; arrivesAt: string; leavesAt: string };
export type SessionInput = { date: string; startsAt: string; endsAt: string; note: string | null };
export type PlanningInput = {
  mode: PlanningMode;
  days: DayInput[];
  monthlyReservedMinutes: number | null;
  planningNoticeDays: number | null;
  familyQuotient: number | null;
  /** Mensualisé (même montant chaque mois) ou au réel (jours du planning du mois) ; nul = défaut des Paramètres. */
  billingMode: BillingMode | null;
  /** Formule de facturation du barème (libellé) à la place du calcul à l’heure ; nulle = calcul à l’heure. */
  formula?: string | null;
};
export type ContractDraft = {
  startDate: string;
  endDate: string;
  noticeMonths: number;
  planning: PlanningInput;
  adaptationBilling: AdaptationBilling;
  adaptation: SessionInput[];
};

export type Quote = {
  tariffScheduleId: string;
  tariffLabel: string;
  criterion: TariffCriterion;
  weeksPerYear: number;
  bands: { lowerBound: number; hourlyRate: number }[];
  bandLowerBound: number;
  hourlyRate: number;
  weeklyMinutes: number;
  monthlyMinutes: number;
  monthlyFee: number;
  registrationFee: number;
  adaptationMinutes: number;
  adaptationRate: number;
  adaptationCost: number;
  /** Mode retenu : celui choisi, sinon le défaut des Paramètres. */
  billingMode: BillingMode;
  /** Formule retenue, nulle pour le calcul à l’heure. */
  formula: string | null;
  /** Forfait calculé à l’heure, pour le comparer aux formules. */
  hourlyFee: number;
  /** Formules proposées par le barème applicable. */
  formulas: { label: string; monthlyFee: number }[];
  problems: string[];
};

export type ContractVersion = {
  number: number;
  effectiveFrom: string;
  reason: string | null;
  planningMode: PlanningMode;
  billingMode: BillingMode;
  days: DayInput[];
  monthlyReservedMinutes: number | null;
  planningNoticeDays: number | null;
  familyQuotient: number | null;
  status: "BROUILLON" | "SIGNE";
  signedOn: string | null;
  signedDocumentId: string | null;
  tariffScheduleId: string;
  criterion: TariffCriterion;
  bandLowerBound: number;
  hourlyRate: number;
  weeksPerYear: number;
  weeklyMinutes: number;
  monthlyMinutes: number;
  monthlyFee: number;
  registrationFee: number;
  formula: string | null;
  inForce: boolean;
  replaced: boolean;
};

export type Contract = {
  id: string;
  number: string;
  childId: string;
  childFirstName: string;
  childLastName: string;
  childBirthDate: string;
  sectionId: string | null;
  requestId: string | null;
  startDate: string;
  endDate: string;
  noticeMonths: number;
  adaptationBilling: AdaptationBilling;
  adaptationRate: number;
  adaptationCost: number;
  adaptation: SessionInput[];
  state: ContractState;
  versions: ContractVersion[];
  version: number;
};

export type AmendmentInput = { effectiveFrom: string; reason: string; planning: PlanningInput };

export type CreateContract = {
  childId?: string;
  requestId?: string;
  newChild?: { child: ChildIdentity; familyId?: string; family?: { family: FamilyInput; guardian: GuardianInput } };
  contract: ContractDraft;
};

export const contractKeys = {
  forChild: (childId: string) => ["contracts", childId] as const,
  detail: (id: string) => ["contract", id] as const,
};

export const contractsApi = {
  forChild: (childId: string, signal?: AbortSignal) => api<Contract[]>(`/contracts?childId=${childId}`, { signal }),
  detail: (id: string, signal?: AbortSignal) => api<Contract>(`/contracts/${id}`, { signal }),
  quote: (body: { effectiveFrom: string; planning: PlanningInput; adaptationBilling?: AdaptationBilling; adaptation?: SessionInput[] }, signal?: AbortSignal) =>
    api<Quote>("/contracts/quote", { method: "POST", body, signal }),
  create: (body: CreateContract) => api<{ id: string }>("/contracts", { method: "POST", body }),
  updateDraft: (id: string, body: ContractDraft) => api<void>(`/contracts/${id}`, { method: "PUT", body }),
  amend: (id: string, body: AmendmentInput) => api<number>(`/contracts/${id}/versions`, { method: "POST", body }),
  updateAmendment: (id: string, number: number, body: AmendmentInput) =>
    api<void>(`/contracts/${id}/versions/${number}`, { method: "PUT", body }),
  deleteDraft: (id: string, number: number) => api<void>(`/contracts/${id}/versions/${number}`, { method: "DELETE" }),
  sign: (id: string, number: number, signedOn: string, file: File | null) => {
    const form = new FormData();
    form.set("signedOn", signedOn);
    if (file) form.set("file", file);
    return apiUpload<void>(`/contracts/${id}/versions/${number}/sign`, form);
  },
  downloadPdf: async (contract: Contract, number: number) => {
    const { blob, fileName } = await apiBlob(`/contracts/${contract.id}/versions/${number}/pdf`);
    saveBlob(blob, fileName ?? `contrat-${contract.number}.pdf`);
  },
};

/**
 * Libellé d'une tranche : « Moins de 20 h », « De 20 h à 35 h », « À partir de 35 h 15 ».
 * Volume horaire : bornes en minutes par semaine, saisies au quart d'heure ; quotient familial : bornes en XPF.
 */
export const bandLabel = (criterion: TariffCriterion, bands: { lowerBound: number }[], lowerBound: number) => {
  const sorted = [...bands].sort((a, b) => a.lowerBound - b.lowerBound);
  const index = sorted.findIndex((b) => b.lowerBound === lowerBound);
  const next = sorted[index + 1];
  const hours = criterion === "HEURES_SEMAINE";
  const unit = (v: number) => (hours ? (v % 60 === 0 ? `${v / 60} h` : formatMinutes(v)) : formatXpf(v));
  if (index <= 0 && next) return `Moins de ${unit(next.lowerBound)}`;
  if (!next) return `À partir de ${unit(lowerBound)}`;
  return `De ${unit(lowerBound)} à ${unit(next.lowerBound - (hours ? 15 : 1))}`;
};
