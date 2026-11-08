import { Link } from "react-router";
import { buttonVariants } from "@/components/ui/button";
import { useOperator } from "@/features/auth/AuthProvider";
import { navItems } from "@/app/navigation";

/**
 * Tableau de bord (maquette « Console ») : les indicateurs et la liste « À faire aujourd’hui » viennent avec le parc
 * (phase 2) et la facturation (phase 3). En attendant, la feuille de route des écrans.
 */
export function DashboardPage() {
  const operator = useOperator();
  const firstName = operator.displayName.split(/\s+/)[0];
  return (
    <>
      <div>
        <h1 className="m-0 font-display text-display">Bonjour {firstName}</h1>
        <p className="mt-1 text-muted-foreground">
          La console est en place : connexion protégée par code et journal des actions. Les écrans arrivent phase par phase.
        </p>
      </div>
      <section className="rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <h2 className="m-0 mb-3 font-display text-title-2">Feuille de route</h2>
        <ul className="m-0 flex list-none flex-col p-0">
          {navItems.slice(1).map((item) => (
            <li key={item.path} className="flex items-center gap-3 border-b border-border-subtle py-3 last:border-b-0">
              <span className="grid size-9 shrink-0 place-items-center rounded-md bg-lagon-100 text-lagon-700">
                <item.icon aria-hidden className="size-[18px]" strokeWidth={1.75} />
              </span>
              <span className="min-w-0 flex-1">
                <span className="font-semibold">{item.label}</span>
                <span className="block text-small text-muted-foreground">{item.description}</span>
              </span>
              {item.phase <= 1 ? (
                <Link to={item.path} className={buttonVariants({ variant: "outline", size: "sm" })}>
                  Ouvrir
                </Link>
              ) : (
                <span className="rounded-full bg-muted px-2.5 py-0.5 text-small font-semibold text-muted-foreground">Phase {item.phase}</span>
              )}
            </li>
          ))}
        </ul>
      </section>
    </>
  );
}
