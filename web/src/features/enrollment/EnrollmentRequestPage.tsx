import { useState } from "react";
import { Link, useParams } from "react-router";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowRight, CalendarDays, Check, Clock, FileSignature, RotateCcw } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { SectionBadge } from "@/features/children/components";
import { useSections } from "@/features/structure/useSections";
import { errorMessage } from "@/lib/api";
import { formatDate, formatTime, isoToDateFr } from "@/lib/datetime";
import { DAY_SHORT, formatMonthLong, formatMonthShort, formatVisit } from "@/lib/format";
import { cn } from "@/lib/utils";
import { STAGES, enrollmentApi, enrollmentKeys, isClosed, stageLabels, type CapacityGrid, type RequestRecord } from "./api";
import { StagePill, TonePill, capacityHint } from "./components";
import { MoveVisitDialog, StageDialog } from "./RequestDialogs";
import { CriteriaFields, RequestFormSections, toBody, useRequestForm } from "./RequestForm";

/** Fiche demande (maquette Inscriptions › Fiche demande). */
export function EnrollmentRequestPage() {
  const { id = "" } = useParams();
  const record = useQuery({ queryKey: enrollmentKeys.record(id), queryFn: ({ signal }) => enrollmentApi.record(id, signal) });

  if (record.isPending) return <p className="text-muted-foreground">Chargement de la demande…</p>;
  if (record.isError) return <p className="text-danger-700">{errorMessage(record.error)}</p>;
  // La clé force un formulaire neuf quand la demande change (après enregistrement).
  return <RequestView key={record.data.version} record={record.data} />;
}

