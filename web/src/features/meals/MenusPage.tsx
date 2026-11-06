import { useEffect, useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronLeft, ChevronRight, Copy, Printer, Soup, TriangleAlert } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { Button } from "@/components/ui/button";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { TonePill } from "@/features/enrollment/components";
import { todayIso } from "@/lib/age";
import { errorMessage } from "@/lib/api";
import { formatDate, formatTime, isoToDateFr } from "@/lib/datetime";
import { addDays, DAY_NAMES } from "@/lib/format";
import { cn } from "@/lib/utils";
import { COURSES, courseLabels, mealKeys, mealsApi, mondayOf, TAGS, type Course, type MenuItem, type MenuWeek } from "./api";
import { MealsHeader } from "./components";

type Cell = { label: string; tags: string[] };
const key = (day: number, course: Course, dietId: string | null) => `${day}/${course}/${dietId ?? ""}`;

function toCells(week: MenuWeek): Record<string, Cell> {
  return Object.fromEntries(week.items.map((i) => [key(i.dayOfWeek, i.course, i.dietId), { label: i.label, tags: i.tags }]));
}

/** « Semaine du 2 au 6 novembre 2026 » */
function weekTitle(week: MenuWeek) {
  const from = new Date(`${week.weekStart}T12:00:00+11:00`);
  const to = new Date(`${week.weekEnd}T12:00:00+11:00`);
  const month = (d: Date) => d.toLocaleDateString("fr-FR", { month: "long", timeZone: "Pacific/Noumea" });
  const sameMonth = month(from) === month(to);
  return `Semaine du ${from.getDate()}${sameMonth ? "" : ` ${month(from)}`} au ${to.getDate()} ${month(to)} ${to.getFullYear()}`;
}

/**
 * Menus de la semaine (maquette « Planning des menus ») : une ligne par plat, une ligne de variante par régime suivi
 * par un enfant inscrit, étiquettes des allergènes. Les variantes manquantes sont signalées avant la fiche cuisine.
 */
