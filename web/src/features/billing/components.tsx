import type { ReactNode } from "react";
import { NavLink } from "react-router";
import { TonePill, type Tone } from "@/features/enrollment/components";
import { todayIso } from "@/lib/age";
import { cn } from "@/lib/utils";
import { statusLabels, type InvoiceStatus, type RequestStatus } from "./api";

/** En-tête commun du module Facturation : titre et onglets (maquette Facturation). */
export function BillingHeader({ title, subtitle, actions }: { title: string; subtitle?: ReactNode; actions?: ReactNode }) {
  const tab = ({ isActive }: { isActive: boolean }) =>
    cn("inline-flex h-control items-center rounded-full px-3.5 text-label", isActive ? "bg-card text-lagon-700 shadow-1" : "text-foreground");
  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-4 print:hidden">
        <div>
          <p className="m-0 text-small text-muted-foreground">Facturation</p>
          <h1 className="m-0 font-display text-display">{title}</h1>
          {subtitle && <p className="mt-1 mb-0 text-muted-foreground">{subtitle}</p>}
        </div>
        {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
      </div>
      <nav aria-label="Vues de la facturation" className="flex gap-1 self-start rounded-[14px] bg-muted p-1 print:hidden">
        <NavLink to="/facturation" end className={tab}>Facturer le mois</NavLink>
        <NavLink to="/facturation/financeurs" className={tab}>Financeurs</NavLink>
        <NavLink to="/facturation/encaissements" className={tab}>Encaissements et relances</NavLink>
        <NavLink to="/facturation/comptabilite" className={tab}>Export comptable</NavLink>
      </nav>
    </>
  );
}

const invoiceTones: Record<InvoiceStatus, Tone> = {
  EMISE: "info",
  PARTIELLE: "warning",
  PAYEE: "success",
  EN_RETARD: "danger",
  ANNULEE: "neutral",
};

export function InvoiceStatusPill({ status }: { status: InvoiceStatus }) {
  return <TonePill tone={invoiceTones[status]}>{statusLabels[status]}</TonePill>;
}

const requestStates: Record<RequestStatus, { tone: Tone; label: string }> = {
  A_ENVOYER: { tone: "warning", label: "À envoyer" },
  ENVOYEE: { tone: "info", label: "Envoyée" },
  PARTIELLE: { tone: "warning", label: "Réglée en partie" },
  REGLEE: { tone: "success", label: "Réglée" },
};

export function RequestStatusPill({ status }: { status: RequestStatus }) {
  return <TonePill tone={requestStates[status].tone}>{requestStates[status].label}</TonePill>;
}

/** Chiffre clé de la maquette (libellé, valeur, précision). */
export function Kpi({ label, value, hint, tone }: { label: string; value: string; hint?: ReactNode; tone?: "danger" }) {
  return (
    <div className="flex flex-col gap-1 rounded-lg border border-border-subtle bg-card p-4 shadow-1">
      <dt className="text-label text-muted-foreground">{label}</dt>
      <dd className={cn("m-0 text-numeric-lg tabular-nums", tone === "danger" && "text-danger-700")}>{value}</dd>
      {hint && <dd className="m-0 text-small text-muted-foreground">{hint}</dd>}
    </div>
  );
}

/** Mois facturables, du plus récent (le mois écoulé) au plus ancien, « AAAA-MM ». */
export function billableMonths(count = 12, today = todayIso()): string[] {
  const [y, m] = today.split("-").map(Number);
  return Array.from({ length: count + 1 }, (_, i) => {
    const index = y * 12 + (m - 1) - i;
    return `${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, "0")}`;
  });
}

/** Mois écoulé : celui que l'on facture par défaut. */
export const previousMonth = (today = todayIso()) => billableMonths(1, today)[1];
