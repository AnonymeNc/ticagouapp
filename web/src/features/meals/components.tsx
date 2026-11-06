import type { ReactNode } from "react";
import { NavLink } from "react-router";
import { HeartPulse, ShieldAlert, Soup } from "lucide-react";
import { cn } from "@/lib/utils";

/** En-tête commun du module Repas : titre et onglets (maquette Repas). */
export function MealsHeader({ subtitle, actions }: { subtitle?: ReactNode; actions?: ReactNode }) {
  const tab = ({ isActive }: { isActive: boolean }) =>
    cn("inline-flex h-control items-center rounded-full px-3.5 text-label", isActive ? "bg-card text-lagon-700 shadow-1" : "text-foreground");
  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-4 print:hidden">
        <div>
          <p className="m-0 text-small text-muted-foreground">Restauration</p>
          <h1 className="m-0 font-display text-display">Repas</h1>
          {subtitle && <p className="mt-1 mb-0 text-muted-foreground">{subtitle}</p>}
        </div>
        {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
      </div>
      <nav aria-label="Vues des repas" className="flex gap-1 self-start rounded-[14px] bg-muted p-1 print:hidden">
        <NavLink to="/repas" end className={tab}>Aujourd’hui en cuisine</NavLink>
        <NavLink to="/repas/menus" className={tab}>Menus de la semaine</NavLink>
        <NavLink to="/repas/recapitulatif" className={tab}>Récapitulatif trimestriel</NavLink>
      </nav>
    </>
  );
}

/** Pastilles santé d'un enfant (allergies, PAI, régime), visibles partout où il apparaît. */
export function NeedsBadges({ allergies, pai, diet }: { allergies: string[]; pai: boolean; diet: string | null }) {
  return (
    <span className="flex flex-wrap gap-1">
      {allergies.length > 0 && (
        <span className="inline-flex items-center gap-1 rounded-sm bg-danger-100 px-2 py-0.5 text-small font-semibold text-danger-700 print:border print:border-danger-700">
          <ShieldAlert aria-hidden className="size-3.5" />
          Allergie : {allergies.join(", ").toLowerCase()}
        </span>
      )}
      {pai && (
        <span className="inline-flex items-center gap-1 rounded-sm bg-warning-100 px-2 py-0.5 text-small font-semibold text-warning-700 print:border print:border-warning-700">
          <HeartPulse aria-hidden className="size-3.5" />
          PAI
        </span>
      )}
      {diet && (
        <span className="inline-flex items-center gap-1 rounded-sm bg-info-100 px-2 py-0.5 text-small font-semibold text-info-700 print:border print:border-info-700">
          <Soup aria-hidden className="size-3.5" />
          Régime : {diet.toLowerCase()}
        </span>
      )}
    </span>
  );
}
