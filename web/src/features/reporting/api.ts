/** Statistiques (miroir des DTO serveur, paquet reporting). */
import { api, apiBlob, saveBlob } from "@/lib/api";

export type Indicators = {
  children: number;
  realizedMinutes: number;
  plannedMinutes: number;
  capacityMinutes: number;
  /** Pour mille. */
  occupancy: number;
  realization: number;
};
export type Report = {
  label: string;
  from: string;
  to: string;
  structureName: string;
  capacity: number;
  openDays: number;
  enrolled: number;
  total: Indicators;
  sections: { sectionId: string; name: string; capacity: number; indicators: Indicators }[];
  months: { month: string; openDays: number; indicators: Indicators }[];
  absences: { reason: string; deductible: boolean; days: number }[];
  funders: { name: string; category: string; children: number; amount: number }[];
  money: { invoices: number; invoiced: number; tgc: number; aids: number; families: number; credited: number; remaining: number };
};

/** « 2026-10 » (mois) ou « 2026 » (année civile) → paramètres de la requête. */
export function periodQuery(period: string): string {
  const [year, month] = period.split("-");
  return month ? `year=${year}&month=${Number(month)}` : `year=${year}`;
}

export const reportingKeys = { report: (period: string) => ["reporting", period] as const };

export const reportingApi = {
  report: (period: string, signal?: AbortSignal) => api<Report>(`/reporting?${periodQuery(period)}`, { signal }),
  download: async (period: string, format: "pdf" | "xlsx") => {
    const { blob, fileName } = await apiBlob(`/reporting/${format}?${periodQuery(period)}`);
    saveBlob(blob, fileName ?? `bilan-activite.${format}`);
  },
};

/** 1 234 567 minutes → « 20 576 h ». */
export function formatHours(minutes: number): string {
  return `${Math.round(minutes / 60).toLocaleString("fr-FR")} h`;
}

/** 712 ‰ → « 71,2 % ». */
export function formatPerMille(value: number): string {
  return `${(value / 10).toLocaleString("fr-FR", { minimumFractionDigits: 1, maximumFractionDigits: 1 })} %`;
}
