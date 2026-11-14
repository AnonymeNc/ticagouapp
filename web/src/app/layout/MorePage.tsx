import { Link } from "react-router";
import { ChevronRight, LogOut } from "lucide-react";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { useAuth, useCurrentUser } from "@/features/auth/AuthProvider";
import { roleLabels } from "@/features/auth/types";
import { ServerStatusChip } from "@/features/health/ServerStatusChip";
import { useRubriques } from "@/features/structure/useRubriques";
import { useStructure } from "@/features/structure/useStructure";
import { initials } from "@/lib/initials";
import { APP_VERSION, isNativeApp } from "@/lib/native/platform";
import { splitNavigation } from "../navigation";

/** « Plus » (téléphone) : les rubriques absentes de la barre basse, le compte et la déconnexion (maquette C1). */
export function MorePage() {
  const user = useCurrentUser();
  const { logout } = useAuth();
  const structure = useStructure();
  const { more } = splitNavigation(user.role, useRubriques().shown);
  const userName = `${user.firstName} ${user.lastName}`;
  return (
    <>
      <h1 className="font-display text-title-1">Plus</h1>
      <section className="flex items-center gap-3 rounded-lg border border-border-subtle bg-card p-4">
        <Avatar className="size-10">
          <AvatarFallback className="bg-lagon-100 font-semibold text-lagon-700">{initials(userName)}</AvatarFallback>
        </Avatar>
        <div className="min-w-0 flex-1">
          <p className="font-semibold">{userName}</p>
          <p className="truncate text-small text-muted-foreground">
            {roleLabels[user.role]}
            {structure.data ? ` · ${structure.data.name}` : ""}
          </p>
        </div>
      </section>
      <nav aria-label="Autres rubriques">
        <ul className="divide-y divide-border-subtle overflow-hidden rounded-lg border border-border-subtle bg-card">
          {more.map(({ path, label, icon: Icon }) => (
            <li key={path}>
              <Link to={path} className="flex min-h-14 items-center gap-3 px-4 hover:bg-lagon-50">
                <Icon aria-hidden className="size-5" strokeWidth={1.75} />
                <span className="flex-1 font-semibold">{label}</span>
                <ChevronRight aria-hidden className="size-4 text-muted-foreground" />
              </Link>
            </li>
          ))}
        </ul>
      </nav>
      <div className="overflow-hidden rounded-lg border border-border-subtle bg-card">
        <button
          type="button"
          onClick={() => void logout()}
          className="flex min-h-14 w-full items-center gap-3 px-4 text-left font-semibold text-danger-700 hover:bg-danger-100"
        >
          <LogOut aria-hidden className="size-5" />
          Se déconnecter
        </button>
      </div>
      <div className="flex flex-col items-center gap-2 text-small text-muted-foreground">
        <ServerStatusChip />
        <span>{isNativeApp() ? `Ti’Cagou Direction ${APP_VERSION}` : "Ti’Cagou"}</span>
      </div>
    </>
  );
}
