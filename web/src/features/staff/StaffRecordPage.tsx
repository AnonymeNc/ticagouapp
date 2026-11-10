import { useRef, useState } from "react";
import { Link, useParams } from "react-router";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Paperclip, Pencil, Plus, Printer, Trash2, TriangleAlert } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { DateInput } from "@/components/DateInput";
import { Input } from "@/components/ui/input";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { roleLabels } from "@/features/auth/types";
import { Facts, Panel } from "@/features/children/tabs/common";
import { TonePill } from "@/features/enrollment/components";
import { useSections } from "@/features/structure/useSections";
import { todayIso } from "@/lib/age";
import { errorMessage } from "@/lib/api";
import { isoToDateFr, parseDateFr } from "@/lib/datetime";
import { DAY_NAMES, daysBetween, formatMinutes, hhmm, span } from "@/lib/format";
import { isoToField, selectClass, textOrNull } from "@/lib/formFields";
import {
  contractLabels,
  qualificationLabels,
  staffApi,
  staffKeys,
  type ContractType,
  type Profile,
  type Qualification,
  type QualificationKind,
  type ScheduleDay,
  type Training,
} from "./api";
import { QualificationPill, alertText, initials, weekly } from "./components";

const dateOrNull = (v: string) => (v.trim() ? parseDateFr(v) : null);

