import { NavLink } from "react-router";
import { useRubriques } from "@/features/structure/useRubriques";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { ServerStatusChip } from "@/features/health/ServerStatusChip";
import { cn } from "@/lib/utils";
import { navItems } from "../navigation";

export function Sidebar() {
  const { role } = useCurrentUser();
  const rubriques = useRubriques();
  return (
    <nav
      aria-label="Navigation principale"
      className="flex w-sidebar shrink-0 flex-col border-r border-sidebar-border bg-sidebar px-3 pb-4"
    >
      <div className="flex h-topbar items-center px-2">
        <span className="font-display text-title-1 text-lagon-700">Ti’Cagou</span>
      </div>
      <ul className="mt-2 flex flex-col gap-0.5">
        {navItems.filter((item) => (!item.roles || item.roles.includes(role)) && rubriques.shown(item.rubrique)).map(({ path, label, icon: Icon }) => (
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
      <div className="mt-auto pt-6">
        <ServerStatusChip />
      </div>
    </nav>
  );
}
