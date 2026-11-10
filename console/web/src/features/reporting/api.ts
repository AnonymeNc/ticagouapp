import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";

/** Filtres du reporting : dates AAAA-MM-JJ (affichées JJ/MM/AAAA). Une seule formule (ADR 0021) : pas de filtre par formule. */
export type Filter = { from: string; to: string; clientId: string };

export type Report = {
  filter: { from: string; to: string; clientId: string | null };
  kpis: {
    revenueHt: number;
    subscriptionsHt: number;
    servicesHt: number;
    collected: number;
    tgc: number;
    rates: number[];
    mrr: number;
    departures: number;
    churnTenths: number | null;
  };
  months: { month: string; ht: number; tgc: number; ttc: number; collected: number }[];
  tgc: { rate: number; base: number; tgc: number }[];
  aging: AgingRow[];
  agingTotal: AgingRow;
  mrrByPlan: { key: string; label: string; monthly: number }[];
  upsell: { slug: string; name: string; text: string; monthly: number }[];
  risks: { slug: string; name: string; kind: "CONNEXION" | "USAGE" | "PAIEMENT"; text: string }[];
  profitability: { slug: string; name: string; revenue: number; supportMinutes: number; cost: number; margin: number }[];
  profitMonths: number;
  settings: Settings;
  clients: { id: string; name: string }[];
};

export type AgingRow = { clientId: string | null; client: string; notDue: number; d30: number; d60: number; d90: number; over: number; total: number };
export type Settings = { supportHourlyRate: number; hostingPerCreche: number };

export function query(filter: Filter): string {
  const params = new URLSearchParams({ du: filter.from, au: filter.to });
  if (filter.clientId) {
    params.set("client", filter.clientId);
  }
  return params.toString();
}

export function useReport(filter: Filter) {
  return useQuery({
    queryKey: ["reporting", filter],
    queryFn: ({ signal }) => api<Report>(`/reporting?${query(filter)}`, { signal }),
    placeholderData: keepPreviousData,
  });
}

/** Lien de téléchargement d'un export (même origine, cookie de session). */
export function exportHref(kind: "journal-ventes.csv" | "encaissements.csv" | "reporting.xlsx", filter: Filter): string {
  return `/api/v1/reporting/${kind}?${query(filter)}`;
}

export function useSaveSettings() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (settings: Settings) => api<Settings>("/reporting/reglages", { method: "PUT", body: settings }),
    onSuccess: () => void client.invalidateQueries({ queryKey: ["reporting"] }),
  });
}

/** Périodes proposées, comme la maquette : exercice en cours, 12 derniers mois, trimestre en cours. */
export type Preset = "exercice" | "douze" | "trimestre" | "autre";

export function presetRange(preset: Exclude<Preset, "autre">, today: Date): { from: string; to: string } {
  const iso = (d: Date) => d.toLocaleDateString("en-CA", { timeZone: "Pacific/Noumea" });
  const [y, m] = iso(today).split("-").map(Number);
  const to = iso(today);
  switch (preset) {
    case "exercice":
      return { from: `${y}-01-01`, to };
    case "douze": {
      const start = new Date(Date.UTC(y, m - 12, 1));
      return { from: start.toISOString().slice(0, 10), to };
    }
    case "trimestre": {
      const first = Math.floor((m - 1) / 3) * 3 + 1;
      return { from: `${y}-${String(first).padStart(2, "0")}-01`, to };
    }
  }
}

/** 110 → « 11 % », 25 → « 2,5 % ». */
export function rateLabel(tenths: number): string {
  return `${(tenths / 10).toLocaleString("fr-FR", { maximumFractionDigits: 1 })} %`;
}
