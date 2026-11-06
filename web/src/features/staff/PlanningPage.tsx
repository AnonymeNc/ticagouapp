import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronLeft, ChevronRight, TriangleAlert } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { useSections } from "@/features/structure/useSections";
import { todayIso } from "@/lib/age";
import { errorMessage } from "@/lib/api";
import { isoToDateFr, parseDateFr } from "@/lib/datetime";
import { DAY_SHORT, addDays, formatMinutes, hhmm, isoWeekday } from "@/lib/format";
import { isoToField, selectClass, textOrNull } from "@/lib/formFields";
import { cn } from "@/lib/utils";
import { absenceLabels, staffApi, staffKeys, type AbsenceKind, type Forecast, type PlanningRow, type RatioStatus, type Shift } from "./api";
import { StaffHeader, useSeesTeam, weekly } from "./components";

const mondayOf = (iso: string) => addDays(iso, 1 - isoWeekday(iso));

const statusClasses: Record<RatioStatus, string> = {
  CONFORME: "bg-success-100 text-success-700",
  LIMITE: "bg-warning-100 text-warning-700",
  INSUFFISANT: "bg-danger-600 text-white",
};

/**
 * Planning de la semaine : grille personnes × jours (semaine type, exceptions, absences) et encadrement
 * prévisionnel du jour choisi, par créneau de 30 minutes ; les créneaux en sous-effectif sont en rouge.
 */
