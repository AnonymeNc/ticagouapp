import { NavLink } from "react-router";
import { Menu } from "lucide-react";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { useRubriques } from "@/features/structure/useRubriques";
import { cn } from "@/lib/utils";
import { shortLabels, splitNavigation } from "../navigation";

const tabClass = ({ isActive }: { isActive: boolean }) =>
  cn(
    "relative flex min-h-14 flex-1 flex-col items-center justify-center gap-0.5 text-caption font-normal text-muted-foreground",
    isActive && "font-semibold text-lagon-600 before:absolute before:top-0 before:h-[3px] before:w-7 before:rounded-b-sm before:bg-lagon-600",
  );

/** Téléphone (moins de 768 px) : barre basse à cinq entrées, la dernière ouvre « Plus » (toutes les autres rubriques). */
export function BottomNav() {
  const { role } = useCurrentUser();
  const { tabs } = splitNavigation(role, useRubriques().shown);
  return (
    <nav
      aria-label="Navigation rapide"
      className="fixed inset-x-0 bottom-0 z-40 flex border-t border-border-subtle bg-card pb-[env(safe-area-inset-bottom)] md:hidden print:hidden"
    >
      {tabs.map(({ path, label, icon: Icon }) => (
        <NavLink key={path} to={path} end={path === "/"} className={tabClass}>
          <Icon aria-hidden className="size-5" strokeWidth={1.75} />
          {shortLabels[path] ?? label}
        </NavLink>
      ))}
      <NavLink to="/plus" className={tabClass}>
        <Menu aria-hidden className="size-5" strokeWidth={1.75} />
        Plus
      </NavLink>
    </nav>
  );
}
