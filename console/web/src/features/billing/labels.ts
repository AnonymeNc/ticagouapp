import type { Tone } from "@/features/parc/labels";
import type { InvoiceState, InvoiceSummary, TgcNature } from "./api";

/** « 179 820 F ». */
export function xpf(amount: number | null | undefined): string {
  if (amount == null) {
    return "—";
  }
  return `${amount.toLocaleString("fr-FR")} F`;
}

/** Dixièmes de pour cent : 110 → « 11 % ». */
export function rate(tenths: number): string {
  return `${(tenths / 10).toLocaleString("fr-FR", { maximumFractionDigits: 1 })} %`;
}

export const natureLabels: Record<TgcNature, string> = { ABONNEMENT: "Abonnement", PRESTATION: "Prestation", MATERIEL: "Matériel" };

export const planLabels = { ESSENTIEL: "Essentiel", GESTION: "Gestion", COMPLET: "Complet" } as const;
export const tierLabels = { MICRO: "Micro", STRUCTURE: "Structure", GRANDE: "Grande" } as const;
export const methodLabels: Record<string, string> = { VIREMENT: "Virement", CHEQUE: "Chèque", ESPECES: "Espèces", AUTRE: "Autre" };

export function statePill(s: Pick<InvoiceSummary, "state" | "daysLate">): { tone: Tone; label: string } {
  const labels: Record<InvoiceState, { tone: Tone; label: string }> = {
    BROUILLON: { tone: "neutral", label: "Brouillon" },
    A_ENVOYER: { tone: "info", label: "À envoyer" },
    ENVOYEE: { tone: "lagon", label: "Envoyée" },
    PARTIELLE: { tone: "lagon", label: "Payée en partie" },
    PAYEE: { tone: "ok", label: "Payée" },
    RETARD: { tone: s.daysLate > 15 ? "bad" : "warn", label: `Retard ${s.daysLate} j` },
    ANNULEE: { tone: "neutral", label: "Annulée par avoir" },
    AVOIR: { tone: "neutral", label: "Avoir" },
  };
  return labels[s.state];
}

/** « 2026-11 » → « nov. ». */
export function monthShort(month: string): string {
  const [y, m] = month.split("-").map(Number);
  return new Intl.DateTimeFormat("fr-FR", { month: "short", timeZone: "UTC" }).format(new Date(Date.UTC(y, m - 1, 1)));
}

/** Aujourd'hui à Nouméa, « AAAA-MM-JJ ». */
export function today(): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Pacific/Noumea" }).format(new Date());
}
