import { useEffect, useRef, useState, type KeyboardEvent } from "react";
import { NavLink, Outlet, useNavigate } from "react-router";
import { LogOut, Search } from "lucide-react";
import { Button } from "@/components/ui/button";
import { HealthDot } from "@/components/ui/pill";
import { useAuth, useOperator } from "@/features/auth/AuthProvider";
import { useParc, useSearch } from "@/features/parc/api";
import { formatStamp, formatTime, initials } from "@/lib/format";
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
      <SourcesFoot />
    </nav>
  );
}

/** Pied de la barre latérale : dernières lectures de la supervision et du rapport de l'hôte. */
function SourcesFoot() {
  const { data } = useParc();
  if (!data) {
    return null;
  }
  const { sources } = data;
  const fresh = (at: string | null) => at != null && Date.now() - new Date(at).getTime() < 20 * 60_000;
  const ok = fresh(sources.supervisionAt) && !sources.supervisionError && fresh(sources.hostAt);
  return (
    <div className="mt-auto flex gap-2 px-2 pt-4 text-small leading-[18px] text-muted-foreground">
      <span aria-hidden className={cn("mt-1.5 size-2 shrink-0 rounded-full", ok ? "bg-success-600" : "bg-warning-500")} />
      <span>
        {sources.supervisionAt ? `Supervision lue à ${formatTime(new Date(sources.supervisionAt))}` : "Supervision pas encore lue"}
        <br />
        {sources.hostAt ? `Hôte : rapport à ${formatTime(new Date(sources.hostAt))}` : "Hôte : aucun rapport"}
      </span>
    </div>
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
      <SearchBox />
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

/** Recherche d'une crèche (nom, identifiant, commune, raison sociale) ; Ctrl K y place le curseur. */
function SearchBox() {
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const input = useRef<HTMLInputElement>(null);
  const navigate = useNavigate();
  const results = useSearch(query);
  const searching = query.trim().length >= 2;
  const hits = searching ? (results.data ?? []) : [];

  useEffect(() => {
    const onKey = (e: globalThis.KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        input.current?.focus();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const go = (slug: string) => {
    setQuery("");
    setOpen(false);
    input.current?.blur();
    navigate(`/parc/${slug}`);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setActive((i) => Math.min(i + 1, hits.length - 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setActive((i) => Math.max(i - 1, 0));
    } else if (e.key === "Enter" && hits[active]) {
      e.preventDefault();
      go(hits[active].slug);
    } else if (e.key === "Escape") {
      setQuery("");
      setOpen(false);
    }
  };

  return (
    <div className="relative w-full max-w-[420px]">
      <label className="flex h-9 items-center gap-2 rounded-md border border-border bg-background px-3 text-muted-foreground focus-within:border-lagon-600">
        <Search aria-hidden className="size-4 shrink-0" />
        <input
          ref={input}
          type="search"
          role="combobox"
          aria-label="Rechercher une crèche"
          aria-expanded={open && searching}
          aria-controls="recherche-resultats"
          aria-autocomplete="list"
          aria-activedescendant={open && hits[active] ? `recherche-${hits[active].slug}` : undefined}
          placeholder="Rechercher une crèche, un client"
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            setActive(0);
            setOpen(true);
          }}
          onFocus={() => setOpen(true)}
          onBlur={() => window.setTimeout(() => setOpen(false), 150)}
          onKeyDown={onKeyDown}
          className="min-w-0 flex-1 bg-transparent text-foreground outline-none placeholder:text-muted-foreground"
        />
        <kbd className="rounded border border-border px-1.5 text-caption">Ctrl K</kbd>
      </label>
      {open && searching && (
        <ul
          id="recherche-resultats"
          role="listbox"
          aria-label="Crèches trouvées"
          className="absolute top-full right-0 left-0 z-20 m-0 mt-1 list-none rounded-md border border-border bg-card p-1 shadow-2"
        >
          {hits.length === 0 ? (
            <li className="px-3 py-2 text-muted-foreground">{results.isFetching ? "Recherche…" : "Aucune crèche trouvée."}</li>
          ) : (
            hits.map((hit, i) => (
              <li
                key={hit.slug}
                id={`recherche-${hit.slug}`}
                role="option"
                aria-selected={i === active}
                onMouseDown={(e) => {
                  e.preventDefault();
                  go(hit.slug);
                }}
                className={cn("flex cursor-pointer items-center gap-2 rounded px-3 py-2", i === active && "bg-lagon-50")}
              >
                <HealthDot level={hit.level} />
                <span className="font-semibold">{hit.name}</span>
                <span className="truncate text-small text-muted-foreground">
                  {[hit.city, hit.clientName !== hit.name ? hit.clientName : null, hit.slug].filter(Boolean).join(" · ")}
                </span>
              </li>
            ))
          )}
        </ul>
      )}
    </div>
  );
}
