/** Paramètres : barèmes tarifaires et critères d'inscription (miroir des DTO serveur). */
import { api } from "@/lib/api";
import type { Role } from "@/features/auth/types";
import type { TariffCriterion } from "@/features/contracts/api";
import type { Criterion } from "@/features/enrollment/api";

export type Band = { lowerBound: number; hourlyRate: number };
/** Formule de facturation : forfait mensuel nommé (« Temps plein », « Matins seulement »…), en francs entiers. */
export type Formula = { label: string; monthlyFee: number };

export type TariffSchedule = {
  id: string;
  label: string;
  criterion: TariffCriterion;
  weeksPerYear: number;
  registrationFee: number;
  validFrom: string;
  validTo: string | null;
  bands: Band[];
  formulas: Formula[];
  used: boolean;
  version: number;
};

export type TariffInput = Omit<TariffSchedule, "id" | "used" | "version">;
export type CriterionInput = Omit<Criterion, "id">;

export const criterionLabels: Record<TariffCriterion, string> = {
  HEURES_SEMAINE: "Selon le volume d’heures par semaine",
  QUOTIENT_FAMILIAL: "Selon le quotient familial",
};

export const settingsApi = {
  tariffs: (signal?: AbortSignal) => api<TariffSchedule[]>("/tariff-schedules", { signal }),
  createTariff: (body: TariffInput) => api<TariffSchedule>("/tariff-schedules", { method: "POST", body }),
  updateTariff: (id: string, body: TariffInput) => api<TariffSchedule>(`/tariff-schedules/${id}`, { method: "PUT", body }),
  duplicateTariff: (id: string, body: { label: string; validFrom: string }) =>
    api<TariffSchedule>(`/tariff-schedules/${id}/duplicate`, { method: "POST", body }),
  deleteTariff: (id: string) => api<void>(`/tariff-schedules/${id}`, { method: "DELETE" }),
  createCriterion: (body: CriterionInput) => api<Criterion>("/enrollment-criteria", { method: "POST", body }),
  updateCriterion: (id: string, body: CriterionInput) => api<Criterion>(`/enrollment-criteria/${id}`, { method: "PUT", body }),
  deleteCriterion: (id: string) => api<void>(`/enrollment-criteria/${id}`, { method: "DELETE" }),
};

// --- Encadrement, utilisateurs, appareils -----------------------------------------------------

export type RuleState = "EN_VIGUEUR" | "A_VENIR" | "EXPIREE";

export type RatioRule = {
  id: string;
  label: string;
  minAgeMonths: number;
  maxAgeMonths: number | null;
  childrenPerAdult: number;
  validFrom: string;
  validTo: string | null;
  state: RuleState;
  version: number;
};

export type RatioRuleInput = Omit<RatioRule, "id" | "state" | "version">;

export type UserRow = {
  id: string;
  firstName: string;
  lastName: string;
  role: Role;
  login: string | null;
  hasPassword: boolean;
  hasPin: boolean;
  active: boolean;
  locked: boolean;
  lastLoginAt: string | null;
  jobTitle: string | null;
  homeSectionId: string | null;
  countsInRatio: boolean;
};

export type UserInput = {
  firstName: string;
  lastName: string;
  role: Role;
  login: string | null;
  jobTitle: string | null;
  homeSectionId: string | null;
  countsInRatio: boolean;
};

export type DeviceState = "EN_ATTENTE" | "APPAIREE" | "RETIREE";

export type Device = {
  id: string;
  name: string;
  state: DeviceState;
  createdAt: string;
  pairedAt: string | null;
  lastSeenAt: string | null;
  userAgent: string | null;
  pairingExpiresAt: string | null;
};

export type PairingCode = { device: Device; code: string; expiresAt: string };

export const staffingApi = {
  rules: (signal?: AbortSignal) => api<RatioRule[]>("/ratio-rules", { signal }),
  createRule: (body: RatioRuleInput) => api<RatioRule>("/ratio-rules", { method: "POST", body }),
  updateRule: (id: string, body: RatioRuleInput) => api<RatioRule>(`/ratio-rules/${id}`, { method: "PUT", body }),
  deleteRule: (id: string) => api<void>(`/ratio-rules/${id}`, { method: "DELETE" }),
  settings: (signal?: AbortSignal) => api<{ alertDesktop: boolean }>("/ratio-rules/settings", { signal }),
  updateSettings: (body: { alertDesktop: boolean }) =>
    api<{ alertDesktop: boolean }>("/ratio-rules/settings", { method: "PUT", body }),
};

export const usersApi = {
  list: (signal?: AbortSignal) => api<UserRow[]>("/users", { signal }),
  create: (body: UserInput & { password: string | null; pin: string | null }) => api<UserRow>("/users", { method: "POST", body }),
  update: (id: string, body: UserInput & { active: boolean }) => api<UserRow>(`/users/${id}`, { method: "PUT", body }),
  resetPin: (id: string, pin: string) => api<void>(`/users/${id}/pin`, { method: "PUT", body: { pin } }),
  resetPassword: (id: string, password: string) => api<void>(`/users/${id}/password`, { method: "PUT", body: { password } }),
  resetMfa: (id: string) => api<void>(`/auth/mfa/${id}`, { method: "DELETE" }),
};

export const devicesApi = {
  list: (signal?: AbortSignal) => api<Device[]>("/devices", { signal }),
  create: (name: string) => api<PairingCode>("/devices", { method: "POST", body: { name } }),
  renewCode: (id: string) => api<PairingCode>(`/devices/${id}/pairing-code`, { method: "POST" }),
  revoke: (id: string) => api<void>(`/devices/${id}`, { method: "DELETE" }),
};
