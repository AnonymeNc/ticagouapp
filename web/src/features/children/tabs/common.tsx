import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

/** Carte d'une rubrique de la fiche. */
export function Panel({ title, actions, children, className }: {
  title?: string;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={cn("flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1", className)}>
      {(title || actions) && (
        <div className="flex flex-wrap items-center justify-between gap-2">
          {title && <h3 className="m-0 text-title-3">{title}</h3>}
          {actions}
        </div>
      )}
      {children}
    </section>
  );
}

/** Liste de définitions « libellé : valeur » ; les valeurs vides s'affichent « — ». */
export function Facts({ items }: { items: [string, ReactNode][] }) {
  return (
    <dl className="m-0 grid grid-cols-[minmax(140px,auto)_1fr] gap-x-4 gap-y-2">
      {items.map(([label, value]) => (
        <div key={label} className="contents">
          <dt className="text-muted-foreground">{label}</dt>
          <dd className="m-0 tabular-nums">{value === null || value === undefined || value === "" ? "—" : value}</dd>
        </div>
      ))}
    </dl>
  );
}

export function FormActions({ pending, onCancel, label = "Enregistrer" }: {
  pending: boolean;
  onCancel: () => void;
  label?: string;
}) {
  return (
    <div className="flex gap-3 pt-2">
      <button
        type="submit"
        disabled={pending}
        className="inline-flex h-control items-center rounded-md bg-primary px-4 text-label text-primary-foreground disabled:opacity-50"
      >
        {pending ? "Enregistrement…" : label}
      </button>
      <button type="button" onClick={onCancel} className="inline-flex h-control items-center rounded-md px-4 text-label">
        Annuler
      </button>
    </div>
  );
}
