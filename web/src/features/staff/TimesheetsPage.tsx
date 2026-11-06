import { Fragment, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { CircleCheck, FileSpreadsheet, FileText, LockOpen } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { TonePill } from "@/features/enrollment/components";
import { billableMonths } from "@/features/billing/components";
import { todayIso } from "@/lib/age";
import { errorMessage } from "@/lib/api";
import { formatDate, formatTime, isoToDateFr } from "@/lib/datetime";
import { DAY_SHORT, formatMinutes, formatMonthLong, hhmm, isoWeekday } from "@/lib/format";
import { selectClass } from "@/lib/formFields";
import { cn } from "@/lib/utils";
import { absenceLabels, anomalyLabels, contractLabels, staffApi, staffKeys, type AbsenceKind, type PersonSheet } from "./api";
import { StaffHeader, useSeesTeam, weekly } from "./components";

const h = (minutes: number) => (minutes === 0 ? "—" : formatMinutes(minutes));

/**
 * Feuilles d'heures du mois pour la paie : prévu, pointé (pause déduite), absences, heures au-delà du contrat,
 * anomalies à corriger ; validation par la direction, puis export CSV ou Excel du mois figé.
 */
export function TimesheetsPage() {
  const user = useCurrentUser();
  const team = useSeesTeam();
  const isDirection = user.role === "DIRECTION";
  const queryClient = useQueryClient();
  const [month, setMonth] = useState(billableMonths(1)[1]);
  const [open, setOpen] = useState<string | null>(null);
  const [reopening, setReopening] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const sheet = useQuery({ queryKey: staffKeys.timesheet(month, !team), queryFn: ({ signal }) => staffApi.timesheet(month, !team, signal) });
  const data = sheet.data;
  const anomalies = data?.people.reduce((n, p) => n + p.anomalies, 0) ?? 0;
  const ended = month < todayIso().slice(0, 7);
  const run = async (action: () => Promise<unknown>) => {
    setError(null);
    try {
      await action();
      await queryClient.invalidateQueries({ queryKey: ["staff"] });
    } catch (e) {
      setError(errorMessage(e));
    }
  };
  const kinds = (Object.keys(absenceLabels) as AbsenceKind[]).filter((k) => data?.people.some((p) => (p.absenceMinutes[k] ?? 0) > 0));

  return (
    <>
      <StaffHeader
        actions={team && data?.validated && (
          <>
            <Button variant="outline" onClick={() => void run(() => staffApi.exportXlsx(month))}><FileSpreadsheet aria-hidden />Exporter en Excel</Button>
            <Button variant="outline" onClick={() => void run(() => staffApi.exportCsv(month))}><FileText aria-hidden />Exporter en CSV</Button>
          </>
        )}
      />
      <div className="flex flex-wrap items-end gap-3">
        <FormField label="Mois">
          <select className={selectClass} value={month} onChange={(e) => { setMonth(e.target.value); setOpen(null); }}>
            {billableMonths().map((m) => <option key={m} value={m}>{formatMonthLong(m)}</option>)}
          </select>
        </FormField>
        {data?.validated && (
          <TonePill tone="success" icon={CircleCheck}>
            Validé le {formatDate(new Date(data.validatedAt!))} à {formatTime(new Date(data.validatedAt!))} par {data.validatedBy}
          </TonePill>
        )}
        {data && !data.validated && team && anomalies > 0 && <TonePill tone="warning">{anomalies} anomalie{anomalies > 1 ? "s" : ""} à corriger</TonePill>}
      </div>
      {error && <Alert>{error}</Alert>}
      {sheet.isError && <Alert>{errorMessage(sheet.error)}</Alert>}

      {data && (
        <section aria-labelledby="heures" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
          <h2 id="heures" className="m-0 text-title-2">Heures de {formatMonthLong(month)}</h2>
          {data.people.length === 0 ? <p className="m-0 text-muted-foreground">Ni planning ni pointage ce mois-ci.</p> : (
            <div className="overflow-x-auto">
              <table className="w-full border-collapse text-left tabular-nums [&_td]:whitespace-nowrap">
                <thead>
                  <tr className="border-b border-border-subtle text-label text-muted-foreground">
                    <th scope="col" className="py-2 pr-3">Personne</th>
                    <th scope="col" className="py-2 pr-3">Contrat</th>
                    <th scope="col" className="py-2 pr-3 text-right">Prévu</th>
                    <th scope="col" className="py-2 pr-3 text-right">Pointé</th>
                    <th scope="col" className="py-2 pr-3 text-right">Au-delà du contrat</th>
                    {kinds.map((k) => <th key={k} scope="col" className="py-2 pr-3 text-right">{absenceLabels[k]}</th>)}
                    <th scope="col" className="py-2">Anomalies</th>
                  </tr>
                </thead>
                <tbody>
                  {data.people.map((p) => (
                    <Fragment key={p.userId}>
                      <tr className="border-b border-border-subtle">
                        <th scope="row" className="py-2 pr-3 text-left font-normal">
                          <button type="button" aria-expanded={open === p.userId} className="text-left font-semibold text-lagon-700"
                            onClick={() => setOpen(open === p.userId ? null : p.userId)}>{p.name}</button>
                          {p.jobTitle && <span className="block text-small text-muted-foreground">{p.jobTitle}</span>}
                        </th>
                        <td className="py-2 pr-3">{contractLabels[p.contractType]} · {weekly(p.weeklyMinutes)}</td>
                        <td className="py-2 pr-3 text-right">{h(p.plannedMinutes)}</td>
                        <td className="py-2 pr-3 text-right">{h(p.workedMinutes)}</td>
                        <td className={cn("py-2 pr-3 text-right", p.overtimeMinutes > 0 && "font-semibold")}>{h(p.overtimeMinutes)}</td>
                        {kinds.map((k) => <td key={k} className="py-2 pr-3 text-right">{h(p.absenceMinutes[k] ?? 0)}</td>)}
                        <td className="py-2">{p.anomalies > 0 ? <TonePill tone="warning">{p.anomalies}</TonePill> : <span className="text-muted-foreground">Aucune</span>}</td>
                      </tr>
                      {open === p.userId && (
                        <tr><td colSpan={6 + kinds.length} className="bg-muted/40 p-3"><PersonDetail person={p} /></td></tr>
                      )}
                    </Fragment>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <p className="m-0 text-small text-muted-foreground">
            Pointé : temps de présence pointé, pause prévue déduite. Les heures au-delà du contrat se comptent par semaine (lundi → dimanche),
            rattachée au mois de son dimanche ; la formation compte comme du temps de travail.
          </p>
          {team && isDirection && !data.validated && (
            <div className="flex flex-wrap items-center gap-3">
              <Button disabled={!ended || anomalies > 0} onClick={() => window.confirm(`Valider les heures de ${formatMonthLong(month)} ? Elles seront figées pour la paie.`) && void run(() => staffApi.validate(month))}>
                <CircleCheck aria-hidden />Valider le mois
              </Button>
              {!ended && <span className="text-small text-muted-foreground">Le mois se valide une fois terminé.</span>}
              {ended && anomalies > 0 && <span className="text-small text-muted-foreground">Corrigez d’abord les anomalies (Présences ou planning).</span>}
            </div>
          )}
          {team && isDirection && data.validated && (
            <Button variant="outline" className="self-start" onClick={() => setReopening(true)}><LockOpen aria-hidden />Rouvrir le mois</Button>
          )}
          {team && !data.validated && <p className="m-0 text-small text-muted-foreground">L’export pour la paie est possible une fois le mois validé par la direction.</p>}
        </section>
      )}
      {reopening && (
        <ReopenDialog month={month} onClose={() => setReopening(false)}
          onSubmit={async (reason) => { await staffApi.reopen(month, reason); await queryClient.invalidateQueries({ queryKey: ["staff"] }); }} />
      )}
    </>
  );
}

function PersonDetail({ person }: { person: PersonSheet }) {
  return (
    <div className="flex flex-col gap-3">
      <table className="w-full border-collapse text-left text-small tabular-nums">
        <caption className="sr-only">Détail jour par jour de {person.name}</caption>
        <thead>
          <tr className="text-muted-foreground">
            <th scope="col" className="py-1 pr-3">Jour</th><th scope="col" className="py-1 pr-3">Pointages</th>
            <th scope="col" className="py-1 pr-3 text-right">Prévu</th><th scope="col" className="py-1 pr-3 text-right">Pointé</th>
            <th scope="col" className="py-1 pr-3">Absence</th><th scope="col" className="py-1">Anomalie</th>
          </tr>
        </thead>
        <tbody>
          {person.days.filter((d) => d.plannedMinutes > 0 || d.workedMinutes > 0 || d.punches.length > 0 || d.absence).map((d) => (
            <tr key={d.day} className="border-t border-border-subtle">
              <td className="py-1 pr-3">{DAY_SHORT[isoWeekday(d.day) - 1]} {isoToDateFr(d.day)}</td>
              <td className="py-1 pr-3">{d.punches.map((p) => `${hhmm(p.arrivedAt)}–${p.departedAt ? hhmm(p.departedAt) : "?"}`).join(", ") || "—"}</td>
              <td className="py-1 pr-3 text-right">{h(d.plannedMinutes)}</td>
              <td className="py-1 pr-3 text-right">{h(d.workedMinutes)}</td>
              <td className="py-1 pr-3">{d.absence ? `${absenceLabels[d.absence]} (${formatMinutes(d.absenceMinutes)})` : "—"}</td>
              <td className="py-1 text-warning-700">{d.anomalies.map((a) => anomalyLabels[a]).join(", ")}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <table className="border-collapse text-left text-small tabular-nums">
        <caption className="mb-1 text-left font-semibold">Par semaine</caption>
        <tbody>
          {person.weeks.map((w) => (
            <tr key={w.monday}>
              <td className="pr-4">Semaine du {isoToDateFr(w.monday)}</td>
              <td className="pr-4">{formatMinutes(w.workedMinutes + w.trainingMinutes)}{w.contractMinutes > 0 && ` pour ${weekly(w.contractMinutes)}`}</td>
              <td className={w.overtimeMinutes > 0 ? "font-semibold" : "text-muted-foreground"}>{w.overtimeMinutes > 0 ? `+ ${formatMinutes(w.overtimeMinutes)}` : "—"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ReopenDialog({ month, onClose, onSubmit }: { month: string; onClose: () => void; onSubmit: (reason: string) => Promise<void> }) {
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Rouvrir les heures de {formatMonthLong(month)}</DialogTitle>
          <DialogDescription>Le planning et les absences du mois redeviennent modifiables ; il faudra valider à nouveau avant l’export. Le motif est conservé.</DialogDescription>
        </DialogHeader>
        <FormField label="Motif"><Input value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} /></FormField>
        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button onClick={async () => {
            if (!reason.trim()) return setError("Indiquez le motif.");
            try {
              await onSubmit(reason.trim());
              onClose();
            } catch (e) {
              setError(errorMessage(e));
            }
          }}>Rouvrir</Button>
          <Button variant="ghost" onClick={onClose}>Annuler</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
