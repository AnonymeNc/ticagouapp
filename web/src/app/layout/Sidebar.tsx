import { NavLink } from "react-router";
import { useRubriques } from "@/features/structure/useRubriques";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { ServerStatusChip } from "@/features/health/ServerStatusChip";
import { cn } from "@/lib/utils";
import { navItems } from "../navigation";

/**
 * Menu latéral : complet à partir de 1 280 px, rail d’icônes de 768 à 1 279 px (libellé au survol), absent sur
 * téléphone où la barre basse le remplace (maquette « Direction tablette et ordinateur »).
 */
export function Sidebar() {
  const { role } = useCurrentUser();
  const rubriques = useRubriques();
  return (
    <nav
      aria-label="Navigation principale"
      className="hidden w-[72px] shrink-0 flex-col border-r border-sidebar-border bg-sidebar px-3 pb-4 md:flex xl:w-sidebar"
    >
      <div className="flex h-topbar items-center justify-center px-2 xl:justify-start">
        <span className="font-display text-title-1 text-lagon-700">
          T’<span className="hidden xl:inline">Cagou</span>
        </span>
      </div>
      <ul className="mt-2 flex flex-col gap-0.5">
        {navItems.filter((item) => (!item.roles || item.roles.includes(role)) && rubriques.shown(item.rubrique)).map(({ path, label, icon: Icon }) => (
          <li key={path}>
            <NavLink
              to={path}
              end={path === "/"}
              title={label}
              className={({ isActive }) =>
                cn(
                  "flex h-11 items-center justify-center gap-2.5 rounded-md text-sidebar-foreground transition-colors hover:bg-lagon-50 xl:h-10 xl:justify-start xl:px-3",
                  isActive && "bg-sidebar-accent font-semibold text-sidebar-accent-foreground hover:bg-sidebar-accent",
                )
              }
            >
              <Icon aria-hidden className="size-5 shrink-0" strokeWidth={1.75} />
              <span className="sr-only xl:not-sr-only">{label}</span>
            </NavLink>
          </li>
        ))}
      </ul>
      <div className="mt-auto hidden pt-6 xl:block">
        <ServerStatusChip />
      </div>
    </nav>
  );
}