function RequestView({ record }: { record: RequestRecord }) {
  const user = useCurrentUser();
  const canEdit = user.role === "DIRECTION";
  const queryClient = useQueryClient();
  const sections = useSections();
  const section = sections.data?.find((s) => s.id === record.sectionId);
  const form = useRequestForm(record);
  const [dialog, setDialog] = useState<"stage" | "refuse" | "visit" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const closed = isClosed(record.stage);
  const editable = canEdit && record.stage !== "SIGNEE";

  const refresh = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: enrollmentKeys.record(record.id) }),
      queryClient.invalidateQueries({ queryKey: ["enrollment-board"] }),
      queryClient.invalidateQueries({ queryKey: enrollmentKeys.capacity }),
    ]);

  const save = form.handleSubmit(async (values) => {
    setError(null);
    try {
      await enrollmentApi.update(record.id, toBody(values));
      await refresh();
    } catch (e) {
      setError(errorMessage(e));
    }
  });

  const visitDone = async () => {
    try {
      await enrollmentApi.visitDone(record.id);
      await refresh();
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <>
      <nav aria-label="Fil d’Ariane" className="text-label text-muted-foreground">
        <Link to="/inscriptions" className="text-lagon-700">Inscriptions</Link> › {record.childFirstName} {record.childLastName}
      </nav>

      <section aria-label="Résumé de la demande" className="flex flex-wrap items-center justify-between gap-4 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <div className="flex flex-col gap-2">
          <h1 className="m-0 font-display text-title-1">{record.childFirstName} {record.childLastName}</h1>
          <div className="flex flex-wrap items-center gap-2">
            <SectionBadge section={section} />
            <StagePill stage={record.stage} />
            <span className="text-muted-foreground tabular-nums">Demande reçue le {isoToDateFr(record.receivedOn)}</span>
          </div>
        </div>
        {canEdit && (
          <div className="flex flex-wrap gap-2">
            {record.stage === "REFUSEE" ? (
              <Button variant="outline" onClick={() => setDialog("stage")}>
                <RotateCcw aria-hidden />
                Rouvrir la demande
              </Button>
            ) : (
              !closed && (
                <Button variant="outline" onClick={() => setDialog("stage")}>Changer d’étape</Button>
              )
            )}
            {!closed && record.contracts.length === 0 && (
              <Button asChild>
                <Link to={`/inscriptions/${record.id}/contrat`}>
                  <FileSignature aria-hidden />
                  Transformer en contrat
                </Link>
              </Button>
            )}
            {record.contracts.length > 0 && record.childId && (
              <Button asChild variant={closed ? "outline" : "default"}>
                <Link to={`/enfants/${record.childId}?onglet=contrat`}>
                  Contrat n° {record.contracts[0].number}
                  <ArrowRight aria-hidden />
                </Link>
              </Button>
            )}
          </div>
        )}
      </section>

      <Stepper record={record} />

      {record.stage === "REFUSEE" && (
        <p role="status" className="m-0 rounded-md bg-danger-100 px-4 py-3 text-danger-700">
          Refusée le {isoToDateFr(record.stageChangedOn)} : {record.refusalReason}
        </p>
      )}

      <div className="grid grid-cols-1 items-start gap-4 xl:grid-cols-[minmax(0,1fr)_360px]">
        <form onSubmit={save} noValidate className="flex min-w-0 flex-col gap-4">
          <RequestFormSections form={form} sections={sections.data ?? []} disabled={!editable} familyLabel={record.familyLabel} />
          {record.siblings.length > 0 && <Siblings record={record} />}
          <CriteriaFields form={form} criteria={record.availableCriteria} disabled={!editable} ranking={record.ranking} />
          {error && <Alert>{error}</Alert>}
          {editable && form.formState.isDirty && (
            <div className="sticky bottom-4 flex items-center gap-3 rounded-lg border border-border-subtle bg-card p-3 shadow-2">
              <Button type="submit" disabled={form.formState.isSubmitting}>
                {form.formState.isSubmitting ? "Enregistrement…" : "Enregistrer les modifications"}
              </Button>
              <Button type="button" variant="ghost" onClick={() => form.reset()}>Annuler</Button>
            </div>
          )}
          <Notes record={record} canEdit={canEdit} onAdded={refresh} />
        </form>

        <aside className="flex flex-col gap-4">
          {!closed && <FreePlaces record={record} />}
          {record.visitAt && (
            <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
              <h2 className="m-0 text-title-3">Visite</h2>
              <p className="m-0 flex items-center gap-2 tabular-nums">
                <CalendarDays aria-hidden className="size-4 text-lagon-700" />
                {formatVisit(record.visitAt)}
                {record.visitDone && <TonePill tone="success">Faite</TonePill>}
              </p>
              {canEdit && record.stage === "VISITE_PREVUE" && !record.visitDone && (
                <div className="flex flex-wrap gap-2">
                  <Button variant="outline" size="sm" onClick={() => setDialog("visit")}>Déplacer la visite</Button>
                  <Button size="sm" onClick={() => void visitDone()}>
                    <Check aria-hidden />
                    Marquer comme faite
                  </Button>
                </div>
              )}
            </section>
          )}
          {record.source === "EN_LIGNE" && (
            <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
              <h2 className="m-0 text-title-3">Demande en ligne</h2>
              {record.consentAt && <p className="m-0 text-small text-muted-foreground">Consentement donné le {formatDate(new Date(record.consentAt))}</p>}
              {record.answers.length === 0 ? <p className="m-0 text-muted-foreground">Pas de question en plus.</p> : (
                <dl className="m-0 flex flex-col gap-2">
                  {record.answers.map((a) => (
                    <div key={a.label}><dt className="text-small text-muted-foreground">{a.label}</dt><dd className="m-0">{a.value}</dd></div>
                  ))}
                </dl>
              )}
            </section>
          )}
          <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
            <h2 className="m-0 text-title-3">Historique</h2>
            <ol className="m-0 flex list-none flex-col gap-2 p-0">
              {record.history.map((e) => (
                <li key={e.id} className="flex gap-3">
                  <span className="shrink-0 text-small text-muted-foreground tabular-nums">{formatDate(new Date(e.createdAt))}</span>
                  <span>{e.label}</span>
                </li>
              ))}
            </ol>
          </section>
          {canEdit && !closed && (
            <Button variant="ghost" className="self-start text-danger-700" onClick={() => setDialog("refuse")}>
              Refuser la demande
            </Button>
          )}
        </aside>
      </div>

      {dialog === "stage" && <StageDialog record={record} open onClose={() => setDialog(null)} onDone={refresh} />}
      {dialog === "refuse" && <StageDialog record={record} open initial="REFUSEE" onClose={() => setDialog(null)} onDone={refresh} />}
      {dialog === "visit" && <MoveVisitDialog record={record} open onClose={() => setDialog(null)} onDone={refresh} />}
    </>
  );
}