export function PlanningPage() {
  const user = useCurrentUser();
  const team = useSeesTeam();
  const canEdit = user.role === "DIRECTION";
  const sections = useSections();
  const queryClient = useQueryClient();
  const [monday, setMonday] = useState(mondayOf(todayIso()));
  const [day, setDay] = useState(todayIso());
  const [editing, setEditing] = useState<{ row: PlanningRow; shift: Shift } | null>(null);
  const planning = useQuery({ queryKey: staffKeys.planning(monday, !team), queryFn: ({ signal }) => staffApi.planning(monday, !team, signal) });
  const data = planning.data;
  const days = Array.from({ length: 7 }, (_, i) => addDays(monday, i))
    .filter((d) => data?.openDays.includes(isoWeekday(d)) || data?.rows.some((r) => r.shifts.find((s) => s.day === d)?.startsAt));
  const selected = days.includes(day) ? day : days[0];
  const sectionOf = (id: string | null) => sections.data?.find((s) => s.id === id);
  const go = (delta: number) => {
    const next = addDays(monday, 7 * delta);
    setMonday(next);
    setDay(next);
  };

  return (
    <>
      <StaffHeader subtitle={team ? undefined : "Votre planning de la semaine. Pour un changement, voyez la direction."} />
      <div className="flex flex-wrap items-center gap-2">
        <Button variant="outline" size="icon" aria-label="Semaine précédente" onClick={() => go(-1)}><ChevronLeft aria-hidden /></Button>
        <span className="text-title-3">Semaine du {isoToDateFr(monday)} au {isoToDateFr(addDays(monday, 6))}</span>
        <Button variant="outline" size="icon" aria-label="Semaine suivante" onClick={() => go(1)}><ChevronRight aria-hidden /></Button>
        {monday !== mondayOf(todayIso()) && <Button variant="ghost" onClick={() => { setMonday(mondayOf(todayIso())); setDay(todayIso()); }}>Cette semaine</Button>}
      </div>
      {planning.isError && <Alert>{errorMessage(planning.error)}</Alert>}
      {data && data.validatedMonths.length > 0 && (
        <p className="m-0 text-small text-muted-foreground">Heures validées pour la paie : le planning de ce mois ne se modifie plus.</p>
      )}

      {data && (
        <section aria-labelledby="grille" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
          <h2 id="grille" className="sr-only">Planning de l’équipe</h2>
          <div className="overflow-x-auto">
            <table className="w-full border-collapse text-left">
              <thead>
                <tr className="border-b border-border-subtle text-label text-muted-foreground">
                  <th scope="col" className="py-2 pr-3">Personne</th>
                  {days.map((d) => {
                    const short = data.shortages[d] ?? 0;
                    return (
                      <th key={d} scope="col" className="py-2 pr-2">
                        <button type="button" onClick={() => setDay(d)} aria-pressed={selected === d}
                          className={cn("flex flex-col items-start rounded px-1", selected === d && "bg-lagon-50 text-lagon-700")}>
                          <span>{DAY_SHORT[isoWeekday(d) - 1]} {isoToDateFr(d).slice(0, 5)}</span>
                          {short > 0 && <span className="inline-flex items-center gap-1 text-caption font-semibold text-danger-700"><TriangleAlert aria-hidden className="size-3" />{short} créneau{short > 1 ? "x" : ""}</span>}
                        </button>
                      </th>
                    );
                  })}
                  <th scope="col" className="py-2 text-right">Semaine</th>
                </tr>
              </thead>
              <tbody>
                {data.rows.map((r) => (
                  <tr key={r.userId} className="border-b border-border-subtle last:border-0 align-top">
                    <th scope="row" className="py-2 pr-3 text-left font-normal">
                      <span className="font-semibold">{r.name}</span>
                      <span className="block text-small text-muted-foreground">{r.jobTitle ?? ""}{!r.countsInRatio && " · hors encadrement"}</span>
                    </th>
                    {days.map((d) => {
                      const s = r.shifts.find((x) => x.day === d)!;
                      const section = sectionOf(s.sectionId);
                      const content = s.absence && !s.halfDay ? (
                        <span className="rounded bg-muted px-1.5 py-0.5 text-small">{absenceLabels[s.absence]}</span>
                      ) : s.startsAt ? (
                        <span className="flex flex-col text-small">
                          <span className="tabular-nums">{hhmm(s.startsAt)}–{hhmm(s.endsAt!)}{s.overridden && <span title="Exception à la semaine type"> *</span>}</span>
                          {section && <span className="text-muted-foreground"><span aria-hidden className="mr-1 inline-block size-2 rounded-full" style={{ backgroundColor: section.color }} />{section.name}</span>}
                          {s.absence && <span className="text-muted-foreground">{absenceLabels[s.absence]} (demi-journée)</span>}
                        </span>
                      ) : <span className="text-small text-muted-foreground">{s.overridden ? "Repos" : "—"}</span>;
                      return (
                        <td key={d} className="py-2 pr-2">
                          {canEdit ? (
                            <button type="button" className="w-full rounded p-1 text-left hover:bg-muted" aria-label={`Modifier le poste de ${r.name} le ${isoToDateFr(d)}`}
                              onClick={() => setEditing({ row: r, shift: s })}>{content}</button>
                          ) : content}
                        </td>
                      );
                    })}
                    <td className="py-2 text-right text-small tabular-nums">
                      {formatMinutes(r.plannedMinutes)}
                      {r.weeklyMinutes !== null && <span className="block text-muted-foreground">sur {weekly(r.weeklyMinutes)}</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {canEdit && <p className="m-0 text-small text-muted-foreground">Cliquez sur un jour pour changer l’horaire, la section, poser un repos ou une absence. * : exception à la semaine type.</p>}
        </section>
      )}

      {team && selected && <ForecastPanel day={selected} />}
      {editing && (
        <ShiftDialog row={editing.row} shift={editing.shift} onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); void queryClient.invalidateQueries({ queryKey: ["staff"] }); }} />
      )}
    </>
  );
}

/** Encadrement prévisionnel d'une journée : une ligne par section et une pour la structure, un carré par créneau. */
function ForecastPanel({ day }: { day: string }) {
  const forecast = useQuery({ queryKey: staffKeys.forecast(day), queryFn: ({ signal }) => staffApi.forecast(day, signal) });
  const f: Forecast | undefined = forecast.data;
  return (
    <section aria-labelledby="prevision" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <div>
        <h2 id="prevision" className="m-0 text-title-2">Encadrement prévisionnel du {isoToDateFr(day)}</h2>
        <p className="m-0 text-small text-muted-foreground">
          Enfants attendus selon leur contrat (absences signalées déduites) et personnel planifié qui compte dans l’encadrement.
          Un adulte compte sur un créneau s’il est là tout le créneau.
        </p>
      </div>
      {forecast.isError && <Alert>{errorMessage(forecast.error)}</Alert>}
      {f && !f.open && <p className="m-0 text-muted-foreground">La structure est fermée ce jour-là.</p>}
      {f && f.open && (
        <>
          <p className={cn("m-0 font-semibold", f.shortages > 0 ? "text-danger-700" : "text-success-700")}>
            {f.shortages > 0 ? `${f.shortages} créneau${f.shortages > 1 ? "x" : ""} en sous-effectif` : "Encadrement suffisant toute la journée"}
            {f.variableChildren > 0 && <span className="font-normal text-muted-foreground"> · {f.variableChildren} enfant{f.variableChildren > 1 ? "s" : ""} au planning variable non compté{f.variableChildren > 1 ? "s" : ""}</span>}
          </p>
          <div className="overflow-x-auto">
            <table className="border-collapse text-caption tabular-nums">
              <caption className="sr-only">Encadrement par créneau de 30 minutes</caption>
              <thead>
                <tr>
                  <th scope="col" className="pr-2 text-left font-normal text-muted-foreground">Section</th>
                  {f.slots.map((s) => <th key={s.startsAt} scope="col" className="px-0.5 font-normal text-muted-foreground">{hhmm(s.startsAt).endsWith(":00") ? hhmm(s.startsAt) : ""}</th>)}
                </tr>
              </thead>
              <tbody>
                {[...f.sections.map((s) => ({ key: s.id, name: s.name, load: (slot: (typeof f.slots)[number]) => slot.sections[s.id] })),
                  { key: "global", name: "Structure", load: (slot: (typeof f.slots)[number]) => slot.global }].map((line) => (
                  <tr key={line.key}>
                    <th scope="row" className="pr-2 text-left text-label font-normal">{line.name}</th>
                    {f.slots.map((slot) => {
                      const l = line.load(slot);
                      if (!l) return <td key={slot.startsAt} />;
                      const label = `${line.name}, ${hhmm(slot.startsAt)}–${hhmm(slot.endsAt)} : ${l.children} enfant${l.children > 1 ? "s" : ""}, ${l.adults} adulte${l.adults > 1 ? "s" : ""} pour ${l.required} requis`;
                      return (
                        <td key={slot.startsAt} className="px-0.5 py-0.5">
                          <span title={label} aria-label={label}
                            className={cn("flex h-7 w-9 items-center justify-center rounded-sm", l.children === 0 && l.adults === 0 ? "bg-muted text-muted-foreground" : statusClasses[l.status])}>
                            {l.children > 0 ? `${l.adults}/${l.required}` : ""}
                          </span>
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="m-0 text-small text-muted-foreground">Dans chaque case : adultes planifiés / adultes requis. Rouge : sous-effectif ; orange : à la limite.</p>
        </>
      )}
    </section>
  );
}

const KINDS: AbsenceKind[] = ["CONGES", "MALADIE", "FORMATION", "RECUPERATION", "AUTRE"];

/** Poste d'un jour : horaire, section, repos, ou absence (congés, maladie…). */
function ShiftDialog({ row, shift, onClose, onSaved }: { row: PlanningRow; shift: Shift; onClose: () => void; onSaved: () => void }) {
  const sections = useSections();
  const [mode, setMode] = useState<"poste" | "repos" | "absence">(shift.absence ? "absence" : shift.startsAt || !shift.overridden ? "poste" : "repos");
  const [starts, setStarts] = useState(shift.startsAt ? hhmm(shift.startsAt) : "07:00");
  const [ends, setEnds] = useState(shift.endsAt ? hhmm(shift.endsAt) : "15:30");
  const [pause, setPause] = useState(String(shift.breakMinutes || 30));
  const [section, setSection] = useState(shift.sectionId ?? row.homeSectionId ?? "");
  const [note, setNote] = useState(shift.note ?? "");
  const [kind, setKind] = useState<AbsenceKind>(shift.absence ?? "CONGES");
  const [to, setTo] = useState(isoToField(shift.day));
  const [half, setHalf] = useState(shift.halfDay);
  const [error, setError] = useState<string | null>(null);
  const run = async (action: () => Promise<unknown>) => {
    setError(null);
    try {
      await action();
      onSaved();
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{row.name} · {isoToDateFr(shift.day)}</DialogTitle>
          <DialogDescription>Ce changement ne vaut que pour ce jour ; la semaine type ne change pas.</DialogDescription>
        </DialogHeader>
        <fieldset className="m-0 flex gap-4 border-0 p-0">
          <legend className="sr-only">Type de changement</legend>
          {(["poste", "repos", "absence"] as const).map((m) => (
            <label key={m} className="flex items-center gap-2"><input type="radio" checked={mode === m} onChange={() => setMode(m)} />{m === "poste" ? "Horaire" : m === "repos" ? "Repos" : "Absence"}</label>
          ))}
        </fieldset>
        {mode === "poste" && (
          <div className="grid grid-cols-2 gap-3">
            <FormField label="Début"><Input type="time" value={starts} onChange={(e) => setStarts(e.target.value)} /></FormField>
            <FormField label="Fin"><Input type="time" value={ends} onChange={(e) => setEnds(e.target.value)} /></FormField>
            <FormField label="Pause (min)"><Input value={pause} inputMode="numeric" onChange={(e) => setPause(e.target.value)} /></FormField>
            <FormField label="Section">
              <select className={selectClass} value={section} onChange={(e) => setSection(e.target.value)}>
                <option value="">Toutes sections</option>
                {sections.data?.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            </FormField>
            <FormField label="Note" className="col-span-2"><Input value={note} maxLength={200} placeholder="Remplacement, réunion…" onChange={(e) => setNote(e.target.value)} /></FormField>
          </div>
        )}
        {mode === "absence" && shift.absenceId && (
          <p className="m-0">{absenceLabels[shift.absence!]}{shift.halfDay && " (demi-journée)"} : supprimez l’absence pour la saisir autrement.</p>
        )}
        {mode === "absence" && !shift.absenceId && (
          <div className="grid grid-cols-2 gap-3">
            <FormField label="Motif">
              <select className={selectClass} value={kind} onChange={(e) => setKind(e.target.value as AbsenceKind)}>
                {KINDS.map((k) => <option key={k} value={k}>{absenceLabels[k]}</option>)}
              </select>
            </FormField>
            <FormField label="Jusqu’au"><Input value={to} disabled={half} onChange={(e) => setTo(e.target.value)} /></FormField>
            <label className="col-span-2 flex items-center gap-2"><input type="checkbox" checked={half} onChange={(e) => setHalf(e.target.checked)} />Demi-journée</label>
          </div>
        )}
        {error && <Alert>{error}</Alert>}
        <div className="flex flex-wrap gap-3">
          <Button
            disabled={mode === "absence" && !!shift.absenceId}
            onClick={() => {
              if (mode === "poste") {
                return void run(() => staffApi.setShift(row.userId, shift.day, { startsAt: starts, endsAt: ends, breakMinutes: Number(pause) || 0, sectionId: section || null, note: textOrNull(note) }));
              }
              if (mode === "repos") {
                return void run(() => staffApi.setShift(row.userId, shift.day, { startsAt: null, endsAt: null, breakMinutes: 0, sectionId: null, note: textOrNull(note) }));
              }
              const toDay = half ? shift.day : parseDateFr(to);
              if (!toDay) return setError("Saisissez la date au format JJ/MM/AAAA.");
              const body = { userId: row.userId, kind, fromDay: shift.day, toDay, halfDay: half, note: null };
              void run(() => staffApi.createAbsence(body));
            }}
          >
            Enregistrer
          </Button>
          {shift.overridden && <Button variant="outline" onClick={() => void run(() => staffApi.resetShift(row.userId, shift.day))}>Revenir à la semaine type</Button>}
          {shift.absenceId && <Button variant="outline" onClick={() => void run(() => staffApi.deleteAbsence(shift.absenceId!))}>Supprimer l’absence</Button>}
          <Button variant="ghost" onClick={onClose}>Annuler</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
