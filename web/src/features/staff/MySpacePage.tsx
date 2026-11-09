import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { CalendarDays, Copy, NotebookPen, Send } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { TonePill, type Tone } from "@/features/enrollment/components";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { errorMessage } from "@/lib/api";
import { formatDate, formatTime, isoToDateFr, parseDateFr } from "@/lib/datetime";
import { formatMinutes } from "@/lib/format";
import { selectClass } from "@/lib/formFields";
import { StaffHeader } from "./components";
import { kindLabels, spaceApi, spaceKeys, type RequestKind, type StaffRequest } from "./space";

const statusTones: Record<StaffRequest["status"], { tone: Tone; label: string }> = {
  DEMANDEE: { tone: "info", label: "En attente" },
  ACCEPTEE: { tone: "success", label: "Acceptée" },
  REFUSEE: { tone: "danger", label: "Refusée" },
  ANNULEE: { tone: "neutral", label: "Annulée" },
};

const period = (r: StaffRequest) => r.kind === "HEURES_SUP"
  ? `${isoToDateFr(r.fromDay)} · ${formatMinutes(r.minutes ?? 0)}`
  : r.fromDay === r.toDay ? isoToDateFr(r.fromDay) : `du ${isoToDateFr(r.fromDay)} au ${isoToDateFr(r.toDay)}`;

/**
 * Mon espace (lot 4) : compteurs de l'année, demandes de congé, de récupération et d'heures supplémentaires,
 * cahier d'équipe, agenda à brancher sur son téléphone. La direction y répond aux demandes en attente. Écart : pas de
 * maquette dédiée ; reprend l'en-tête du module Personnel.
 */
