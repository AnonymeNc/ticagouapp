import { useState } from "react";
import { useSearchParams } from "react-router";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronLeft, ChevronRight, History, Plus, ShieldAlert, TriangleAlert } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { DateInput } from "@/components/DateInput";
import { Input } from "@/components/ui/input";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { TonePill } from "@/features/enrollment/components";
import { AbsenceDialog, absencePeriod } from "@/features/billing/AbsenceDialog";
import { billingApi, billingKeys } from "@/features/billing/api";
import { childrenApi } from "@/features/children/api";
import { instructionLabels, type Candidate, type KioskToday } from "@/features/kiosk/api";
import { usersApi } from "@/features/settings/api";
import { useSections } from "@/features/structure/useSections";
import { todayIso } from "@/lib/age";
import { api, errorMessage } from "@/lib/api";
import { formatDate, formatLongDate, formatTime, isoToDateFr, parseDateFr } from "@/lib/datetime";
import { addDays } from "@/lib/format";
import { selectClass } from "@/lib/formFields";
import {
  attendanceApi,
  attendanceKeys,
  fieldLabels,
  sourceLabels,
  toInstant,
  type ChildRow,
  type StaffRow,
  type Target,
} from "./api";

const hm = (iso: string | null) => (iso ? formatTime(new Date(iso)) : "—");

type Editing =
  | { kind: "child"; row: ChildRow }
  | { kind: "staff"; row: StaffRow }
  | { kind: "add-child" }
  | { kind: "add-staff" }
  | { kind: "history"; target: Target; id: string; label: string }
  | { kind: "review"; target: Target; id: string; label: string }
  | { kind: "departure"; row: ChildRow }
  | { kind: "absence" };

/**
 * Présences (poste bureau) : pointages du jour ou d'une date, attendus pas encore arrivés, personnel.
 * La direction corrige, ajoute et vérifie, avec un motif tracé dans un journal en ajout seul.
 */