/** Frise des étapes : franchies, en cours, à venir. */
function Stepper({ record }: { record: RequestRecord }) {
  const steps = STAGES.filter((s) => s !== "REFUSEE");
  const current = record.stage === "REFUSEE" ? -1 : steps.indexOf(record.stage);
  return (
    <ol aria-label="Étapes de la demande" className="m-0 flex list-none flex-wrap gap-x-6 gap-y-3 rounded-lg border border-border-subtle bg-card p-4 shadow-1">
      {steps.map((s, i) => {
        const done = i < current || (record.stage === "SIGNEE" && i === current);
        const isCurrent = i === current && record.stage !== "SIGNEE";
        return (
          <li key={s} aria-current={isCurrent ? "step" : undefined} className="flex items-center gap-2">
            <span
              aria-hidden
              className={cn(
                "inline-flex size-7 items-center justify-center rounded-full",
                done ? "bg-success-100 text-success-700" : isCurrent ? "bg-lagon-600 text-white" : "border border-border bg-muted text-muted-foreground",
              )}
            >
              {done ? <Check className="size-4" /> : isCurrent ? <Clock className="size-4" /> : null}
            </span>
            <span className={cn("text-label", isCurrent && "text-lagon-700")}>
              {stageLabels[s]}
              <span className="block text-small font-normal text-muted-foreground tabular-nums">
                {i === 0 ? isoToDateFr(record.receivedOn) : isCurrent || (done && i === current) ? isoToDateFr(record.stageChangedOn) : done ? "faite" : "à venir"}
              </span>
            </span>
          </li>
        );
      })}
    </ol>
  );
}

function Siblings({ record }: { record: RequestRecord }) {
  const sections = useSections();
  return (
    <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <h2 className="m-0 text-title-3">Fratrie déjà accueillie</h2>
      <ul className="m-0 flex list-none flex-col gap-2 p-0">
        {record.siblings.map((s) => (
          <li key={s.id} className="flex flex-wrap items-center gap-3">
            <span>
              <strong>{s.firstName} {s.lastName}</strong>
              {s.entryDate && <span className="text-muted-foreground"> · accueilli(e) depuis le {isoToDateFr(s.entryDate)}</span>}
              {s.exitDate && <span className="text-muted-foreground"> · sortie le {isoToDateFr(s.exitDate)}</span>}
            </span>
            <SectionBadge section={sections.data?.find((x) => x.id === s.sectionId)} />
            <Link to={`/enfants/${s.id}`} className="text-label text-lagon-700">Voir la fiche</Link>
          </li>
        ))}
      </ul>
    </section>
  );
}