export function MySpacePage() {
  const user = useCurrentUser();
  const isDirection = user.role === "DIRECTION";
  const queryClient = useQueryClient();
  const mine = useQuery({ queryKey: spaceKeys.mine, queryFn: ({ signal }) => spaceApi.mine(signal) });
  const counters = useQuery({ queryKey: spaceKeys.counters, queryFn: ({ signal }) => spaceApi.counters(signal) });
  const pending = useQuery({ queryKey: spaceKeys.pending, queryFn: ({ signal }) => spaceApi.pending(signal), enabled: isDirection });
  const showNotes = user.role !== "COMPTABILITE";
  const notes = useQuery({ queryKey: spaceKeys.notes, queryFn: ({ signal }) => spaceApi.notes(signal), enabled: showNotes });
  const calendar = useQuery({ queryKey: spaceKeys.calendar, queryFn: ({ signal }) => spaceApi.calendar(signal) });
  const [form, setForm] = useState({ kind: "CONGES" as RequestKind, from: "", to: "", minutes: "", note: "" });
  const [reasons, setReasons] = useState<Record<string, string>>({});
  const [note, setNote] = useState("");
  const [calendarUrl, setCalendarUrl] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const run = async (action: () => Promise<unknown>, done?: string) => {
    setError(null);
    setMessage(null);
    try {
      await action();
      await queryClient.invalidateQueries({ queryKey: ["staff-space"] });
      if (done) setMessage(done);
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  const send = () => {
    const from = parseDateFr(form.from);
    const to = form.kind === "HEURES_SUP" ? from : parseDateFr(form.to || form.from);
    if (!from || !to) return setError("Saisissez les dates au format JJ/MM/AAAA.");
    const [h, m] = form.minutes.split(":").map(Number);
    const minutes = form.kind === "HEURES_SUP" ? (h || 0) * 60 + (m || 0) : null;
    void run(async () => {
      await spaceApi.request({ kind: form.kind, fromDay: from, toDay: to, minutes, note: form.note.trim() || null });
      setForm({ ...form, from: "", to: "", minutes: "", note: "" });
    }, "Demande envoyée à la direction.");
  };

  return (
    <>
      <StaffHeader subtitle="Vos demandes, vos compteurs et le cahier de l’équipe." />
      {message && <p role="status" className="m-0 rounded-md bg-success-100 px-3 py-2 text-success-700">{message}</p>}
      {error && <Alert>{error}</Alert>}

      {isDirection && (pending.data ?? []).length > 0 && (
        <section aria-labelledby="a-traiter" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
          <h2 id="a-traiter" className="m-0 text-title-3">Demandes du personnel à traiter</h2>
          <ul className="m-0 flex list-none flex-col gap-2 p-0">
            {pending.data!.map((r) => (
              <li key={r.id} className="flex flex-wrap items-center gap-3 border-b border-border-subtle py-2">
                <span className="grow"><strong className="font-semibold">{r.userName}</strong> · {kindLabels[r.kind]} {period(r)}
                  {r.note && <span className="text-muted-foreground"> · « {r.note} »</span>}</span>
                <Input aria-label={`Motif pour ${r.userName}`} placeholder="Motif du refus" className="w-56" value={reasons[r.id] ?? ""}
                  onChange={(e) => setReasons({ ...reasons, [r.id]: e.target.value })} />
                <Button size="sm" onClick={() => void run(async () => {
                  const result = await spaceApi.accept(r.id);
                  setMessage(result.shortageDays.length > 0
                    ? `Acceptée. Attention : encadrement insuffisant prévu le ${result.shortageDays.map(isoToDateFr).join(", ")}.`
                    : "Acceptée : le planning est à jour.");
                })}>Accepter</Button>
                <Button size="sm" variant="ghost" disabled={!reasons[r.id]?.trim()}
                  onClick={() => void run(() => spaceApi.refuse(r.id, reasons[r.id]), "Demande refusée.")}>Refuser</Button>
              </li>
            ))}
          </ul>
        </section>
      )}

      <div className="grid gap-4 lg:grid-cols-2">
        <section aria-labelledby="compteurs" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
          <h2 id="compteurs" className="m-0 text-title-3">Mes compteurs {counters.data?.year}</h2>
          {counters.data && (
            <dl className="m-0 grid grid-cols-2 gap-3">
              <div><dt className="text-small text-muted-foreground">Congés pris</dt><dd className="m-0 text-title-2 tabular-nums">{String(counters.data.leaveDaysTaken).replace(".", ",")} j</dd></div>
              <div><dt className="text-small text-muted-foreground">Heures sup. acceptées</dt><dd className="m-0 text-title-2 tabular-nums">{formatMinutes(counters.data.overtimeMinutes)}</dd></div>
              <div><dt className="text-small text-muted-foreground">Heures récupérées</dt><dd className="m-0 text-title-2 tabular-nums">{formatMinutes(counters.data.recoveredMinutes)}</dd></div>
              <div><dt className="text-small text-muted-foreground">Reste à récupérer</dt><dd className="m-0 text-title-2 tabular-nums">{formatMinutes(counters.data.balanceMinutes)}</dd></div>
            </dl>
          )}
        </section>

        <section aria-labelledby="nouvelle-demande" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
          <h2 id="nouvelle-demande" className="m-0 text-title-3">Nouvelle demande</h2>
          <FormField label="Type">
            <select className={selectClass} value={form.kind} onChange={(e) => setForm({ ...form, kind: e.target.value as RequestKind })}>
              {(Object.keys(kindLabels) as RequestKind[]).map((k) => <option key={k} value={k}>{kindLabels[k]}</option>)}
            </select>
          </FormField>
          <div className="grid grid-cols-2 gap-3">
            <FormField label={form.kind === "HEURES_SUP" ? "Jour" : "Du"} hint="JJ/MM/AAAA">
              <Input value={form.from} inputMode="numeric" onChange={(e) => setForm({ ...form, from: e.target.value })} />
            </FormField>
            {form.kind === "HEURES_SUP" ? (
              <FormField label="Durée" hint="HH:MM"><Input type="time" value={form.minutes} onChange={(e) => setForm({ ...form, minutes: e.target.value })} /></FormField>
            ) : (
              <FormField label="Au" hint="Vide : un seul jour"><Input value={form.to} inputMode="numeric" onChange={(e) => setForm({ ...form, to: e.target.value })} /></FormField>
            )}
          </div>
          <FormField label="Précision (facultatif)"><Input value={form.note} maxLength={300} onChange={(e) => setForm({ ...form, note: e.target.value })} /></FormField>
          <Button className="self-start" disabled={!form.from} onClick={send}><Send aria-hidden />Envoyer à la direction</Button>
        </section>
      </div>

      <section aria-labelledby="mes-demandes" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <h2 id="mes-demandes" className="m-0 text-title-3">Mes demandes</h2>
        {(mine.data ?? []).length === 0 ? <p className="m-0 text-muted-foreground">Aucune demande pour le moment.</p> : (
          <ul className="m-0 flex list-none flex-col gap-2 p-0">
            {mine.data!.map((r) => (
              <li key={r.id} className="flex flex-wrap items-center gap-3 border-b border-border-subtle py-2">
                <span className="grow">{kindLabels[r.kind]} {period(r)}{r.answer && <span className="text-muted-foreground"> · {r.answer}</span>}</span>
                <TonePill tone={statusTones[r.status].tone}>{statusTones[r.status].label}</TonePill>
                {r.status === "DEMANDEE" && <Button size="sm" variant="ghost" onClick={() => void run(() => spaceApi.cancel(r.id), "Demande annulée.")}>Annuler</Button>}
              </li>
            ))}
          </ul>
        )}
      </section>

      {showNotes && (
        <section aria-labelledby="cahier" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
          <h2 id="cahier" className="m-0 flex items-center gap-2 text-title-3"><NotebookPen aria-hidden className="size-5" />Cahier d’équipe</h2>
          <form className="flex items-end gap-2" onSubmit={(e) => { e.preventDefault(); void run(async () => { await spaceApi.writeNote(note); setNote(""); }); }}>
            <Textarea aria-label="Note pour l’équipe" rows={2} maxLength={1000} value={note} onChange={(e) => setNote(e.target.value)}
              placeholder="Une information pour toute l’équipe…" className="grow" />
            <Button type="submit" disabled={!note.trim()}>Publier</Button>
          </form>
          <ul className="m-0 flex list-none flex-col gap-2 p-0">
            {(notes.data ?? []).map((n) => (
              <li key={n.id} className={n.read ? "rounded-md bg-muted px-3 py-2" : "rounded-md border-2 border-lagon-600 px-3 py-2"}>
                <p className="m-0 whitespace-pre-wrap">{n.body}</p>
                <p className="m-0 flex flex-wrap items-center gap-2 text-small text-muted-foreground">
                  {n.authorName} · {formatDate(new Date(n.createdAt))} {formatTime(new Date(n.createdAt))} · lu par {n.readers}
                  {!n.read && <Button size="sm" variant="ghost" onClick={() => void run(() => spaceApi.readNote(n.id))}>J’ai lu</Button>}
                </p>
              </li>
            ))}
          </ul>
        </section>
      )}

      <section aria-labelledby="agenda" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <h2 id="agenda" className="m-0 flex items-center gap-2 text-title-3"><CalendarDays aria-hidden className="size-5" />Mon planning dans mon agenda</h2>
        <p className="m-0 max-w-3xl text-muted-foreground">
          Un lien privé à ajouter à l’agenda de votre téléphone (Google Agenda, Calendrier d’Apple, Outlook). Il montre vos
          horaires, sans rien d’autre. Ne le partagez pas ; en cas de doute, créez-en un nouveau : l’ancien cesse de marcher.
        </p>
        {calendarUrl && (
          <div className="flex items-center gap-2">
            <code className="grow truncate rounded-md bg-muted px-2 py-1 text-small">{calendarUrl}</code>
            <Button size="sm" variant="ghost" aria-label="Copier le lien" onClick={() => void navigator.clipboard?.writeText(calendarUrl)}><Copy aria-hidden /></Button>
          </div>
        )}
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" onClick={() => void run(async () => {
            const { token } = await spaceApi.newCalendar();
            setCalendarUrl(`${window.location.origin}/api/v1/public/calendar/${token}.ics`);
          }, "Lien créé : copiez-le maintenant, il ne sera plus affiché.")}>
            {calendar.data?.active ? "Créer un nouveau lien" : "Créer mon lien"}
          </Button>
          {calendar.data?.active && <Button variant="ghost" onClick={() => void run(async () => { await spaceApi.revokeCalendar(); setCalendarUrl(null); }, "Lien révoqué.")}>Révoquer le lien</Button>}
        </div>
      </section>
    </>
  );
}
