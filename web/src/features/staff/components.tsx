import type { ReactNode } from "react";
import { NavLink } from "react-router";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { TonePill, type Tone } from "@/features/enrollment/components";
import { isoToDateFr } from "@/lib/datetime";
import { formatMinutes } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { Alert, QualificationState } from "./api";

/** La direction et la comptabilité voient toute l'équipe ; les autres, leur planning et leurs heures. */
export function useSeesTeam() {
  const user = useCurrentUser();
  return user.role === "DIRECTION" || user.role === "COMPTABILITE";
}

/** En-tête du module Personnel : titre et onglets (maquette Personnel). */
export function StaffHeader({ subtitle, actions }: { subtitle?: ReactNode; actions?: ReactNode }) {
  const team = useSeesTeam();
  const tab = ({ isActive }: { isActive: boolean }) =>
    cn("inline-flex h-control items-center rounded-full px-3.5 text-label", isActive ? "bg-card text-lagon-700 shadow-1" : "text-foreground");
  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-4 print:hidden">
        <div>
          <h1 className="m-0 font-display text-display">Personnel</h1>
          {subtitle && <p className="mt-1 mb-0 text-muted-foreground">{subtitle}</p>}
        </div>
        {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
      </div>
      <nav aria-label="Vues du personnel" className="flex max-w-full gap-1 self-start overflow-x-auto rounded-[14px] bg-muted p-1 [&>*]:shrink-0 [&>*]:whitespace-nowrap print:hidden">
        {team && <NavLink to="/personnel" end className={tab}>Équipe</NavLink>}
        <NavLink to="/personnel/planning" className={tab}>{team ? "Planning de la semaine" : "Mon planning"}</NavLink>
        <NavLink to="/personnel/heures" className={tab}>{team ? "Feuilles d’heures" : "Mes heures"}</NavLink>
        <NavLink to="/personnel/mon-espace" className={tab}>{team ? "Demandes et cahier" : "Mon espace"}</NavLink>
      </nav>
    </>
  );
}

const stateTones: Record<QualificationState, Tone> = { SANS_LIMITE: "neutral", VALIDE: "success", A_RENOUVELER: "warning", EXPIREE: "danger" };

export function QualificationPill({ state, validUntil }: { state: QualificationState; validUntil: string | null }) {
  const label = state === "SANS_LIMITE" ? "Sans limite"
    : state === "VALIDE" ? "Valide"
    : state === "EXPIREE" ? `Expiré le ${isoToDateFr(validUntil!)}`
    : `Expire le ${isoToDateFr(validUntil!)}`;
  return <TonePill tone={stateTones[state]}>{label}</TonePill>;
}

/** « SST expiré le 02/10/2026 », « Fin de contrat le 31/12/2026 » */
export function alertText(a: Alert): string {
  if (a.kind === "CONTRAT") return `Fin de contrat le ${isoToDateFr(a.date)}`;
  const short = a.label.match(/\(([^)]+)\)\s*$/)?.[1] ?? a.label;
  return `${short} ${a.state === "EXPIREE" ? "expiré" : "expire"} le ${isoToDateFr(a.date)}`;
}

/** 2340 → « 39 h » ; 1872 → « 31 h 12 » ; nul → « à l’heure » */
export function weekly(minutes: number | null): string {
  if (minutes === null) return "à l’heure";
  return minutes % 60 === 0 ? `${minutes / 60} h` : formatMinutes(minutes);
}

export const initials = (firstName: string, lastName: string) => `${firstName.charAt(0)}${lastName.charAt(0)}`.toUpperCase();