/** Fiche d'une personne (maquette « Fiche de Céline Hnawia ») : identité, contrat, qualifications, formations, semaine type. */
export function StaffRecordPage() {
  const { id = "" } = useParams();
  const user = useCurrentUser();
  const canEdit = user.role === "DIRECTION";
  const sections = useSections();
  const queryClient = useQueryClient();
  const profile = useQuery({ queryKey: staffKeys.profile(id), queryFn: ({ signal }) => staffApi.profile(id, signal), refetchOnWindowFocus: false });
  const [editing, setEditing] = useState<"profile" | "schedule" | Qualification | "qualification" | Training | "training" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["staff"] });

  if (profile.isPending) return <p className="text-muted-foreground">Chargement de la fiche…</p>;
  if (profile.isError) return <Alert>{errorMessage(profile.error)}</Alert>;
  const p = profile.data;
  const m = p.member;
  const sectionName = (sid: string | null) => (sid ? sections.data?.find((s) => s.id === sid)?.name ?? "—" : "Toutes sections");
  const run = async (action: () => Promise<unknown>) => {
    setError(null);
    try {
      await action();
      await refresh();
    } catch (e) {
      setError(errorMessage(e));
    }
  };
  const nextTraining = p.trainings.filter((t) => !t.done && t.startsOn >= todayIso()).sort((a, b) => a.startsOn.localeCompare(b.startsOn))[0];

  return (
    <>
      <Link to="/personnel" className="inline-flex items-center gap-1.5 text-label text-lagon-700 print:hidden">
        <ArrowLeft aria-hidden className="size-4" />Personnel
      </Link>
      <section aria-label="Résumé de la fiche" className="flex flex-wrap items-center gap-5 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <span aria-hidden className="flex size-16 items-center justify-center rounded-full bg-lagon-100 text-title-2 text-lagon-700">{initials(m.firstName, m.lastName)}</span>
        <div className="flex flex-1 flex-col gap-1">
          <h1 className="m-0 font-display text-title-1">{m.firstName} <span className="font-normal">{m.lastName}</span></h1>
          <div className="flex flex-wrap items-center gap-2 text-muted-foreground">
            <span>{m.jobTitle ?? roleLabels[m.role]}</span>·<span>{sectionName(m.homeSectionId)}</span>
            <TonePill tone={m.countsInRatio ? "info" : "neutral"}>{m.countsInRatio ? "Compte dans l’encadrement" : "Hors encadrement"}</TonePill>
          </div>
        </div>
        <div className="flex gap-2 print:hidden">
          <Button variant="outline" onClick={() => window.print()}><Printer aria-hidden />Imprimer la fiche</Button>
          {canEdit && <Button onClick={() => setEditing("profile")}><Pencil aria-hidden />Modifier la fiche</Button>}
        </div>
      </section>

      {m.alerts.map((a, i) => (
        <p key={i} role="alert" className="m-0 flex items-center gap-2 rounded-md border border-warning-700/30 bg-warning-100 px-4 py-3 text-warning-700">
          <TriangleAlert aria-hidden className="size-5 shrink-0" />
          <span>
            {alertText(a)}{a.state === "A_RENOUVELER" && `, dans ${daysBetween(todayIso(), a.date)} jours`}.
            {a.kind === "QUALIFICATION" && nextTraining && ` Formation prévue le ${isoToDateFr(nextTraining.startsOn)} : ${nextTraining.label}.`}
          </span>
        </p>
      ))}
      {error && <Alert>{error}</Alert>}

      <div className="grid gap-4 lg:grid-cols-2">
        <Panel title="Identité">
          <Facts items={[
            ["Nom", m.lastName], ["Prénom", m.firstName], ["Né(e) le", p.birthDate && isoToDateFr(p.birthDate)],
            ["Téléphone", p.phone], ["Courriel", p.email], ["Adresse", p.address], ["N° CAFAT", p.cafatNumber],
            ["À prévenir", p.emergencyContact],
          ]} />
        </Panel>
        <Panel title="Contrat">
          <Facts items={[
            ["Type", contractLabels[m.contractType]],
            ["Durée", m.weeklyMinutes === null ? "Rémunération à l’heure" : `${m.weeklyMinutes >= 39 * 60 ? "Temps plein" : "Temps partiel"}, ${weekly(m.weeklyMinutes)} par semaine`],
            ["Entrée le", m.hiredOn && isoToDateFr(m.hiredOn)], ["Fin le", m.endsOn && isoToDateFr(m.endsOn)],
            ["Fonction", m.jobTitle], ["Diplôme", m.diploma], ["Section", sectionName(m.homeSectionId)],
            ["Encadrement", m.countsInRatio ? "Compte dans le taux" : "Hors encadrement"],
          ]} />
        </Panel>
      </div>

      <Panel title="Qualifications" actions={canEdit && <Button variant="outline" onClick={() => setEditing("qualification")}><Plus aria-hidden />Ajouter une qualification</Button>}>
        {p.qualifications.length === 0 ? <p className="m-0 text-muted-foreground">Aucune qualification enregistrée.</p> : (
          <table className="w-full border-collapse text-left">
            <thead>
              <tr className="border-b border-border-subtle text-label text-muted-foreground">
                <th scope="col" className="py-2 pr-3">Intitulé</th>
                <th scope="col" className="py-2 pr-3">Obtenue le</th>
                <th scope="col" className="py-2 pr-3">Valable jusqu’au</th>
                <th scope="col" className="py-2 pr-3">Justificatif</th>
                <th scope="col" className="py-2 pr-3">État</th>
                <th scope="col" className="py-2"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {p.qualifications.map((q) => (
                <tr key={q.id} className="border-b border-border-subtle last:border-0">
                  <td className="py-2 pr-3">{q.label}{q.verified && <span className="block text-small text-muted-foreground">Vérifié</span>}</td>
                  <td className="py-2 pr-3">{q.obtainedOn ? isoToDateFr(q.obtainedOn) : "—"}</td>
                  <td className="py-2 pr-3">{q.validUntil ? isoToDateFr(q.validUntil) : "Sans limite"}</td>
                  <td className="py-2 pr-3">
                    {q.fileName ? <button type="button" className="text-lagon-700" onClick={() => void run(() => staffApi.qualificationFile(q))}>{q.fileName}</button> : "—"}
                    {canEdit && <Attach label={`Joindre le justificatif de ${q.label}`} onFile={(f) => void run(() => staffApi.attachQualification(q.id, f))} />}
                  </td>
                  <td className="py-2 pr-3"><QualificationPill state={q.state} validUntil={q.validUntil} /></td>
                  <td className="py-2 text-right whitespace-nowrap">
                    {canEdit && (
                      <>
                        <Button variant="ghost" size="sm" onClick={() => setEditing(q)}>Modifier</Button>
                        <Button variant="ghost" size="sm" className="text-danger-700" aria-label={`Supprimer ${q.label}`}
                          onClick={() => window.confirm(`Supprimer « ${q.label} » et son justificatif ?`) && void run(() => staffApi.deleteQualification(q.id))}>
                          <Trash2 aria-hidden />
                        </Button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Panel>

      <Panel title="Formations" actions={canEdit && <Button variant="outline" onClick={() => setEditing("training")}><Plus aria-hidden />Ajouter une formation</Button>}>
        <p className="m-0 text-small text-muted-foreground">{formatMinutes(p.trainingMinutesSinceJanuary).replace(" h 00", " h")} suivies depuis janvier {Number(todayIso().slice(0, 4)) - 1}</p>
        <ul className="m-0 flex list-none flex-col gap-2 p-0">
          {p.trainings.map((t) => (
            <li key={t.id} className="flex flex-wrap items-center justify-between gap-2 border-b border-border-subtle pb-2 last:border-0">
              <span>
                <strong>{t.label}</strong>
                <span className="block text-small text-muted-foreground">
                  {formatMinutes(t.minutes).replace(" h 00", " h")} · {t.startsOn === t.endsOn ? `le ${isoToDateFr(t.startsOn)}` : `du ${isoToDateFr(t.startsOn)} au ${isoToDateFr(t.endsOn)}`}
                  {t.organization && ` · ${t.organization}`}
                  {t.fileName && <> · <button type="button" className="text-lagon-700" onClick={() => void run(() => staffApi.trainingFile(t))}>{t.fileName}</button></>}
                </span>
              </span>
              <span className="flex items-center gap-2">
                <TonePill tone={t.done ? "success" : "info"}>{t.done ? "Suivie" : "Prévue"}</TonePill>
                {canEdit && <Attach label={`Joindre l’attestation de ${t.label}`} onFile={(f) => void run(() => staffApi.attachTraining(t.id, f))} />}
                {canEdit && <Button variant="ghost" size="sm" onClick={() => setEditing(t)}>Modifier</Button>}
              </span>
            </li>
          ))}
        </ul>
      </Panel>

      <Panel title="Planning hebdomadaire" actions={canEdit && <Button variant="outline" onClick={() => setEditing("schedule")}><Pencil aria-hidden />Modifier la semaine type</Button>}>
        {p.schedule ? (
          <>
            <p className="m-0 text-muted-foreground">
              Semaine type : {formatMinutes(p.schedule.weekMinutes)}{m.weeklyMinutes !== null && ` pour ${weekly(m.weeklyMinutes)} au contrat`} · en vigueur depuis le {isoToDateFr(p.schedule.effectiveFrom)}
              {" · "}<Link to="/personnel/planning" className="text-lagon-700">Voir le planning d’équipe</Link>
            </p>
            <WeekStrip days={p.schedule.days} />
          </>
        ) : <p className="m-0 text-muted-foreground">Pas encore de semaine type.</p>}
        {p.upcomingSchedules.map((s) => <p key={s.effectiveFrom} className="m-0 text-small">Nouvelle semaine type à partir du {isoToDateFr(s.effectiveFrom)} : {formatMinutes(s.weekMinutes)}.</p>)}
      </Panel>

      {editing === "profile" && <ProfileDialog profile={p} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); void refresh(); }} />}
      {editing === "schedule" && <ScheduleDialog userId={id} profile={p} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); void refresh(); }} />}
      {(editing === "qualification" || (editing && typeof editing === "object" && "kind" in editing)) && (
        <QualificationDialog userId={id} qualification={editing === "qualification" ? null : (editing as Qualification)}
          onClose={() => setEditing(null)} onSaved={() => { setEditing(null); void refresh(); }} />
      )}
      {(editing === "training" || (editing && typeof editing === "object" && "minutes" in editing)) && (
        <TrainingDialog userId={id} training={editing === "training" ? null : (editing as Training)}
          onClose={() => setEditing(null)} onSaved={() => { setEditing(null); void refresh(); }} />
      )}
    </>
  );
}