export function AttendancePage() {
  const user = useCurrentUser();
  const isDirection = user.role === "DIRECTION";
  const canPoint = isDirection || user.role === "EDUCATRICE";
  // « ?date=AAAA-MM-JJ » : ouverture sur un jour précis (anomalie de facturation à corriger).
  const [params] = useSearchParams();
  const initialDay = /^\d{4}-\d{2}-\d{2}$/.test(params.get("date") ?? "") ? params.get("date")! : todayIso();
  const [day, setDay] = useState(initialDay);
  const [dayField, setDayField] = useState(isoToDateFr(initialDay));
  const [editing, setEditing] = useState<Editing | null>(null);
  const [error, setError] = useState<string | null>(null);
  const queryClient = useQueryClient();
  const sections = useSections();
  const view = useQuery({ queryKey: attendanceKeys.day(day), queryFn: ({ signal }) => attendanceApi.day(day, signal) });
  const review = useQuery({
    queryKey: attendanceKeys.review,
    queryFn: ({ signal }) => attendanceApi.review(signal),
    enabled: isDirection,
  });
  const absences = useQuery({
    queryKey: billingKeys.absencesBetween(day, day),
    queryFn: ({ signal }) => billingApi.absencesBetween(day, day, signal),
    enabled: canPoint,
  });
  const isToday = day === todayIso();
  const sectionName = (id: string | null) => sections.data?.find((s) => s.id === id)?.name ?? "—";
  const refresh = () => queryClient.invalidateQueries({ queryKey: attendanceKeys.all });

  const goTo = (next: string) => {
    setDay(next);
    setDayField(isoToDateFr(next));
  };

  const arrive = async (childId: string) => {
    setError(null);
    try {
      await attendanceApi.arrive(childId, {});
      await refresh();
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  const data = view.data;
  const present = data?.children.filter((c) => !c.departedAt).length ?? 0;
  const departed = data?.children.filter((c) => c.departedAt).length ?? 0;
  const toReview = (review.data?.children.length ?? 0) + (review.data?.staff.length ?? 0) + (review.data?.refused.length ?? 0);

  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="m-0 font-display text-display">Présences</h1>
          <p className="mt-1 mb-0 text-muted-foreground">
            {formatLongDate(new Date(`${day}T12:00:00+11:00`))}
            {data && <> · {present} présent{present > 1 ? "s" : ""} sur {data.expected} attendu{data.expected > 1 ? "s" : ""}{departed > 0 && <>, {departed} parti{departed > 1 ? "s" : ""}</>}</>}
          </p>
        </div>
        <div className="flex flex-wrap items-end gap-2">
          <Button variant="outline" size="icon" aria-label="Jour précédent" onClick={() => goTo(addDays(day, -1))}><ChevronLeft aria-hidden /></Button>
          <FormField label="Date">
            <DateInput
              className="w-36"
              value={dayField}
              placeholder="JJ/MM/AAAA"
              onChange={(e) => {
                setDayField(e.target.value);
                const iso = parseDateFr(e.target.value);
                if (iso) setDay(iso);
              }}
            />
          </FormField>
          <Button variant="outline" size="icon" aria-label="Jour suivant" onClick={() => goTo(addDays(day, 1))}><ChevronRight aria-hidden /></Button>
          {!isToday && <Button variant="ghost" onClick={() => goTo(todayIso())}>Aujourd’hui</Button>}
          {canPoint && <Button variant="outline" onClick={() => setEditing({ kind: "absence" })}><Plus aria-hidden />Signaler une absence</Button>}
        </div>
      </div>
      {error && <Alert>{error}</Alert>}
      {view.isError && <Alert>{errorMessage(view.error)}</Alert>}

      {isDirection && toReview > 0 && review.data && (
        <section aria-labelledby="a-verifier" className="flex flex-col gap-3 rounded-lg border border-warning-500 bg-warning-100 p-5">
          <h2 id="a-verifier" className="m-0 flex items-center gap-2 text-title-3 text-warning-700">
            <TriangleAlert aria-hidden className="size-5" />
            À vérifier ({toReview})
          </h2>
          <ul className="m-0 flex list-none flex-col gap-2 p-0">
            {review.data.children.map((r) => (
              <li key={r.id} className="flex flex-wrap items-center gap-3 rounded-md bg-card px-4 py-2">
                <span className="grow">
                  <strong>{r.firstName} {r.lastName}</strong> · {isoToDateFr(r.day)} · arrivée {hm(r.arrivedAt)}, départ {hm(r.departedAt)}
                  <span className="block text-small text-warning-700">{r.reviewReason}</span>
                </span>
                <Button variant="outline" size="sm" onClick={() => setEditing({ kind: "child", row: r })}>Corriger</Button>
                <Button variant="ghost" size="sm" onClick={() => setEditing({ kind: "review", target: "children", id: r.id, label: `${r.firstName} ${r.lastName}` })}>Vérifié</Button>
              </li>
            ))}
            {review.data.staff.map((r) => (
              <li key={r.id} className="flex flex-wrap items-center gap-3 rounded-md bg-card px-4 py-2">
                <span className="grow">
                  <strong>{r.displayName}</strong> · {isoToDateFr(r.day)} · arrivée {hm(r.arrivedAt)}, départ {hm(r.departedAt)}
                  <span className="block text-small text-warning-700">{r.reviewReason}</span>
                </span>
                <Button variant="outline" size="sm" onClick={() => setEditing({ kind: "staff", row: r })}>Corriger</Button>
                <Button variant="ghost" size="sm" onClick={() => setEditing({ kind: "review", target: "staff", id: r.id, label: r.displayName })}>Vérifié</Button>
              </li>
            ))}
            {review.data.refused.map((o) => (
              <li key={o.clientId} className="rounded-md bg-card px-4 py-2">
                <strong>Opération de tablette refusée</strong> · {formatDate(new Date(o.receivedAt))} {hm(o.occurredAt)} : {o.message}
              </li>
            ))}
          </ul>
        </section>
      )}

      <section aria-labelledby="enfants" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 id="enfants" className="m-0 text-title-3">Enfants</h2>
          {isDirection && <Button variant="outline" onClick={() => setEditing({ kind: "add-child" })}><Plus aria-hidden />Ajouter un passage</Button>}
        </div>
        <table className="table-cards w-full border-collapse text-left">
          <caption className="sr-only">Pointages des enfants</caption>
          <thead>
            <tr className="border-b border-border-subtle text-label text-muted-foreground">
              <th scope="col" className="py-2">Enfant</th>
              <th scope="col" className="py-2">Section</th>
              <th scope="col" className="py-2">Arrivée</th>
              <th scope="col" className="py-2">Départ</th>
              <th scope="col" className="py-2">Déposé · récupéré par</th>
              <th scope="col" className="py-2">Consignes</th>
              <th scope="col" className="py-2"><span className="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody className="tabular-nums">
            {data?.children.map((r) => (
              <tr key={r.id} className="border-b border-border-subtle align-top last:border-0">
                <td className="py-2">
                  {r.firstName} {r.lastName}
                  <span className="flex flex-wrap gap-1 pt-1">
                    {r.unplanned && <TonePill tone="info">Hors planning</TonePill>}
                    {r.needsReview && <TonePill tone="warning">À vérifier</TonePill>}
                  </span>
                </td>
                <td className="py-2">{sectionName(r.sectionId)}</td>
                <td className="py-2">{hm(r.arrivedAt)}<span className="block text-small text-muted-foreground">{sourceLabels[r.source]} · {r.arrivalRecordedBy}</span></td>
                <td className="py-2">{r.departedAt ? hm(r.departedAt) : <TonePill tone="success">Présent</TonePill>}</td>
                <td className="py-2">
                  {r.droppedOffBy ?? "—"} · {r.pickedUpByName ?? "—"}
                  {r.override && (
                    <span className="flex items-center gap-1 text-small text-warning-700">
                      <ShieldAlert aria-hidden className="size-3.5" />
                      Hors liste ({r.override.relationship}), validé par {r.override.validatedBy}
                      {!r.override.pinVerified && " — code non vérifié"}
                    </span>
                  )}
                </td>
                <td className="py-2 text-small">
                  {r.instructions.map((i) => (
                    <span key={i.id} className="block">
                      {i.kind === "MEDICAMENT"
                        ? `Médicament à ${i.medicationAt?.slice(0, 5)}${i.givenAt ? `, donné à ${hm(i.givenAt)} par ${i.givenBy}` : ", pas encore donné"}`
                        : i.kind === "AUTRE" ? i.details : instructionLabels[i.kind]}
                    </span>
                  ))}
                </td>
                <td data-cell="action" className="py-2 text-right whitespace-nowrap">
                  {canPoint && isToday && !r.departedAt && (
                    <Button variant="outline" size="sm" onClick={() => setEditing({ kind: "departure", row: r })}>Départ</Button>
                  )}
                  {isDirection && <Button variant="ghost" size="sm" onClick={() => setEditing({ kind: "child", row: r })}>Corriger</Button>}
                  <Button variant="ghost" size="sm" aria-label={`Historique de ${r.firstName} ${r.lastName}`} onClick={() => setEditing({ kind: "history", target: "children", id: r.id, label: `${r.firstName} ${r.lastName}` })}>
                    <History aria-hidden />
                  </Button>
                </td>
              </tr>
            ))}
            {data?.children.length === 0 && (
              <tr><td colSpan={7} className="py-4 text-muted-foreground">Aucun pointage ce jour-là.</td></tr>
            )}
          </tbody>
        </table>
        {data && data.notArrived.length > 0 && (
          <div className="flex flex-col gap-2">
            <h3 className="m-0 text-title-3">Pas encore arrivés</h3>
            <ul className="m-0 flex list-none flex-wrap gap-2 p-0">
              {data.notArrived.map((e) => (
                <li key={e.childId} className="flex items-center gap-2 rounded-md border border-border-subtle px-3 py-2">
                  {e.firstName} {e.lastName} · prévu à {e.expectedAt.slice(0, 5)}
                  {e.late && <TonePill tone="warning">En retard</TonePill>}
                  {canPoint && isToday && <Button size="sm" variant="outline" onClick={() => void arrive(e.childId)}>Arrivée</Button>}
                </li>
              ))}
            </ul>
          </div>
        )}
        {absences.data && absences.data.length > 0 && (
          <div className="flex flex-col gap-2">
            <h3 className="m-0 text-title-3">Absences signalées</h3>
            <ul className="m-0 flex list-none flex-wrap gap-2 p-0">
              {absences.data.map((a) => (
                <li key={a.id} className="flex items-center gap-2 rounded-md border border-border-subtle px-3 py-2">
                  {a.childName} · {a.reason.toLowerCase()} {absencePeriod(a)}
                  {a.proofRequired && !a.documentId && <TonePill tone="warning">Justificatif à joindre</TonePill>}
                </li>
              ))}
            </ul>
          </div>
        )}
      </section>

      <section aria-labelledby="personnel" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 id="personnel" className="m-0 text-title-3">Personnel</h2>
          {isDirection && <Button variant="outline" onClick={() => setEditing({ kind: "add-staff" })}><Plus aria-hidden />Ajouter un pointage</Button>}
        </div>
        <table className="table-cards w-full border-collapse text-left">
          <caption className="sr-only">Pointages du personnel</caption>
          <thead>
            <tr className="border-b border-border-subtle text-label text-muted-foreground">
              <th scope="col" className="py-2">Nom</th>
              <th scope="col" className="py-2">Section</th>
              <th scope="col" className="py-2">Arrivée</th>
              <th scope="col" className="py-2">Départ</th>
              <th scope="col" className="py-2">Pointage</th>
              <th scope="col" className="py-2"><span className="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody className="tabular-nums">
            {data?.staff.map((r) => (
              <tr key={r.id} className="border-b border-border-subtle last:border-0">
                <td className="py-2">
                  {r.displayName}
                  <span className="block text-small text-muted-foreground">{r.jobTitle ?? ""}{r.countsInRatio ? "" : " · hors encadrement"}</span>
                </td>
                <td className="py-2">{sectionName(r.sectionId)}</td>
                <td className="py-2">{hm(r.arrivedAt)}</td>
                <td className="py-2">{r.departedAt ? hm(r.departedAt) : <TonePill tone="success">Présent</TonePill>}</td>
                <td className="py-2">
                  {sourceLabels[r.source]}
                  {!r.pinVerified && <TonePill tone="warning">Code non vérifié</TonePill>}
                </td>
                <td data-cell="action" className="py-2 text-right whitespace-nowrap">
                  {isDirection && <Button variant="ghost" size="sm" onClick={() => setEditing({ kind: "staff", row: r })}>Corriger</Button>}
                  <Button variant="ghost" size="sm" aria-label={`Historique de ${r.displayName}`} onClick={() => setEditing({ kind: "history", target: "staff", id: r.id, label: r.displayName })}>
                    <History aria-hidden />
                  </Button>
                </td>
              </tr>
            ))}
            {data?.staff.length === 0 && <tr><td colSpan={6} className="py-4 text-muted-foreground">Personne n’a pointé ce jour-là.</td></tr>}
          </tbody>
        </table>
        {data && data.staffNotArrived.length > 0 && isToday && (
          <p className="m-0 text-muted-foreground">
            Pas encore pointé : {data.staffNotArrived.map((s) => s.displayName).join(", ")}.
          </p>
        )}
      </section>

      {editing?.kind === "child" && (
        <CorrectionDialog
          title={`Corriger le passage de ${editing.row.firstName} ${editing.row.lastName}`}
          day={editing.row.day}
          arrivedAt={editing.row.arrivedAt}
          departedAt={editing.row.departedAt}
          sectionId={editing.row.sectionId}
          askPickup={!editing.row.departedAt}
          onClose={() => setEditing(null)}
          onSubmit={async (body) => {
            await attendanceApi.correctChild(editing.row.id, body);
            await refresh();
          }}
        />
      )}
      {editing?.kind === "staff" && (
        <CorrectionDialog
          title={`Corriger le pointage de ${editing.row.displayName}`}
          day={editing.row.day}
          arrivedAt={editing.row.arrivedAt}
          departedAt={editing.row.departedAt}
          sectionId={editing.row.sectionId}
          askPickup={false}
          onClose={() => setEditing(null)}
          onSubmit={async ({ pickedUpBy: _ignored, ...body }) => {
            await attendanceApi.correctStaff(editing.row.id, body);
            await refresh();
          }}
        />
      )}
      {(editing?.kind === "add-child" || editing?.kind === "add-staff") && (
        <AdditionDialog kind={editing.kind} day={day} onClose={() => setEditing(null)} onDone={refresh} />
      )}
      {editing?.kind === "history" && <HistoryDialog target={editing.target} id={editing.id} label={editing.label} onClose={() => setEditing(null)} />}
      {editing?.kind === "review" && (
        <ReviewDialog
          label={editing.label}
          onClose={() => setEditing(null)}
          onSubmit={async (note) => {
            await attendanceApi.markReviewed(editing.target, editing.id, note);
            await refresh();
          }}
        />
      )}
      {editing?.kind === "absence" && (
        <AbsenceForDay day={day} onClose={() => setEditing(null)}
          onDone={() => { setEditing(null); void queryClient.invalidateQueries({ queryKey: ["billing"] }); }} />
      )}
      {editing?.kind === "departure" && (
        <DepartureDialog row={editing.row} isDirection={isDirection} onClose={() => setEditing(null)} onDone={refresh} />
      )}
    </>
  );
}

type CorrectionBody = { arrivedAt: string; departedAt: string | null; sectionId: string | null; pickedUpBy: string | null; reason: string };

function CorrectionDialog(props: {
  title: string;
  day: string;
  arrivedAt: string;
  departedAt: string | null;
  sectionId: string | null;
  askPickup: boolean;
  onClose: () => void;
  onSubmit: (body: CorrectionBody) => Promise<void>;
}) {
  const sections = useSections();
  const [arrival, setArrival] = useState(hm(props.arrivedAt));
  const [departure, setDeparture] = useState(props.departedAt ? hm(props.departedAt) : "");
  const [sectionId, setSectionId] = useState(props.sectionId ?? "");
  const [pickedUpBy, setPickedUpBy] = useState("");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    if (!/^\d{2}:\d{2}$/.test(arrival) || (departure && !/^\d{2}:\d{2}$/.test(departure))) return setError("Heures attendues au format HH:MM.");
    if (!reason.trim()) return setError("Indiquez le motif de la correction : il est conservé dans le journal.");
    try {
      await props.onSubmit({
        arrivedAt: toInstant(props.day, arrival),
        departedAt: departure ? toInstant(props.day, departure) : null,
        sectionId: sectionId || null,
        pickedUpBy: pickedUpBy.trim() || null,
        reason: reason.trim(),
      });
      props.onClose();
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && props.onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{props.title}</DialogTitle>
          <DialogDescription>{isoToDateFr(props.day)} · la correction et son motif sont conservés dans le journal.</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <FormField label="Arrivée"><Input type="time" value={arrival} onChange={(e) => setArrival(e.target.value)} /></FormField>
          <FormField label="Départ" hint="Vide : toujours présent."><Input type="time" value={departure} onChange={(e) => setDeparture(e.target.value)} /></FormField>
          <FormField label="Section">
            <select className={selectClass} value={sectionId} onChange={(e) => setSectionId(e.target.value)}>
              <option value="">Sans section</option>
              {sections.data?.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </FormField>
          {props.askPickup && departure && (
            <FormField label="Récupéré par"><Input value={pickedUpBy} maxLength={170} onChange={(e) => setPickedUpBy(e.target.value)} /></FormField>
          )}
          <FormField label="Motif" className="sm:col-span-2"><Input value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} /></FormField>
        </div>
        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button onClick={() => void submit()}>Enregistrer la correction</Button>
          <Button variant="ghost" onClick={props.onClose}>Annuler</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function AdditionDialog({ kind, day, onClose, onDone }: { kind: "add-child" | "add-staff"; day: string; onClose: () => void; onDone: () => Promise<unknown> }) {
  const sections = useSections();
  const children = useQuery({ queryKey: ["children", {}], queryFn: ({ signal }) => childrenApi.list({}, signal), enabled: kind === "add-child" });
  const users = useQuery({
    queryKey: ["users"],
    queryFn: ({ signal }) => usersApi.list(signal),
    enabled: kind === "add-staff",
  });
  const [who, setWho] = useState("");
  const [arrival, setArrival] = useState("");
  const [departure, setDeparture] = useState("");
  const [sectionId, setSectionId] = useState("");
  const [pickedUpBy, setPickedUpBy] = useState("");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    if (!who) return setError(kind === "add-child" ? "Choisissez l’enfant." : "Choisissez la personne.");
    if (!/^\d{2}:\d{2}$/.test(arrival)) return setError("Indiquez l’heure d’arrivée (HH:MM).");
    if (!reason.trim()) return setError("Indiquez le motif : il est conservé dans le journal.");
    const common = {
      arrivedAt: toInstant(day, arrival),
      departedAt: departure ? toInstant(day, departure) : null,
      sectionId: sectionId || null,
      reason: reason.trim(),
    };
    try {
      if (kind === "add-child") await attendanceApi.addChild({ ...common, childId: who, pickedUpBy: pickedUpBy.trim() || null });
      else await attendanceApi.addStaff({ ...common, userId: who });
      await onDone();
      onClose();
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{kind === "add-child" ? "Ajouter un passage" : "Ajouter un pointage du personnel"}</DialogTitle>
          <DialogDescription>{isoToDateFr(day)} · pointage oublié ou tablette en panne. Le motif est conservé dans le journal.</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <FormField label={kind === "add-child" ? "Enfant" : "Personne"} className="sm:col-span-2">
            <select className={selectClass} value={who} onChange={(e) => setWho(e.target.value)}>
              <option value="">Choisir…</option>
              {kind === "add-child"
                ? children.data?.map((c) => <option key={c.id} value={c.id}>{c.firstName} {c.lastName}</option>)
                : users.data?.filter((u) => u.active).map((u) => <option key={u.id} value={u.id}>{u.firstName} {u.lastName}</option>)}
            </select>
          </FormField>
          <FormField label="Arrivée"><Input type="time" value={arrival} onChange={(e) => setArrival(e.target.value)} /></FormField>
          <FormField label="Départ (facultatif)"><Input type="time" value={departure} onChange={(e) => setDeparture(e.target.value)} /></FormField>
          <FormField label="Section">
            <select className={selectClass} value={sectionId} onChange={(e) => setSectionId(e.target.value)}>
              <option value="">{kind === "add-child" ? "Section de l’enfant" : "Section habituelle"}</option>
              {sections.data?.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </FormField>
          {kind === "add-child" && departure && (
            <FormField label="Récupéré par"><Input value={pickedUpBy} maxLength={170} onChange={(e) => setPickedUpBy(e.target.value)} /></FormField>
          )}
          <FormField label="Motif" className="sm:col-span-2"><Input value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} /></FormField>
        </div>
        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button onClick={() => void submit()}>Ajouter</Button>
          <Button variant="ghost" onClick={onClose}>Annuler</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function HistoryDialog({ target, id, label, onClose }: { target: Target; id: string; label: string; onClose: () => void }) {
  const history = useQuery({ queryKey: ["attendance", "history", id], queryFn: ({ signal }) => attendanceApi.corrections(target, id, signal) });
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Historique · {label}</DialogTitle>
          <DialogDescription>Corrections de la direction, dans l’ordre ; le journal ne peut pas être modifié.</DialogDescription>
        </DialogHeader>
        {history.data?.length === 0 && <p className="m-0 text-muted-foreground">Aucune correction.</p>}
        <ol className="m-0 flex list-none flex-col gap-2 p-0">
          {history.data?.map((c, i) => (
            <li key={i} className="rounded-md border border-border-subtle px-3 py-2">
              <strong>{fieldLabels[c.field] ?? c.field}</strong> : {c.oldValue ?? "—"} → {c.newValue ?? "—"}
              <span className="block text-small text-muted-foreground">
                {formatDate(new Date(c.createdAt))} {hm(c.createdAt)} · {c.author} · « {c.reason} »
              </span>
            </li>
          ))}
        </ol>
        <Button variant="ghost" onClick={onClose} className="self-start">Fermer</Button>
      </DialogContent>
    </Dialog>
  );
}

function ReviewDialog({ label, onClose, onSubmit }: { label: string; onClose: () => void; onSubmit: (note: string) => Promise<void> }) {
  const [note, setNote] = useState("");
  const [error, setError] = useState<string | null>(null);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Pointage vérifié · {label}</DialogTitle>
          <DialogDescription>Indiquez ce que vous avez vérifié ; la note est conservée dans le journal.</DialogDescription>
        </DialogHeader>
        <FormField label="Note"><Input value={note} maxLength={300} onChange={(e) => setNote(e.target.value)} /></FormField>
        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button
            onClick={async () => {
              if (!note.trim()) return setError("Indiquez la vérification faite.");
              try {
                await onSubmit(note.trim());
                onClose();
              } catch (e) {
                setError(errorMessage(e));
              }
            }}
          >
            Marquer comme vérifié
          </Button>
          <Button variant="ghost" onClick={onClose}>Annuler</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

/** Départ pointé au bureau : une personne de la liste ; une autre personne seulement par la direction, avec motif. */
function DepartureDialog({ row, isDirection, onClose, onDone }: { row: ChildRow; isDirection: boolean; onClose: () => void; onDone: () => Promise<unknown> }) {
  const today = useQuery({ queryKey: ["kiosk-today", "desk"], queryFn: ({ signal }) => kioskApiForDesk(signal) });
  const pickup: Candidate[] = today.data?.children.find((c) => c.id === row.childId)?.pickup ?? [];
  const [chosen, setChosen] = useState<string>("");
  const [name, setName] = useState("");
  const [relationship, setRelationship] = useState("");
  const [idChecked, setIdChecked] = useState(false);
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    setError(null);
    try {
      if (chosen === "autre") {
        if (!name.trim() || !relationship.trim() || !reason.trim()) return setError("Indiquez la personne, son lien et le motif de votre validation.");
        await attendanceApi.depart(row.id, { other: { personName: name.trim(), relationship: relationship.trim(), idChecked, reason: reason.trim() } });
      } else {
        const person = pickup.find((p) => p.id === chosen);
        if (!person) return setError("Choisissez qui vient chercher l’enfant.");
        await attendanceApi.depart(row.id, { pickup: { kind: person.kind, personId: person.id } });
      }
      await onDone();
      onClose();
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Départ de {row.firstName} {row.lastName}</DialogTitle>
          <DialogDescription>Présent depuis {hm(row.arrivedAt)}. Qui vient le chercher ?</DialogDescription>
        </DialogHeader>
        <div role="radiogroup" aria-label="Personne qui vient chercher l’enfant" className="flex flex-col gap-2">
          {pickup.map((p) => (
            <label key={p.id} className="flex items-center gap-3 rounded-md border border-border-subtle px-3 py-2">
              <input type="radio" name="pickup" checked={chosen === p.id} onChange={() => setChosen(p.id)} />
              {p.name} <span className="text-muted-foreground">{p.relationship}</span>
            </label>
          ))}
          <label className="flex items-center gap-3 rounded-md border border-dashed border-border px-3 py-2">
            <input type="radio" name="pickup" checked={chosen === "autre"} onChange={() => setChosen("autre")} />
            Une autre personne <span className="text-muted-foreground">absente de la liste</span>
          </label>
        </div>
        {chosen === "autre" &&
          (isDirection ? (
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <FormField label="Prénom et nom"><Input value={name} maxLength={170} onChange={(e) => setName(e.target.value)} /></FormField>
              <FormField label="Lien avec l’enfant"><Input value={relationship} maxLength={120} onChange={(e) => setRelationship(e.target.value)} /></FormField>
              <label className="flex items-center gap-2 sm:col-span-2">
                <input type="checkbox" checked={idChecked} onChange={(e) => setIdChecked(e.target.checked)} />
                Pièce d’identité présentée et vérifiée
              </label>
              <FormField label="Motif de votre validation" className="sm:col-span-2"><Input value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} /></FormField>
            </div>
          ) : (
            <Alert>Cette personne ne figure pas sur la liste : le départ est bloqué tant que la direction ne l’a pas validé.</Alert>
          ))}
        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button onClick={() => void submit()} disabled={!chosen || (chosen === "autre" && !isDirection)}>Valider le départ</Button>
          <Button variant="ghost" onClick={onClose}>Annuler</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

/** Liste des personnes autorisées : instantané du jour, lu avec la session du bureau. */
function kioskApiForDesk(signal?: AbortSignal) {
  return api<KioskToday>("/kiosk/today", { signal });
}

/** Signaler une absence depuis Présences : l'enfant est choisi parmi les inscrits. */
function AbsenceForDay({ day, onClose, onDone }: { day: string; onClose: () => void; onDone: () => void }) {
  const children = useQuery({ queryKey: ["children", {}], queryFn: ({ signal }) => childrenApi.list({}, signal) });
  if (!children.data) return null;
  return (
    <AbsenceDialog
      defaultDay={day}
      children={children.data.map((c) => ({ id: c.id, name: `${c.firstName} ${c.lastName}` }))}
      onClose={onClose}
      onSaved={onDone}
    />
  );
}
