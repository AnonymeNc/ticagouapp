import { useEffect, useState } from "react";
import { NavLink, Outlet } from "react-router";
import { LogOut } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useAuth, useOperator } from "@/features/auth/AuthProvider";
import { formatStamp, initials } from "@/lib/format";
import { cn } from "@/lib/utils";
import { navItems } from "./navigation";

/** Coquille de la console (maquette) : barre latérale, barre supérieure, contenu défilant. */
export function AppShell() {
  return (
    <div className="flex h-screen overflow-hidden bg-background text-foreground">
      <Sidebar />
      <div className="flex min-w-0 flex-1 flex-col">
        <Topbar />
        <div className="min-h-0 flex-1 overflow-y-auto">
          <main className="mx-auto flex w-full max-w-[1360px] flex-col gap-6 p-6">
            <Outlet />
          </main>
        </div>
      </div>
    </div>
  );
}

function Sidebar() {
  return (
    <nav aria-label="Navigation principale" className="flex w-sidebar shrink-0 flex-col border-r border-sidebar-border bg-sidebar px-3 pb-4">
      <div className="flex h-topbar items-baseline gap-2 px-2 pt-4">
        <span className="font-display text-title-1 text-lagon-700">Ti’Cagou</span>
        <span className="text-caption tracking-wider text-terre-600 uppercase">Console</span>
      </div>
      <ul className="mt-2 flex flex-col gap-0.5">
        {navItems.map(({ path, label, icon: Icon }) => (
          <li key={path}>
            <NavLink
              to={path}
              end={path === "/"}
              className={({ isActive }) =>
                cn(
                  "flex h-10 items-center gap-2.5 rounded-md px-3 text-sidebar-foreground transition-colors hover:bg-lagon-50",
                  isActive && "bg-sidebar-accent font-semibold text-sidebar-accent-foreground hover:bg-sidebar-accent",
                )
              }
            >
              <Icon aria-hidden className="size-5" strokeWidth={1.75} />
              {label}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  );
}

function Topbar() {
  const operator = useOperator();
  const { logout } = useAuth();
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 30_000);
    return () => window.clearInterval(timer);
  }, []);
  return (
    <header className="flex min-h-topbar items-center gap-4 border-b border-border-subtle bg-card px-6 py-2.5">
      <div className="ml-auto flex items-center gap-4">
        <span className="font-medium text-muted-foreground tabular-nums">{formatStamp(now)}</span>
        <div className="flex items-center gap-2.5">
          <span className="grid size-8 place-items-center rounded-full bg-lagon-100 text-small font-semibold text-lagon-700">
            {initials(operator.displayName)}
          </span>
          <span className="flex flex-col leading-[18px]">
            <span className="text-label">{operator.displayName}</span>
            <span className="text-small text-muted-foreground">Éditeur</span>
          </span>
          <Button variant="ghost" size="icon-sm" aria-label="Se déconnecter" title="Se déconnecter" onClick={() => void logout()}>
            <LogOut aria-hidden />
          </Button>
        </div>
      </div>
    </header>
  );
}
