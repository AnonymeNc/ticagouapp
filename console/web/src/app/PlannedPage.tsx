import type { NavItem } from "./navigation";

/** Écran prévu par le plan, pas encore livré : on dit lequel et quand, sans données inventées. */
export function PlannedPage({ item }: { item: NavItem }) {
  return (
    <>
      <div>
        <h1 className="m-0 font-display text-display">{item.label}</h1>
        <p className="mt-1 text-muted-foreground">{item.description}</p>
      </div>
      <section className="rounded-lg border border-border-subtle bg-card p-6 text-muted-foreground shadow-1">
        Cet écran n’est pas encore livré. Sa maquette est dans
        docs/design/Ti’Cagou — Console éditeur.html.
      </section>
    </>
  );
}
