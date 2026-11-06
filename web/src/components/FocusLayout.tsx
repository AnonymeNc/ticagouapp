import type { ReactNode } from "react";

type Props = {
  children: ReactNode;
  /** Pied de page discret (changer de serveur…). */
  footer?: ReactNode;
};

/** Écran sans barre latérale, carte centrée : connexion, premier lancement, serveur injoignable. */
export function FocusLayout({ children, footer }: Props) {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-6 bg-background px-4 py-10">
      <span className="font-display text-title-1 text-lagon-700">Ti’Cagou</span>
      <main className="w-full max-w-[560px] rounded-lg border border-border-subtle bg-card p-8 shadow-2">
        {children}
      </main>
      {footer && <div className="text-small text-muted-foreground">{footer}</div>}
    </div>
  );
}

export function Alert({ children }: { children: ReactNode }) {
  return (
    <div role="alert" className="rounded-md bg-danger-100 px-4 py-3 text-danger-700">
      {children}
    </div>
  );
}
