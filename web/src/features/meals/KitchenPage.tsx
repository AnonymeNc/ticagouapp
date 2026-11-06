import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronLeft, ChevronRight, Lock, Maximize2, Minimize2, Printer } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { todayIso } from "@/lib/age";
import { errorMessage } from "@/lib/api";
import { formatDate, formatLongDate, formatTime } from "@/lib/datetime";
import { addDays } from "@/lib/format";
import { cn } from "@/lib/utils";
import {
  COURSES,
  courseLabels,
  mealKeys,
  mealsApi,
  plural,
  type KitchenChild,
  type KitchenDay,
  type Meal,
  type SectionCount,
} from "./api";
import { MealsHeader, NeedsBadges } from "./components";

const hm = (time: string) => time.slice(0, 5);

/**
 * Aujourd'hui en cuisine (maquette « Vue cuisine du jour ») : comptes par section d'après les présences, enfants à
 * adapter, menu du jour. Fiche A4 par l'impression du navigateur ; mode cuisine en plein écran.
 */
export function KitchenPage() {
  const user = useCurrentUser();
  const canEdit = user.role === "DIRECTION" || user.role === "CUISINE";
  const [day, setDay] = useState(todayIso());
  const [fullScreen, setFullScreen] = useState(false);
  const [correcting, setCorrecting] = useState<{ child: KitchenChild; meal: Meal } | null>(null);
  const [noting, setNoting] = useState<KitchenChild | null>(null);
  const queryClient = useQueryClient();
  const view = useQuery({ queryKey: mealKeys.kitchen(day), queryFn: ({ signal }) => mealsApi.kitchen(day, signal) });
  const refresh = () => queryClient.invalidateQueries({ queryKey: mealKeys.all });
  const isToday = day === todayIso();
  const data = view.data;
  const title = `Repas du ${formatLongDate(new Date(`${day}T12:00:00+11:00`)).replace(/^./, (c) => c.toLowerCase()).replace(/ \d{4}$/, "")}`;

  const content = data && (
    <KitchenSheet
      data={data}
      title={title}
      canEdit={canEdit && !data.closedQuarter}
      large={fullScreen}
      onCorrect={(child, meal) => setCorrecting({ child, meal })}
      onNote={setNoting}
    />
  );

  if (fullScreen && data) {
    return (
      <div className="fixed inset-0 z-40 overflow-y-auto bg-background p-8 print:static print:p-0">
        <div className="mx-auto flex max-w-[1500px] flex-col gap-6">
          <div className="flex justify-end gap-2 print:hidden">
            <Button variant="outline" onClick={() => window.print()}><Printer aria-hidden />Imprimer la fiche</Button>
            <Button onClick={() => setFullScreen(false)}><Minimize2 aria-hidden />Quitter le mode cuisine</Button>
          </div>
          {content}
        </div>
      </div>
    );
  }

  return (
    <>
      <MealsHeader
        subtitle={data?.structureName}
        actions={
          <>
            <Button variant="outline" onClick={() => window.print()} disabled={!data}><Printer aria-hidden />Imprimer la fiche</Button>
            <Button variant="outline" onClick={() => setFullScreen(true)} disabled={!data}><Maximize2 aria-hidden />Mode cuisine</Button>
          </>
        }
      />
      <div className="flex items-center gap-2 print:hidden">
        <Button variant="outline" size="icon" aria-label="Jour précédent" onClick={() => setDay(addDays(day, -1))}><ChevronLeft aria-hidden /></Button>
        <span className="min-w-36 text-center tabular-nums">{formatDate(new Date(`${day}T12:00:00+11:00`))}</span>
        <Button variant="outline" size="icon" aria-label="Jour suivant" onClick={() => setDay(addDays(day, 1))}><ChevronRight aria-hidden /></Button>
        {!isToday && <Button variant="ghost" onClick={() => setDay(todayIso())}>Aujourd’hui</Button>}
      </div>
      {view.isError && <Alert>{errorMessage(view.error)}</Alert>}
      {!data && !view.isError && <p className="text-muted-foreground">Chargement…</p>}
      {content}
      {correcting && data && (
        <CorrectionDialog
          day={day}
          child={correcting.child}
          meal={correcting.meal}
          onClose={() => setCorrecting(null)}
          onDone={(next) => {
            queryClient.setQueryData(mealKeys.kitchen(day), next);
            void refresh();
          }}
        />
      )}
      {noting && <NoteDialog day={day} child={noting} onClose={() => setNoting(null)} onDone={refresh} />}
    </>
  );
}