function Attach({ label, onFile }: { label: string; onFile: (file: File) => void }) {
  const input = useRef<HTMLInputElement>(null);
  return (
    <>
      <Button variant="ghost" size="sm" aria-label={label} onClick={() => input.current?.click()}><Paperclip aria-hidden /></Button>
      <input ref={input} type="file" accept="application/pdf,image/*" className="hidden"
        onChange={(e) => { const f = e.target.files?.[0]; if (f) onFile(f); e.target.value = ""; }} />
    </>
  );
}

const FROM = 6 * 60 + 30;
const TO = 18 * 60 + 30;

/** Frise de la semaine type (maquette) : 06:30 → 18:30. */
export function WeekStrip({ days }: { days: ScheduleDay[] }) {
  return (
    <div className="flex flex-col gap-1">
      <div className="ml-24 flex justify-between text-caption text-muted-foreground">
        {["06:30", "09:00", "12:00", "15:00", "18:30"].map((t) => <span key={t}>{t}</span>)}
      </div>
      {[1, 2, 3, 4, 5, 6, 7].filter((d) => d <= 5 || days.some((x) => x.dayOfWeek === d)).map((d) => {
        const day = days.find((x) => x.dayOfWeek === d);
        return (
          <div key={d} className="flex items-center gap-2">
            <span className="w-22 text-label">{DAY_NAMES[d - 1]}</span>
            <div className="relative h-7 flex-1 rounded bg-muted">
              {day && (
                <div className="absolute inset-y-0 flex items-center rounded bg-lagon-600 px-2 text-small text-white"
                  style={{ left: `${((span("06:30", hhmm(day.startsAt))) / (TO - FROM)) * 100}%`, width: `${(span(hhmm(day.startsAt), hhmm(day.endsAt)) / (TO - FROM)) * 100}%` }}>
                  {hhmm(day.startsAt)} – {hhmm(day.endsAt)}
                </div>
              )}
            </div>
            <span className="w-14 text-right text-small tabular-nums">{day ? formatMinutes(span(hhmm(day.startsAt), hhmm(day.endsAt)) - day.breakMinutes) : "—"}</span>
          </div>
        );
      })}
      <p className="m-0 text-small text-muted-foreground">Pause déduite de la durée.</p>
    </div>
  );
}