export function MenusPage() {
  const user = useCurrentUser();
  const canEdit = user.role === "DIRECTION" || user.role === "CUISINE";
  const [monday, setMonday] = useState(mondayOf(todayIso()));
  const queryClient = useQueryClient();
  const week = useQuery({ queryKey: mealKeys.menus(monday), queryFn: ({ signal }) => mealsApi.week(monday, signal) });
  const [cells, setCells] = useState<Record<string, Cell>>({});
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const [dirty, setDirty] = useState(false);

  useEffect(() => {
    if (week.data) {
      setCells(toCells(week.data));
      setDirty(false);
    }
  }, [week.data]);

  const data = week.data;
  const days = data?.openDays ?? [1, 2, 3, 4, 5];

  const setCell = (k: string, patch: Partial<Cell>) => {
    setDirty(true);
    setSaved(null);
    setCells((current) => ({ ...current, [k]: { label: current[k]?.label ?? "", tags: current[k]?.tags ?? [], ...patch } }));
  };

  /** Statut d'une variante, recalculé à la saisie. */
  const variantStatus = (day: number, dietId: string, excludes: string[]) => {
    if (cells[key(day, "VARIANTE", dietId)]?.label.trim()) return "VARIANTE";
    const plat = cells[key(day, "PLAT", null)];
    if (!plat?.label.trim()) return "SANS_MENU";
    return plat.tags.some((t) => excludes.includes(t)) ? "MANQUANTE" : "CONVIENT";
  };

  const missing = useMemo(
    () => (data?.diets ?? []).flatMap((d) => days.filter((day) => variantStatus(day, d.dietId, d.excludes) === "MANQUANTE").map((day) => ({ d, day }))),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [data, cells],
  );

  const save = async (status: MenuWeek["status"]) => {
    setError(null);
    const items: MenuItem[] = Object.entries(cells)
      .filter(([, c]) => c.label.trim())
      .map(([k, c]) => {
        const [day, course, dietId] = k.split("/");
        return { dayOfWeek: Number(day), course: course as Course, dietId: dietId || null, label: c.label.trim(), tags: c.tags };
      })
      .filter((i) => days.includes(i.dayOfWeek));
    try {
      const next = await mealsApi.saveWeek(monday, { status, items });
      queryClient.setQueryData(mealKeys.menus(monday), next);
      void queryClient.invalidateQueries({ queryKey: mealKeys.all });
      setSaved(status === "PUBLIE" ? "Semaine publiée : la fiche cuisine reprend ces menus." : "Semaine enregistrée en brouillon.");
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  const copyPrevious = async () => {
    if (dirty && !window.confirm("Remplacer les menus saisis par ceux de la semaine précédente ?")) return;
    setError(null);
    try {
      queryClient.setQueryData(mealKeys.menus(monday), await mealsApi.copyPrevious(monday));
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  const rows: { course: Course; dietId: string | null; label: string; hint?: string; excludes?: string[] }[] = [];
  for (const course of COURSES) {
    rows.push({ course, dietId: null, label: courseLabels[course], hint: course === "PLAT" ? "Tous les enfants" : undefined });
    if (course === "PLAT") {
      for (const d of data?.diets ?? []) {
        rows.push({ course: "VARIANTE", dietId: d.dietId, label: d.label, hint: `${d.children.length} enfant${d.children.length > 1 ? "s" : ""}`, excludes: d.excludes });
      }
    }
  }

  return (
    <>
      <MealsHeader
        actions={
          <>
            {canEdit && <Button variant="outline" onClick={() => void copyPrevious()}><Copy aria-hidden />Copier la semaine précédente</Button>}
            <Button variant="outline" onClick={() => window.print()}><Printer aria-hidden />Imprimer le menu</Button>
            {canEdit && <Button variant="outline" onClick={() => void save("BROUILLON")}>Enregistrer la semaine</Button>}
            {canEdit && <Button onClick={() => void save("PUBLIE")}>Publier</Button>}
          </>
        }
      />
      <div className="flex flex-wrap items-center gap-3">
        <Button variant="outline" size="icon" aria-label="Semaine précédente" className="print:hidden" onClick={() => setMonday(addDays(monday, -7))}><ChevronLeft aria-hidden /></Button>
        <div>
          <h2 className="m-0 text-title-2">{data ? weekTitle(data) : "Menus de la semaine"}</h2>
          {data && (
            <p className="m-0 text-small text-muted-foreground">
              Semaine {data.isoWeek} · {isoToDateFr(data.weekStart)} – {isoToDateFr(data.weekEnd)}
              {data.updatedAt && <> · modifié le {formatDate(new Date(data.updatedAt))} à {formatTime(new Date(data.updatedAt))} par {data.updatedBy}</>}
            </p>
          )}
        </div>
        <Button variant="outline" size="icon" aria-label="Semaine suivante" className="print:hidden" onClick={() => setMonday(addDays(monday, 7))}><ChevronRight aria-hidden /></Button>
        {data && <TonePill tone={data.status === "PUBLIE" ? "success" : "info"}>{data.status === "PUBLIE" ? "Publié" : data.saved ? "Brouillon" : "Non saisi"}</TonePill>}
      </div>
      {error && <Alert>{error}</Alert>}
      {saved && <p role="status" className="m-0 rounded-md bg-success-100 px-3 py-2 text-success-700 print:hidden">{saved}</p>}
      {missing.length > 0 && (
        <div role="alert" className="flex flex-col gap-1 rounded-lg border border-warning-500 bg-warning-100 px-4 py-3 text-warning-700 print:hidden">
          {missing.map(({ d, day }) => {
            const plat = cells[key(day, "PLAT", null)];
            const conflicts = plat.tags.filter((t) => d.excludes.includes(t));
            return (
              <p key={`${d.dietId}-${day}`} className="m-0 flex items-start gap-2">
                <TriangleAlert aria-hidden className="mt-0.5 size-4 shrink-0" />
                <span>
                  {DAY_NAMES[day - 1]} : le plat contient {conflicts.join(", ")} et {d.children.length} enfant{d.children.length > 1 ? "s" : ""} suit
                  le régime « {d.label} » ({d.children.join(", ")}). Saisissez la variante pour qu’elle figure sur la fiche cuisine.
                </span>
              </p>
            );
          })}
        </div>
      )}
      {week.isError && <Alert>{errorMessage(week.error)}</Alert>}

      <div className="overflow-x-auto rounded-lg border border-border-subtle bg-card shadow-1 print:overflow-visible print:border-0 print:shadow-none">
        <table className="w-full min-w-[960px] border-collapse text-left print:min-w-0">
          <caption className="sr-only">{data ? `Menus de la ${weekTitle(data).toLowerCase()}` : "Menus de la semaine"}</caption>
          <thead>
            <tr className="border-b border-border-subtle">
              <th scope="col" className="w-40 p-3 text-label text-muted-foreground">Repas</th>
              {days.map((day) => {
                const date = addDays(monday, day - 1);
                return (
                  <th key={day} scope="col" className={cn("p-3 text-label", date === todayIso() && "bg-lagon-100 text-lagon-700")}>
                    {DAY_NAMES[day - 1]} <span className="font-normal text-muted-foreground">{isoToDateFr(date).slice(0, 5)}</span>
                  </th>
                );
              })}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={`${row.course}-${row.dietId}`} className={cn("border-b border-border-subtle align-top last:border-0", row.dietId && "bg-muted/40")}>
                <th scope="row" className="p-3 text-left">
                  <span className="flex items-center gap-1.5">
                    {row.dietId && <Soup aria-hidden className="size-4 text-info-700" />}
                    {row.label}
                  </span>
                  {row.hint && <span className="block text-small font-normal text-muted-foreground">{row.hint}</span>}
                </th>
                {days.map((day) => {
                  const k = key(day, row.course, row.dietId);
                  const cell = cells[k];
                  const status = row.dietId ? variantStatus(day, row.dietId, row.excludes ?? []) : null;
                  const name = `${row.label} — ${DAY_NAMES[day - 1].toLowerCase()} ${isoToDateFr(addDays(monday, day - 1)).slice(0, 5)}`;
                  return (
                    <td key={day} className="p-2">
                      {canEdit ? (
                        <textarea
                          aria-label={name}
                          rows={2}
                          maxLength={200}
                          value={cell?.label ?? ""}
                          placeholder={status === "CONVIENT" ? "Le plat du jour convient" : undefined}
                          onChange={(e) => setCell(k, { label: e.target.value })}
                          className={cn(
                            "w-full resize-none rounded-md border bg-card px-2 py-1 text-body print:border-0 print:p-0",
                            status === "MANQUANTE" ? "border-danger-600" : "border-input",
                          )}
                        />
                      ) : (
                        <p className="m-0">{cell?.label || (status === "CONVIENT" ? "Le plat du jour convient" : "—")}</p>
                      )}
                      {status === "MANQUANTE" && <span className="block text-small font-semibold text-danger-700">Variante manquante</span>}
                      {!row.dietId && canEdit && <TagPicker label={name} tags={cell?.tags ?? []} onChange={(tags) => setCell(k, { tags })} />}
                      {cell && cell.tags.length > 0 && <span className="block text-small text-danger-700">Contient : {cell.tags.join(", ")}</span>}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="m-0 text-small text-muted-foreground">
        Les variantes ne s’affichent que si au moins un enfant inscrit suit ce régime (Paramètres › Repas et régimes). Les
        textures des bébés (mixé, haché) se règlent dans la fiche de chaque enfant.
      </p>
    </>
  );
}

/** Choix des étiquettes d'un plat (allergènes réglementaires, porc, viande). */
function TagPicker({ label, tags, onChange }: { label: string; tags: string[]; onChange: (tags: string[]) => void }) {
  return (
    <details className="mt-1 print:hidden">
      <summary className="cursor-pointer text-small text-lagon-700" aria-label={`Allergènes : ${label}`}>
        Allergènes{tags.length > 0 ? ` (${tags.length})` : ""}
      </summary>
      <div className="mt-1 grid grid-cols-2 gap-x-2">
        {TAGS.map((t) => (
          <label key={t} className="flex items-center gap-1 text-small">
            <input type="checkbox" checked={tags.includes(t)} onChange={(e) => onChange(e.target.checked ? [...tags, t] : tags.filter((x) => x !== t))} />
            {t}
          </label>
        ))}
      </div>
    </details>
  );
}
