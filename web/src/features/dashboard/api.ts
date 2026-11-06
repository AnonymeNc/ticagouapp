/** Tableau de bord : types et appels (miroir des DTO serveur, en attendant le client généré). */
import { api } from "@/lib/api";

export type ReportedAbsence = {
  childId: string;
  name: string;
  sectionId: string | null;
  reason: string | null;
  until: string;
  notifiedOn: string | null;
  note: string | null;
};

export type PlannedDeparture = { childId: string; name: string; sectionId: string | null; leavesAt: string; present: boolean };

export type StaffArrival = { userId: string; name: string; sectionId: string | null; startsAt: string; countsInRatio: boolean };

export type Today = {
  day: string;
  closesAt: string | null;
  absences: ReportedAbsence[];
  departures: PlannedDeparture[];
  staffArrivals: StaffArrival[];
};

/** Indicateurs du mois. Taux et évolutions en pour mille ; montants en XPF. */
export type Kpis = {
  month: string;
  fillRate: number;
  reservedMinutes: number;
  enrolled: number;
  capacity: number;
  /** Dernier mois facturé (« 2026-10 »), nul avant la première facture. */
  invoicedMonth: string | null;
  invoiced: number;
  /** Nul quand le mois précédent n'a pas été facturé. */
  invoicedChange: number | null;
  invoices: number;
  billableFamilies: number;
  unpaid: number;
  unpaidCount: number;
  oldestUnpaid: string | null;
  waitingList: number;
  waitingBySection: Record<string, number>;
  newRequests: number;
};

export const dashboardKeys = {
  today: (day: string) => ["dashboard", "today", day] as const,
  kpis: ["dashboard", "kpis"] as const,
};

export const dashboardApi = {
  today: (signal?: AbortSignal) => api<Today>("/dashboard/today", { signal }),
  kpis: (signal?: AbortSignal) => api<Kpis>("/dashboard/kpis", { signal }),
};