function ProfileDialog({ profile, onClose, onSaved }: { profile: Profile; onClose: () => void; onSaved: () => void }) {
  const m = profile.member;
  const [f, setF] = useState({
    birthDate: isoToField(profile.birthDate), phone: profile.phone ?? "", email: profile.email ?? "", address: profile.address ?? "",
    cafatNumber: profile.cafatNumber ?? "", emergencyContact: profile.emergencyContact ?? "", diploma: m.diploma ?? "",
    contractType: m.contractType, hourly: m.weeklyMinutes === null,
    weekly: m.weeklyMinutes === null ? "39" : String(m.weeklyMinutes / 60).replace(".", ","),
    hiredOn: isoToField(m.hiredOn), endsOn: isoToField(m.endsOn),
  });
  const [error, setError] = useState<string | null>(null);
  const set = (patch: Partial<typeof f>) => setF((x) => ({ ...x, ...patch }));
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>Modifier la fiche de {m.firstName} {m.lastName}</DialogTitle>
          <DialogDescription>Fonction, section et encadrement se modifient dans Paramètres › Utilisateurs et rôles.</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-2 gap-3">
          <FormField label="Né(e) le"><DateInput value={f.birthDate} onChange={(e) => set({ birthDate: e.target.value })} /></FormField>
          <FormField label="Téléphone"><Input value={f.phone} maxLength={30} onChange={(e) => set({ phone: e.target.value })} /></FormField>
          <FormField label="Courriel"><Input value={f.email} maxLength={254} onChange={(e) => set({ email: e.target.value })} /></FormField>
          <FormField label="N° CAFAT"><Input value={f.cafatNumber} maxLength={30} onChange={(e) => set({ cafatNumber: e.target.value })} /></FormField>
          <FormField label="Adresse" className="col-span-2"><Input value={f.address} maxLength={300} onChange={(e) => set({ address: e.target.value })} /></FormField>
          <FormField label="À prévenir (nom et téléphone)" className="col-span-2"><Input value={f.emergencyContact} maxLength={200} onChange={(e) => set({ emergencyContact: e.target.value })} /></FormField>
          <FormField label="Diplôme"><Input value={f.diploma} maxLength={120} onChange={(e) => set({ diploma: e.target.value })} /></FormField>
          <FormField label="Type de contrat">
            <select className={selectClass} value={f.contractType} onChange={(e) => set({ contractType: e.target.value as ContractType })}>
              {Object.entries(contractLabels).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
            </select>
          </FormField>
          <FormField label="Heures par semaine"><Input value={f.weekly} disabled={f.hourly} inputMode="decimal" onChange={(e) => set({ weekly: e.target.value })} /></FormField>
          <label className="flex items-end gap-2 pb-2"><input type="checkbox" checked={f.hourly} onChange={(e) => set({ hourly: e.target.checked })} />Rémunération à l’heure</label>
          <FormField label="Entrée le"><DateInput value={f.hiredOn} onChange={(e) => set({ hiredOn: e.target.value })} /></FormField>
          <FormField label="Fin le (CDD)"><DateInput value={f.endsOn} onChange={(e) => set({ endsOn: e.target.value })} /></FormField>
        </div>
        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button
            onClick={async () => {
              const dates = [f.birthDate, f.hiredOn, f.endsOn].map((d) => [d, dateOrNull(d)] as const);
              if (dates.some(([raw, iso]) => raw.trim() && !iso)) return setError("Saisissez les dates au format JJ/MM/AAAA.");
              const hours = Number(f.weekly.replace(",", "."));
              if (!f.hourly && !(hours > 0 && hours <= 60)) return setError("Indiquez la durée hebdomadaire en heures.");
              try {
                await staffApi.updateProfile(m.userId, {
                  birthDate: dates[0][1], phone: textOrNull(f.phone), email: textOrNull(f.email), address: textOrNull(f.address),
                  cafatNumber: textOrNull(f.cafatNumber), emergencyContact: textOrNull(f.emergencyContact), diploma: textOrNull(f.diploma),
                  contractType: f.contractType, weeklyMinutes: f.hourly ? null : Math.round(hours * 60), hiredOn: dates[1][1], endsOn: dates[2][1],
                });
                onSaved();
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

function ScheduleDialog({ userId, profile, onClose, onSaved }: { userId: string; profile: Profile; onClose: () => void; onSaved: () => void }) {
  const sections = useSections();
  const current = profile.schedule?.days ?? [];
  const [from, setFrom] = useState(isoToField(todayIso()));
  const [rows, setRows] = useState(() => [1, 2, 3, 4, 5, 6].map((d) => {
    const x = current.find((c) => c.dayOfWeek === d);
    return { day: d, on: !!x, starts: x ? hhmm(x.startsAt) : "07:00", ends: x ? hhmm(x.endsAt) : "15:30", pause: String(x?.breakMinutes ?? 30),
      section: x?.sectionId ?? profile.member.homeSectionId ?? "" };
  }));
  const [error, setError] = useState<string | null>(null);
  const update = (day: number, patch: Partial<(typeof rows)[number]>) => setRows((r) => r.map((x) => (x.day === day ? { ...x, ...patch } : x)));
  const total = rows.filter((r) => r.on).reduce((n, r) => n + Math.max(span(r.starts, r.ends) - (Number(r.pause) || 0), 0), 0);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>Semaine type</DialogTitle>
          <DialogDescription>S’applique à partir de la date choisie ; les semaines précédentes gardent l’ancien planning.</DialogDescription>
        </DialogHeader>
        <FormField label="À partir du"><DateInput value={from} onChange={(e) => setFrom(e.target.value)} className="w-40" /></FormField>
        <table className="w-full border-collapse text-left">
          <thead>
            <tr className="text-label text-muted-foreground"><th className="py-1">Jour</th><th>Début</th><th>Fin</th><th>Pause (min)</th><th>Section</th></tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.day}>
                <td className="py-1"><label className="flex items-center gap-2"><input type="checkbox" checked={r.on} onChange={(e) => update(r.day, { on: e.target.checked })} />{DAY_NAMES[r.day - 1]}</label></td>
                <td><Input aria-label={`Début ${DAY_NAMES[r.day - 1]}`} type="time" value={r.starts} disabled={!r.on} onChange={(e) => update(r.day, { starts: e.target.value })} /></td>
                <td><Input aria-label={`Fin ${DAY_NAMES[r.day - 1]}`} type="time" value={r.ends} disabled={!r.on} onChange={(e) => update(r.day, { ends: e.target.value })} /></td>
                <td><Input aria-label={`Pause ${DAY_NAMES[r.day - 1]}`} value={r.pause} disabled={!r.on} inputMode="numeric" className="w-20" onChange={(e) => update(r.day, { pause: e.target.value })} /></td>
                <td>
                  <select aria-label={`Section ${DAY_NAMES[r.day - 1]}`} className={selectClass} value={r.section} disabled={!r.on} onChange={(e) => update(r.day, { section: e.target.value })}>
                    <option value="">Toutes sections</option>
                    {sections.data?.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
                  </select>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="m-0 text-small text-muted-foreground">
          Total : {formatMinutes(total)}{profile.member.weeklyMinutes !== null && ` pour ${weekly(profile.member.weeklyMinutes)} au contrat`}.
        </p>
        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button
            onClick={async () => {
              const effectiveFrom = parseDateFr(from);
              if (!effectiveFrom) return setError("Saisissez la date au format JJ/MM/AAAA.");
              const on = rows.filter((r) => r.on);
              if (on.some((r) => span(r.starts, r.ends) <= 0)) return setError("L’heure de fin doit suivre l’heure de début.");
              try {
                await staffApi.setSchedule(userId, {
                  effectiveFrom,
                  days: on.map((r) => ({ dayOfWeek: r.day, startsAt: r.starts, endsAt: r.ends, breakMinutes: Number(r.pause) || 0, sectionId: r.section || null })),
                });
                onSaved();
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

function QualificationDialog({ userId, qualification, onClose, onSaved }: { userId: string; qualification: Qualification | null; onClose: () => void; onSaved: () => void }) {
  const [kind, setKind] = useState<QualificationKind>(qualification?.kind ?? "SST");
  const [label, setLabel] = useState(qualification?.label ?? "");
  const [obtained, setObtained] = useState(isoToField(qualification?.obtainedOn));
  const [until, setUntil] = useState(isoToField(qualification?.validUntil));
  const [verified, setVerified] = useState(qualification?.verified ?? false);
  const [error, setError] = useState<string | null>(null);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{qualification ? "Modifier la qualification" : "Ajouter une qualification"}</DialogTitle>
          <DialogDescription>Une qualification avec une date de fin déclenche une alerte avant son expiration.</DialogDescription>
        </DialogHeader>
        <FormField label="Type">
          <select className={selectClass} value={kind} onChange={(e) => { setKind(e.target.value as QualificationKind); if (!label) setLabel(qualificationLabels[e.target.value as QualificationKind]); }}>
            {Object.entries(qualificationLabels).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </select>
        </FormField>
        <FormField label="Intitulé"><Input value={label} maxLength={160} onChange={(e) => setLabel(e.target.value)} /></FormField>
        <div className="grid grid-cols-2 gap-3">
          <FormField label="Obtenue le"><DateInput value={obtained} onChange={(e) => setObtained(e.target.value)} /></FormField>
          <FormField label="Valable jusqu’au" hint="Vide : sans limite"><DateInput value={until} onChange={(e) => setUntil(e.target.value)} /></FormField>
        </div>
        <label className="flex items-center gap-2"><input type="checkbox" checked={verified} onChange={(e) => setVerified(e.target.checked)} />Justificatif vérifié</label>
        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button
            onClick={async () => {
              const obtainedOn = dateOrNull(obtained);
              const validUntil = dateOrNull(until);
              if ((obtained.trim() && !obtainedOn) || (until.trim() && !validUntil)) return setError("Saisissez les dates au format JJ/MM/AAAA.");
              if (!label.trim()) return setError("Indiquez l’intitulé.");
              const body = { kind, label: label.trim(), obtainedOn, validUntil, verified };
              try {
                await (qualification ? staffApi.updateQualification(qualification.id, body) : staffApi.addQualification(userId, body));
                onSaved();
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

function TrainingDialog({ userId, training, onClose, onSaved }: { userId: string; training: Training | null; onClose: () => void; onSaved: () => void }) {
  const [label, setLabel] = useState(training?.label ?? "");
  const [hours, setHours] = useState(training ? String(training.minutes / 60).replace(".", ",") : "7");
  const [from, setFrom] = useState(isoToField(training?.startsOn));
  const [to, setTo] = useState(isoToField(training?.endsOn));
  const [organization, setOrganization] = useState(training?.organization ?? "");
  const [done, setDone] = useState(training?.done ?? false);
  const [error, setError] = useState<string | null>(null);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{training ? "Modifier la formation" : "Ajouter une formation"}</DialogTitle>
          <DialogDescription>Formation prévue ou suivie, avec son attestation.</DialogDescription>
        </DialogHeader>
        <FormField label="Intitulé"><Input value={label} maxLength={200} onChange={(e) => setLabel(e.target.value)} /></FormField>
        <div className="grid grid-cols-3 gap-3">
          <FormField label="Durée (h)"><Input value={hours} inputMode="decimal" onChange={(e) => setHours(e.target.value)} /></FormField>
          <FormField label="Du"><DateInput value={from} onChange={(e) => setFrom(e.target.value)} /></FormField>
          <FormField label="Au" hint="Vide : même jour"><DateInput value={to} onChange={(e) => setTo(e.target.value)} /></FormField>
        </div>
        <FormField label="Organisme"><Input value={organization} maxLength={160} onChange={(e) => setOrganization(e.target.value)} /></FormField>
        <label className="flex items-center gap-2"><input type="checkbox" checked={done} onChange={(e) => setDone(e.target.checked)} />Formation suivie</label>
        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button
            onClick={async () => {
              const startsOn = parseDateFr(from);
              const endsOn = to.trim() ? parseDateFr(to) : startsOn;
              const minutes = Math.round(Number(hours.replace(",", ".")) * 60);
              if (!startsOn || !endsOn) return setError("Saisissez les dates au format JJ/MM/AAAA.");
              if (!label.trim() || !(minutes > 0)) return setError("Indiquez l’intitulé et la durée.");
              const body = { label: label.trim(), minutes, startsOn, endsOn, organization: textOrNull(organization), done };
              try {
                await (training ? staffApi.updateTraining(training.id, body) : staffApi.addTraining(userId, body));
                onSaved();
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
