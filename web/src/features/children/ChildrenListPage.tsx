import { useDeferredValue, useMemo, useState } from "react";
import { Link } from "react-router";
import { useQuery } from "@tanstack/react-query";
import { ChevronRight, Plus, Search } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { useSections } from "@/features/structure/useSections";
import { ageInMonths, formatAge, todayIso } from "@/lib/age";
import { errorMessage } from "@/lib/api";
import { cn } from "@/lib/utils";
import { childKeys, childrenApi } from "./api";
import { ChildAvatar, HealthBadges, SectionBadge } from "./components";

/**
 * Liste des enfants (maquette Enfants › Liste). Écarts : colonnes Jours d'accueil, Contrat et Solde famille,
 * filtres par jour et « contrat se terminant » et export de la liste arriveront avec les contrats et la facturation.
 */
export function ChildrenListPage() {
  const user = useCurrentUser();
  const canEdit = user.role === "DIRECTION";
  const canOpen = user.role === "DIRECTION" || user.role === "EDUCATRICE";
  const sections = useSections();
  const [q, setQ] = useState("");
  const [sectionId, setSectionId] = useState<string | null>(null);
  const [withAllergy, setWithAllergy] = useState(false);
  const [includeExited, setIncludeExited] = useState(false);
  const deferredQ = useDeferredValue(q.trim());

  // La recherche et les filtres sont appliqués localement : une crèche compte rarement plus de 100 enfants.
  const children = useQuery({
    queryKey: childKeys.list({ includeExited }),
    queryFn: ({ signal }) => childrenApi.list({ includeExited }, signal),
  });

  const today = todayIso();
  const normalized = (s: string) => s.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase();
  const filtered = useMemo(
    () =>
      (children.data ?? []).filter(
        (c) =>
          (!deferredQ || normalized(`${c.firstName} ${c.lastName}`).includes(normalized(deferredQ))) &&
          (!sectionId || c.sectionId === sectionId) &&
          (!withAllergy || c.health.allergies.length > 0),
      ),
    [children.data, deferredQ, sectionId, withAllergy],
  );
  const counts = (id: string | null) => (children.data ?? []).filter((c) => !id || c.sectionId === id).length;
  const allergyCount = (children.data ?? []).filter((c) => c.health.allergies.length > 0).length;
  const hasFilters = Boolean(q || sectionId || withAllergy);
  const sectionById = new Map((sections.data ?? []).map((s) => [s.id, s]));

  const reset = () => {
    setQ("");
    setSectionId(null);
    setWithAllergy(false);
  };

  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="m-0 font-display text-display">Enfants</h1>
          <p className="mt-1 mb-0 text-muted-foreground">
            <span className="tabular-nums">{children.data?.length ?? "…"}</span> enfants{" "}
            {includeExited ? "inscrits ou sortis" : "inscrits"}
          </p>
        </div>
        {canEdit && (
          <Button asChild>
            <Link to="/enfants/nouveau">
              <Plus aria-hidden />
              Nouvel enfant
            </Link>
          </Button>
        )}
      </div>

      <section aria-label="Filtres" className="flex flex-wrap items-end gap-x-6 gap-y-4 rounded-lg border border-border-subtle bg-card p-4 shadow-1">
        <div className="flex min-w-60 flex-1 flex-col gap-1.5">
          <label htmlFor="recherche" className="text-label">Rechercher un enfant</label>
          <div className="flex h-control items-center gap-2 rounded-md border border-input bg-card px-3 focus-within:outline-2 focus-within:outline-ring">
            <Search aria-hidden className="size-4 text-muted-foreground" />
            <input
              id="recherche"
              type="search"
              placeholder="Prénom ou nom"
              value={q}
              onChange={(e) => setQ(e.target.value)}
              className="min-w-0 flex-1 bg-transparent outline-none"
            />
          </div>
        </div>
        <fieldset className="m-0 flex flex-col gap-1.5 border-0 p-0">
          <legend className="mb-1.5 p-0 text-label">Section</legend>
          <div className="flex flex-wrap gap-1.5">
            {[{ id: null, name: "Toutes", color: null as string | null }, ...(sections.data ?? [])].map((s) => (
              <button
                key={s.id ?? "all"}
                type="button"
                aria-pressed={sectionId === s.id}
                onClick={() => setSectionId(s.id)}
                className={cn(
                  "inline-flex h-control items-center gap-2 rounded-full border px-3 text-label",
                  sectionId === s.id ? "border-lagon-600 bg-lagon-100 text-lagon-700" : "border-input bg-card",
                )}
              >
                {s.color && <span aria-hidden className="size-2.5 rounded-full" style={{ backgroundColor: s.color }} />}
                {s.name}
                <span className="font-normal text-muted-foreground tabular-nums">{counts(s.id)}</span>
              </button>
            ))}
          </div>
        </fieldset>
        <div className="flex flex-wrap items-center gap-4">
          <label className="inline-flex items-center gap-2">
            <input type="checkbox" checked={withAllergy} onChange={(e) => setWithAllergy(e.target.checked)} className="size-4 accent-lagon-600" />
            Avec allergie <span className="text-muted-foreground tabular-nums">{allergyCount}</span>
          </label>
          <label className="inline-flex items-center gap-2">
            <input type="checkbox" checked={includeExited} onChange={(e) => setIncludeExited(e.target.checked)} className="size-4 accent-lagon-600" />
            Afficher les enfants sortis
          </label>
          {hasFilters && (
            <Button variant="link" size="sm" onClick={reset}>
              Effacer les filtres
            </Button>
          )}
        </div>
      </section>

      <p role="status" className="-mb-4 text-muted-foreground tabular-nums">
        {children.isSuccess && `${filtered.length} enfant${filtered.length > 1 ? "s" : ""} affiché${filtered.length > 1 ? "s" : ""}`}
      </p>

      <div className="overflow-x-auto rounded-lg border border-border-subtle bg-card shadow-1">
        {children.isError ? (
          <p className="m-0 p-6 text-danger-700">{errorMessage(children.error)}</p>
        ) : (
          <table className="w-full border-collapse text-left">
            <caption className="sr-only">Liste des enfants inscrits</caption>
            <thead>
              <tr className="border-b border-border-subtle bg-muted text-label text-muted-foreground">
                <th scope="col" className="px-4 py-2.5">Enfant</th>
                <th scope="col" className="px-4 py-2.5">Âge</th>
                <th scope="col" className="px-4 py-2.5">Section</th>
                <th scope="col" className="px-4 py-2.5">Santé</th>
                <th scope="col" className="px-4 py-2.5"><span className="sr-only">Ouvrir</span></th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((c) => {
                const section = c.sectionId ? sectionById.get(c.sectionId) : undefined;
                const name = (
                  <>
                    <strong className="font-semibold">{c.firstName}</strong> {c.lastName}
                  </>
                );
                return (
                  <tr key={c.id} className="border-b border-border-subtle last:border-0 hover:bg-lagon-50">
                    <td className="px-4 py-2">
                      <div className="flex items-center gap-3">
                        <ChildAvatar child={c} section={section} />
                        {canOpen ? (
                          <Link to={`/enfants/${c.id}`} className="text-foreground no-underline hover:underline">
                            {name}
                          </Link>
                        ) : (
                          <span>{name}</span>
                        )}
                        {c.exitDate && c.exitDate < today && (
                          <span className="rounded-full bg-muted px-2 text-caption text-muted-foreground">Sorti</span>
                        )}
                      </div>
                    </td>
                    <td className="px-4 py-2 whitespace-nowrap tabular-nums">{formatAge(ageInMonths(c.birthDate, today))}</td>
                    <td className="px-4 py-2"><SectionBadge section={section} /></td>
                    <td className="px-4 py-2"><HealthBadges health={c.health} /></td>
                    <td className="px-4 py-2 text-right">
                      {canOpen && (
                        <Link to={`/enfants/${c.id}`} aria-label={`Ouvrir la fiche de ${c.firstName} ${c.lastName}`} className="inline-flex text-muted-foreground">
                          <ChevronRight aria-hidden className="size-5" />
                        </Link>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
        {children.isSuccess && filtered.length === 0 && (
          <div className="flex flex-col items-center gap-2 p-10 text-center">
            <p className="m-0 text-title-3">
              {children.data.length === 0 ? "Aucun enfant inscrit pour l’instant" : "Aucun enfant ne correspond à ces filtres"}
            </p>
            {hasFilters && (
              <>
                <p className="m-0 text-muted-foreground">Retirez un filtre ou modifiez la recherche.</p>
                <Button variant="outline" onClick={reset}>Effacer les filtres</Button>
              </>
            )}
          </div>
        )}
      </div>
    </>
  );
}