function KitchenSheet({
  data,
  title,
  canEdit,
  large,
  onCorrect,
  onNote,
}: {
  data: KitchenDay;
  title: string;
  canEdit: boolean;
  large: boolean;
  onCorrect: (child: KitchenChild, meal: Meal) => void;
  onNote: (child: KitchenChild) => void;
}) {
  const now = new Date();
  const counted = formatTime(new Date(data.countedAt));
  const plats = data.menu.filter((i) => i.course !== "VARIANTE");
  const variants = data.menu.filter((i) => i.course === "VARIANTE");
  return (
    <div className={cn("flex flex-col gap-6", large && "text-title-3")}>
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <p className="m-0 hidden text-small text-muted-foreground print:block">{data.structureName} · Fiche cuisine</p>
          <h2 className={cn("m-0 font-display", large ? "text-[40px] leading-tight" : "text-title-1")}>{title}</h2>
          <p className="m-0 text-muted-foreground">
            Compté à {counted} d’après les présences · repas à {hm(data.lunchTime)}, goûter à {hm(data.snackTime)}
            <span className="hidden print:inline"> · Imprimée le {formatDate(now)} à {formatTime(now)}</span>
          </p>
        </div>
        {data.closedQuarter && (
          <p className="m-0 flex items-center gap-2 rounded-md bg-muted px-3 py-1.5 text-small print:hidden">
            <Lock aria-hidden className="size-4" />
            Trimestre clos : le comptage ne peut plus être corrigé
          </p>
        )}
      </div>
      {!data.openDay && <Alert>La structure est fermée ce jour-là.</Alert>}

      <section aria-label="Comptes par section" className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4 print:grid-cols-4 print:gap-2">
        {data.sections.map((s) => <CountCard key={s.sectionId} count={s} large={large} />)}
        <CountCard count={data.total} total large={large} />
      </section>

      <section aria-labelledby="a-adapter" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1 print:border-0 print:p-0 print:shadow-none">
        <h2 id="a-adapter" className="m-0 text-title-2">
          À adapter aujourd’hui <span className="font-normal text-muted-foreground">· {plural(data.toAdapt.length, "enfant")} présent{data.toAdapt.length > 1 ? "s" : ""}</span>
        </h2>
        {data.toAdapt.length === 0 && <p className="m-0 text-muted-foreground">Aucune allergie, aucun PAI ni régime parmi les enfants présents.</p>}
        {data.toAdapt.length > 0 && (
          <table className="w-full border-collapse text-left">
            <caption className="sr-only">Enfants à adapter</caption>
            <thead>
              <tr className="border-b border-border-subtle text-label text-muted-foreground">
                <th scope="col" className="py-2">Enfant</th>
                <th scope="col" className="py-2">Allergie, PAI, régime</th>
                <th scope="col" className="py-2">Ce qu’il faut faire</th>
                <th scope="col" className="hidden py-2 print:table-cell">Fait</th>
                {canEdit && <th scope="col" className="py-2 print:hidden"><span className="sr-only">Actions</span></th>}
              </tr>
            </thead>
            <tbody>
              {data.toAdapt.map((c) => (
                <tr key={c.childId} className="border-b border-border-subtle align-top last:border-0">
                  <td className="py-2 pr-3">
                    <strong>{c.firstName} {c.lastName}</strong>
                    <span className="block text-small text-muted-foreground">{c.sectionName}</span>
                  </td>
                  <td className="py-2 pr-3"><NeedsBadges allergies={c.allergies} pai={c.pai} diet={c.diet} /></td>
                  <td className="py-2 pr-3">
                    {c.advice.map((a) => <span key={a} className="block">{a}</span>)}
                    {c.note && <span className="block font-semibold">{c.note}</span>}
                  </td>
                  <td className="hidden py-2 print:table-cell"><span className="inline-block size-4 border border-ink" /></td>
                  {canEdit && (
                    <td className="py-2 text-right print:hidden">
                      <Button variant="ghost" size="sm" onClick={() => onNote(c)}>Consigne</Button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {data.absentWithNeeds.length > 0 && (
          <p className="m-0 text-muted-foreground">
            Absent{data.absentWithNeeds.length > 1 ? "s" : ""} aujourd’hui, rien à préparer :{" "}
            {data.absentWithNeeds
              .map((c) => `${c.firstName} ${c.lastName}${c.diet ? ` (régime ${c.diet.toLowerCase()})` : c.allergies.length ? ` (allergie : ${c.allergies.join(", ").toLowerCase()})` : ""}`)
              .join(", ")}.
          </p>
        )}
      </section>

      <section aria-labelledby="menu-du-jour" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1 print:border-0 print:p-0 print:shadow-none">
        <h2 id="menu-du-jour" className="m-0 text-title-2">Menu du jour</h2>
        {plats.length === 0 ? (
          <p className="m-0 text-muted-foreground">Le menu de ce jour n’est pas saisi (Menus de la semaine).</p>
        ) : (
          <dl className="m-0 grid gap-3 sm:grid-cols-2 print:grid-cols-2">
            {COURSES.filter((course) => plats.some((p) => p.course === course)).map((course) => {
              const item = plats.find((p) => p.course === course)!;
              return (
                <div key={course}>
                  <dt className="text-label text-muted-foreground">{courseLabels[course]}</dt>
                  <dd className="m-0">
                    {item.label}
                    {course === "PLAT" && variants.map((v) => (
                      <span key={v.dietId} className="block text-small">
                        {(v.dietId && data.diets[v.dietId]) ?? "Variante"} : {v.label}
                      </span>
                    ))}
                    {item.tags.length > 0 && <span className="block text-small text-danger-700">Contient : {item.tags.join(", ")}</span>}
                  </dd>
                </div>
              );
            })}
          </dl>
        )}
        <p className="m-0 text-small text-muted-foreground">Bébés : textures adaptées à chaque enfant (mixé, haché), d’après sa fiche.</p>
      </section>

      <section aria-labelledby="comptage" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1 print:hidden">
        <h2 id="comptage" className="m-0 text-title-2">Comptage par enfant</h2>
        <p className="m-0 text-small text-muted-foreground">
          Un repas compte quand l’enfant est présent à l’heure du repas ; avant l’heure, d’après son contrat. Une correction
          (avec motif) l’emporte sur le calcul.
        </p>
        <table className="w-full border-collapse text-left">
          <caption className="sr-only">Comptage des repas par enfant</caption>
          <thead>
            <tr className="border-b border-border-subtle text-label text-muted-foreground">
              <th scope="col" className="py-2">Enfant</th>
              <th scope="col" className="py-2">Section</th>
              <th scope="col" className="py-2">Repas</th>
              <th scope="col" className="py-2">Goûter</th>
            </tr>
          </thead>
          <tbody>
            {data.children.map((c) => (
              <tr key={c.childId} className="border-b border-border-subtle last:border-0">
                <td className="py-2">
                  {c.firstName} {c.lastName}
                  {c.packedLunch && <span className="block text-small text-muted-foreground">Panier repas</span>}
                </td>
                <td className="py-2">{c.sectionName ?? "—"}</td>
                {(["REPAS", "GOUTER"] as const).map((meal) => {
                  const served = meal === "REPAS" ? c.lunch : c.snack;
                  const basis = meal === "REPAS" ? c.lunchBasis : c.snackBasis;
                  const adjustment = meal === "REPAS" ? c.lunchAdjustment : c.snackAdjustment;
                  return (
                    <td key={meal} className="py-2">
                      <span className={cn(served ? "font-semibold" : "text-muted-foreground")}>
                        {c.packedLunch ? "Panier" : served ? (basis === "PREVISION" ? "Prévu" : "Servi") : "Non"}
                      </span>
                      {adjustment && <span className="block text-small text-warning-700">Corrigé : {adjustment.reason}</span>}
                      {canEdit && !c.packedLunch && (
                        <Button variant="ghost" size="sm" onClick={() => onCorrect(c, meal)}
                          aria-label={`Corriger ${meal === "REPAS" ? "le repas" : "le goûter"} de ${c.firstName} ${c.lastName}`}>
                          Corriger
                        </Button>
                      )}
                    </td>
                  );
                })}
              </tr>
            ))}
            {data.children.length === 0 && <tr><td colSpan={4} className="py-4 text-muted-foreground">Aucun enfant compté ce jour-là.</td></tr>}
          </tbody>
        </table>
      </section>

      <p className="m-0 hidden print:block">Vérifié par : ……………………………… Heure : ……………</p>
    </div>
  );
}

function CountCard({ count, total = false, large }: { count: SectionCount; total?: boolean; large: boolean }) {
  const details: string[] = [];
  if (count.mashed + count.minced > 0) {
    details.push([count.mashed && `Mixé ${count.mashed}`, count.minced && `Haché ${count.minced}`].filter(Boolean).join(" · "));
  }
  if (total) details.push(`${plural(count.children, "enfant")} présent${count.children > 1 ? "s" : ""}`);
  if (count.packedLunches > 0) details.push(`dont ${plural(count.packedLunches, "panier repas")}`);
  if (count.leavingBeforeSnack > 0) details.push(`${plural(count.leavingBeforeSnack, "départ")} avant le goûter`);
  return (
    <article
      aria-label={total ? "Total à préparer" : `Section ${count.name}`}
      className={cn("flex flex-col gap-1 rounded-lg border p-4 print:p-2", total ? "border-lagon-600 bg-lagon-50" : "border-border-subtle bg-card shadow-1")}
    >
      <h3 className="m-0 flex items-center gap-2 text-title-3">
        {count.color && <span aria-hidden className="size-3 rounded-full" style={{ backgroundColor: count.color }} />}
        {total ? "Total à préparer" : count.name}
      </h3>
      <p className={cn("m-0 font-semibold tabular-nums", large ? "text-[36px] leading-tight" : "text-title-1")}>{plural(count.lunches, "repas")}</p>
      <p className="m-0 tabular-nums">{plural(count.snacks, "goûter")}</p>
      {details.map((d) => <p key={d} className="m-0 text-small text-muted-foreground">{d}</p>)}
    </article>
  );
}

function CorrectionDialog({
  day,
  child,
  meal,
  onClose,
  onDone,
}: {
  day: string;
  child: KitchenChild;
  meal: Meal;
  onClose: () => void;
  onDone: (next: KitchenDay) => void;
}) {
  const current = meal === "REPAS" ? child.lunch : child.snack;
  const adjustment = meal === "REPAS" ? child.lunchAdjustment : child.snackAdjustment;
  const [served, setServed] = useState(!current);
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const label = meal === "REPAS" ? "repas" : "goûter";

  const submit = async (value: boolean | null) => {
    if (value !== null && !reason.trim()) return setError("Indiquez le motif de la correction.");
    try {
      onDone(await mealsApi.adjust({ childId: child.childId, day, meal, served: value, reason: value === null ? undefined : reason.trim() }));
      onClose();
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Corriger le {label} de {child.firstName} {child.lastName}</DialogTitle>
          <DialogDescription>
            Calcul d’après les présences : {current ? "servi" : "non servi"}. La correction et son motif l’emportent sur le calcul.
          </DialogDescription>
        </DialogHeader>
        <div role="radiogroup" aria-label={`Le ${label} a-t-il été servi ?`} className="flex gap-2">
          {[true, false].map((v) => (
            <label key={String(v)} className={cn("flex h-control items-center gap-2 rounded-md border px-3", served === v ? "border-lagon-600 bg-lagon-50" : "border-border")}>
              <input type="radio" name="served" checked={served === v} onChange={() => setServed(v)} />
              {v ? "Servi" : "Non servi"}
            </label>
          ))}
        </div>
        <FormField label="Motif"><Input value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} /></FormField>
        {error && <Alert>{error}</Alert>}
        <div className="flex flex-wrap gap-3">
          <Button onClick={() => void submit(served)}>Enregistrer la correction</Button>
          {adjustment && <Button variant="outline" onClick={() => void submit(null)}>Revenir au calcul</Button>}
          <Button variant="ghost" onClick={onClose}>Annuler</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function NoteDialog({ day, child, onClose, onDone }: { day: string; child: KitchenChild; onClose: () => void; onDone: () => Promise<unknown> }) {
  const [note, setNote] = useState(child.note ?? "");
  const [error, setError] = useState<string | null>(null);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Consigne du jour pour {child.firstName} {child.lastName}</DialogTitle>
          <DialogDescription>Affichée sur la fiche cuisine de ce jour, en plus des consignes calculées.</DialogDescription>
        </DialogHeader>
        <FormField label="Consigne"><Input value={note} maxLength={300} placeholder="Pas de dorure à l’œuf sur le pain" onChange={(e) => setNote(e.target.value)} /></FormField>
        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button
            onClick={async () => {
              try {
                await mealsApi.note({ childId: child.childId, day, note: note.trim() });
                await onDone();
                onClose();
              } catch (e) {
                setError(errorMessage(e));
              }
            }}
          >
            Enregistrer
          </Button>
          <Button variant="ghost" onClick={onClose}>Annuler</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