function Notes({ record, canEdit, onAdded }: { record: RequestRecord; canEdit: boolean; onAdded: () => Promise<unknown> }) {
  const [body, setBody] = useState("");
  const [error, setError] = useState<string | null>(null);
  const add = async () => {
    if (!body.trim()) return;
    try {
      await enrollmentApi.addNote(record.id, body.trim());
      setBody("");
      await onAdded();
    } catch (e) {
      setError(errorMessage(e));
    }
  };
  return (
    <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <h2 className="m-0 text-title-3">Notes</h2>
      {canEdit && (
        <div className="flex flex-col gap-2">
          <label htmlFor="nouvelle-note" className="text-label">Ajouter une note</label>
          <Textarea id="nouvelle-note" value={body} onChange={(e) => setBody(e.target.value)} maxLength={2000} />
          <Button type="button" variant="outline" size="sm" className="self-start" onClick={() => void add()} disabled={!body.trim()}>
            Ajouter la note
          </Button>
          {error && <Alert>{error}</Alert>}
        </div>
      )}
      {record.notes.length === 0 ? (
        <p className="m-0 text-muted-foreground">Aucune note.</p>
      ) : (
        <ul className="m-0 flex list-none flex-col gap-3 p-0">
          {record.notes.map((n) => {
            const at = new Date(n.createdAt);
            return (
              <li key={n.id} className="flex flex-col gap-0.5">
                <span className="text-small text-muted-foreground tabular-nums">{formatDate(at)} · {formatTime(at)} · {n.author}</span>
                <span className="whitespace-pre-line">{n.body}</span>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

/** Places libres dans la section de la demande, les jours demandés, sur 6 mois. */
function FreePlaces({ record }: { record: RequestRecord }) {
  const capacity = useQuery({ queryKey: enrollmentKeys.capacity, queryFn: ({ signal }) => enrollmentApi.capacity(signal) });
  const sections = useSections();
  const section = sections.data?.find((s) => s.id === record.sectionId);
  const hint = capacityHint(record.capacity, record.stage);
  return (
    <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <h2 className="m-0 text-title-3">Places libres{section ? ` en ${section.name}` : ""}</h2>
      {capacity.data && record.sectionId ? (
        <MiniGrid grid={capacity.data} sectionId={record.sectionId} days={record.days} desiredMonth={record.desiredStart.slice(0, 7)} />
      ) : (
        !record.sectionId && <p className="m-0 text-muted-foreground">Choisissez la section prévue pour voir les places libres.</p>
      )}
      {hint && <TonePill tone={hint.tone}>{hint.label}</TonePill>}
      {record.capacity?.verdict === "LATER" && record.capacity.fullDays.length > 0 && (
        <p className="m-0 text-small text-muted-foreground">
          Complet le {record.capacity.fullDays.map((d) => DAY_SHORT[d - 1].toLowerCase()).join(" et le ")} en {formatMonthLong(record.desiredStart.slice(0, 7))}.
          Proposez une entrée en {formatMonthLong(record.capacity.firstMonth!)} ou un autre jour.
        </p>
      )}
      <Link to="/inscriptions/capacite" className="text-label text-lagon-700">Ouvrir la vue Capacité</Link>
    </section>
  );
}

function MiniGrid({ grid, sectionId, days, desiredMonth }: { grid: CapacityGrid; sectionId: string; days: number[]; desiredMonth: string }) {
  const section = grid.sections.find((s) => s.sectionId === sectionId);
  if (!section) return null;
  const rows = section.days.filter((d) => days.includes(d.dayOfWeek));
  return (
    <table className="w-full border-collapse text-small tabular-nums">
      <caption className="sr-only">Places libres par mois les jours demandés</caption>
      <thead>
        <tr className="text-muted-foreground">
          <th scope="col" className="py-1 text-left font-semibold">Mois</th>
          {rows.map((r) => (
            <th key={r.dayOfWeek} scope="col" className="py-1 text-right font-semibold">{DAY_SHORT[r.dayOfWeek - 1]}</th>
          ))}
        </tr>
      </thead>
      <tbody>
        {grid.months.map((m, i) => (
          <tr key={m} className={cn(m === desiredMonth && "outline-2 -outline-offset-2 outline-lagon-600")}>
            <th scope="row" className="py-1 text-left font-normal">{formatMonthShort(m)}</th>
            {rows.map((r) => {
              const free = r.free[i];
              return (
                <td key={r.dayOfWeek} className={cn("px-2 py-1 text-right font-semibold", free <= 0 ? "bg-danger-100 text-danger-700" : free === 1 ? "text-warning-700" : "text-success-700")}>
                  {free <= 0 ? "Complet" : free}
                </td>
              );
            })}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
