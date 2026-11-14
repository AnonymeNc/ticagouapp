import { useEffect, useRef } from "react";
import { ChevronDown, LogOut, Search } from "lucide-react";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { useAuth, useCurrentUser } from "@/features/auth/AuthProvider";
import { roleLabels } from "@/features/auth/types";
import { useStructure } from "@/features/structure/useStructure";
import { useNow } from "@/hooks/useNow";
import { formatTopbarStamp } from "@/lib/datetime";
import { HelpButton } from "@/features/help/HelpButton";
import { initials } from "@/lib/initials";

export function Topbar() {
  const now = useNow();
  const user = useCurrentUser();
  const { logout } = useAuth();
  const structure = useStructure();
  const searchRef = useRef<HTMLInputElement>(null);
  const userName = `${user.firstName} ${user.lastName}`;

  // Ctrl K : recherche globale
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        searchRef.current?.focus();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, []);

  return (
    <header className="flex min-h-topbar flex-wrap items-center gap-x-4 gap-y-3 border-b border-border-subtle bg-card px-4 pt-[max(0.625rem,env(safe-area-inset-top))] pb-2.5 md:px-6">
      {/* Téléphone : marque et crèche ; la recherche, l’heure et la déconnexion passent dans « Plus » (maquette B1). */}
      <div className="flex min-w-0 flex-1 flex-col md:hidden">
        <span className="font-display text-title-2 leading-6 text-lagon-700">Ti’Cagou</span>
        <span className="truncate text-small text-muted-foreground">{structure.data?.name ?? "…"}</span>
      </div>
      {/* Sélecteur de structure : une seule structure tant que le multi-structures n'a pas d'écran */}
      <Button variant="outline" className="hidden border-border font-semibold md:inline-flex">
        {structure.data ? [structure.data.name, structure.data.city].filter(Boolean).join(" · ") : "…"}
        <ChevronDown aria-hidden />
      </Button>

      <label className="hidden h-control max-w-[480px] flex-[1_1_280px] items-center md:flex gap-2 rounded-md border border-input bg-card px-3 text-muted-foreground focus-within:outline-2 focus-within:outline-ring">
        <Search aria-hidden className="size-4" />
        <span className="sr-only">Recherche globale</span>
        <input
          ref={searchRef}
          type="search"
          placeholder="Rechercher un enfant, une famille, une facture"
          className="min-w-0 flex-1 bg-transparent text-foreground outline-none placeholder:text-muted-foreground"
        />
        <kbd className="hidden rounded-sm xl:inline border border-border bg-muted px-1.5 py-0.5 font-sans text-caption text-muted-foreground">
          Ctrl K
        </kbd>
      </label>

      <div className="ml-auto flex items-center gap-2 md:gap-4">
        <span className="hidden font-medium text-muted-foreground tabular-nums lg:inline">{formatTopbarStamp(now)}</span>
        <HelpButton />
        <div className="flex items-center gap-2.5">
          <Avatar className="size-8">
            <AvatarFallback className="bg-lagon-100 text-small font-semibold text-lagon-700">
              {initials(userName)}
            </AvatarFallback>
          </Avatar>
          <span className="hidden flex-col leading-[18px] xl:flex">
            <span className="text-label">{userName}</span>
            <span className="text-small text-muted-foreground">{roleLabels[user.role]}</span>
          </span>
          <Button variant="ghost" size="icon-sm" className="hidden md:inline-flex" aria-label="Se déconnecter" title="Se déconnecter" onClick={() => void logout()}>
            <LogOut aria-hidden />
          </Button>
        </div>
      </div>
    </header>
  );
}
