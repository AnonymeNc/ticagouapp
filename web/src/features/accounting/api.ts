/** Export comptable (miroir des DTO serveur, paquet accounting). */
import { api, apiBlob, saveBlob } from "@/lib/api";

export type AccountRole =
  | "PRODUITS_GARDE" | "PRODUITS_INSCRIPTION" | "PRODUITS_SUPPLEMENTS" | "TGC_COLLECTEE" | "CLIENTS_FAMILLES"
  | "CLIENTS_FINANCEURS" | "BANQUE" | "CAISSE" | "CHEQUES" | "CARTE" | "AVOIRS" | "ARRONDIS";

export type Account = {
  id: string;
  role: AccountRole;
  roleLabel: string;
  account: string;
  label: string;
  validFrom: string;
  validTo: string | null;
  version: number;
};
export type AccountInput = Pick<Account, "role" | "account" | "label" | "validFrom" | "validTo">;

export type CsvColumn = "JOURNAL" | "DATE" | "PIECE" | "COMPTE" | "AUXILIAIRE" | "LIBELLE" | "DEBIT" | "CREDIT";
export const csvColumnLabels: Record<CsvColumn, string> = {
  JOURNAL: "Journal", DATE: "Date", PIECE: "Pièce", COMPTE: "Compte", AUXILIAIRE: "Compte auxiliaire",
  LIBELLE: "Libellé", DEBIT: "Débit", CREDIT: "Crédit",
};

export type AccountingSettings = {
  salesJournal: string;
  bankJournal: string;
  cashJournal: string;
  familyPrefix: string;
  funderPrefix: string;
  csvSeparator: ";" | "," | "TAB";
  csvDate: "JJ/MM/AAAA" | "AAAA-MM-JJ" | "AAAAMMJJ";
  csvColumns: CsvColumn[];
  csvHeader: boolean;
  version: number;
};

export type JournalTotal = { journal: string; label: string; pieces: number; debit: number; credit: number };
export type AccountingCheck = { from: string; to: string; journals: JournalTotal[]; debit: number; credit: number; anomalies: string[] };

export const accountingKeys = {
  check: (from: string, to: string) => ["accounting", "check", from, to] as const,
  accounts: ["accounting", "accounts"] as const,
  settings: ["accounting", "settings"] as const,
};

export const accountingApi = {
  check: (from: string, to: string, signal?: AbortSignal) => api<AccountingCheck>(`/accounting/check?from=${from}&to=${to}`, { signal }),
  download: async (from: string, to: string, format: "CSV" | "FEC") => {
    const { blob, fileName } = await apiBlob(`/accounting/export?from=${from}&to=${to}&format=${format}`);
    saveBlob(blob, fileName ?? `ecritures.${format === "CSV" ? "csv" : "txt"}`);
  },
  accounts: (signal?: AbortSignal) => api<Account[]>("/accounting/accounts", { signal }),
  createAccount: (body: AccountInput) => api<Account>("/accounting/accounts", { method: "POST", body }),
  updateAccount: (id: string, version: number, body: AccountInput) =>
    api<Account>(`/accounting/accounts/${id}?version=${version}`, { method: "PUT", body }),
  settings: (signal?: AbortSignal) => api<AccountingSettings>("/accounting/settings", { signal }),
  updateSettings: (body: AccountingSettings) => api<AccountingSettings>("/accounting/settings", { method: "PUT", body }),
};

/** « 2026-10 » → 1er et dernier jour du mois ; « 2026 » → l'année civile. */
export function periodBounds(period: string): { from: string; to: string } {
  if (/^\d{4}$/.test(period)) return { from: `${period}-01-01`, to: `${period}-12-31` };
  const [y, m] = period.split("-").map(Number);
  const last = new Date(Date.UTC(y, m, 0)).getUTCDate();
  return { from: `${period}-01`, to: `${period}-${String(last).padStart(2, "0")}` };
}
