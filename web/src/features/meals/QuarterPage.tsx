import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { CircleCheck, CircleDashed, FileSpreadsheet, FileText, Lock, LockOpen } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { TonePill, type Tone } from "@/features/enrollment/components";
import { errorMessage } from "@/lib/api";
import { formatDate, isoToDateFr } from "@/lib/datetime";
import { selectClass } from "@/lib/formFields";
import { mealKeys, mealsApi, type Quarter, type QuarterState } from "./api";
import { MealsHeader } from "./components";

const states: Record<QuarterState, { tone: Tone; label: string }> = {
  A_VENIR: { tone: "neutral", label: "À venir" },
  EN_COURS: { tone: "info", label: "En cours" },
  A_CLORE: { tone: "warning", label: "À clore" },
  CLOS: { tone: "success", label: "Trimestre clos" },
};

const number = (n: number) => n.toLocaleString("fr-FR");

/**
 * Récapitulatif trimestriel (maquette « Récapitulatif trimestriel ») : repas et goûters délivrés par mois et par
 * section, contrôles avant la demande d'aide, exports PDF et Excel, clôture par la direction.
 */
export function QuarterPage() {
  const user = useCurrentUser();
  const canExport = user.role === "DIRECTION" || user.role === "CUISINE";
  const isDirection = user.role === "DIRECTION";
  const queryClient = useQueryClient();
  const quarters = useQuery({ queryKey: mealKeys.quarters, queryFn: ({ signal }) => mealsApi.quarters(signal) });
  const [selected, setSelected] = useState<string | null>(null);
  const current = selected ?? (quarters.data ? `${quarters.data[0].year}-${quarters.data[0].quarter}` : null);
  const [year, quarter] = current ? current.split("-").map(Number) : [0, 0];
  const recap = useQuery({
    queryKey: mealKeys.quarter(year, quarter),
    queryFn: ({ signal }) => mealsApi.quarter(year, quarter, signal),
    enabled: current !== null,
  });
  const [error, setError] = useState<string | null>(null);
  const [reopening, setReopening] = useState(false);
  const data = recap.data;

  const update = (next: Quarter) => {
    queryClient.setQueryData(mealKeys.quarter(year, quarter), next);
    void queryClient.invalidateQueries({ queryKey: mealKeys.quarters });
  };

  const download = async (format: "pdf" | "xlsx") => {
    setError(null);
    try {
      await mealsApi.download(year, quarter, format);
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  const close = async () => {
    if (!data || !window.confirm(`Clore le ${data.label} ? Les pointages et les repas de ces trois mois ne pourront plus être corrigés.`)) return;
    setError(null);
    try {
      update(await mealsApi.close(year, quarter));
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <>
      <MealsHeader
        actions={
          canExport && data && (
            <>
              <Button variant="outline" onClick={() => void download("xlsx")}><FileSpreadsheet aria-hidden />Exporter en Excel</Button>
              <Button variant="outline" onClick={() => void download("pdf")}><FileText aria-hidden />Exporter en PDF</Button>
            </>
          )
        }
      />
      <div className="flex flex-wrap items-end gap-3">
        <FormField label="Trimestre">
          <select className={selectClass} value={current ?? ""} onChange={(e) => setSelected(e.target.value)}>
            {quarters.data?.map((q) => (
              <option key={`${q.year}-${q.quarter}`} value={`${q.year}-${q.quarter}`}>
                {q.label}{q.state === "EN_COURS" ? " (en cours)" : q.state === "CLOS" ? " · clos" : ""}
              </option>
            ))}
          </select>
        </FormField>
        {data && <TonePill tone={states[data.state].tone}>{states[data.state].label}</TonePill>}
        {data && <span className="text-muted-foreground">Du {isoToDateFr(data.from)} au {isoToDateFr(data.to)}</span>}
      </div>
      {error && <Alert>{error}</Alert>}
      {recap.isError && <Alert>{errorMessage(recap.error)}</Alert>}

      {data && (
        <>
          <dl className="m-0 grid grid-cols-2 gap-4 md:grid-cols-4">
            {[
              ["Repas délivrés", number(data.total.lunches)],
              ["Goûters délivrés", number(data.total.snacks)],
              ["Jours d’ouverture", number(data.total.openDays)],
              ["Moyenne par jour", `${data.averagePerDay} repas`],
            ].map(([label, value]) => (
              <div key={label} className="flex flex-col gap-1 rounded-lg border border-border-subtle bg-card p-4 shadow-1">
                <dt className="text-label text-muted-foreground">{label}</dt>
                <dd className="m-0 text-numeric-lg tabular-nums">{value}</dd>
              </div>
            ))}
          </dl>

          <section aria-labelledby="par-mois" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
            <h2 id="par-mois" className="m-0 text-title-2">Repas délivrés par mois</h2>
            <table className="w-full border-collapse text-left tabular-nums">
              <caption className="sr-only">Repas et goûters délivrés par mois et par section, {data.label}</caption>
              <thead>
                <tr className="border-b border-border-subtle text-label text-muted-foreground">
                  <th scope="col" className="py-2">Mois</th>
                  <th scope="col" className="py-2 text-right">Jours d’ouverture</th>
                  {data.sections.map((s) => <th key={s.id} scope="col" className="py-2 text-right">{s.name}</th>)}
                  <th scope="col" className="py-2 text-right">Total repas</th>
                  <th scope="col" className="py-2 text-right">Goûters</th>
                </tr>
              </thead>
              <tbody>
                {[...data.months, data.total].map((m, i) => {
                  const total = i === data.months.length;
                  return (
                    <tr key={m.label} className={total ? "border-t-2 border-border font-semibold" : "border-b border-border-subtle"}>
                      <th scope="row" className="py-2 text-left font-normal">{total ? <strong>{m.label}</strong> : m.label}</th>
                      <td className="py-2 text-right">{number(m.openDays)}</td>
                      {data.sections.map((s) => <td key={s.id} className="py-2 text-right">{number(m.lunchesBySection[s.id] ?? 0)}</td>)}
                      <td className="py-2 text-right">{number(m.lunches)}</td>
                      <td className="py-2 text-right">{number(m.snacks)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            <p className="m-0 text-small text-muted-foreground">
              Un repas est compté quand l’enfant est pointé présent à l’heure du repas. Les paniers repas fournis par les
              familles (PAI) ne sont pas comptés : {number(data.total.packedLunches)} sur le trimestre.
            </p>
          </section>

          <section aria-labelledby="avant-envoi" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
            <h2 id="avant-envoi" className="m-0 text-title-2">Avant d’envoyer la demande d’aide</h2>
            <ul className="m-0 flex list-none flex-col gap-2 p-0">
              <Check ok={data.checks.monthsOver}>
                {data.checks.monthsOver ? "Les trois mois du trimestre sont terminés." : "Le trimestre n’est pas terminé."}
              </Check>
              <Check ok={data.checks.toReview === 0}>
                {data.checks.toReview === 0 ? "Aucun pointage à vérifier." : `${data.checks.toReview} pointage${data.checks.toReview > 1 ? "s" : ""} à vérifier dans Présences.`}
              </Check>
              <Check ok={data.checks.closed}>
                {data.checks.closed && data.closedAt
                  ? `Trimestre clos le ${formatDate(new Date(data.closedAt))} par ${data.closedBy}.`
                  : "Trimestre non clos : les comptes peuvent encore changer."}
              </Check>
            </ul>
            <p className="m-0 text-small text-muted-foreground">
              PDF : récapitulatif signable, une page, prêt à joindre au dossier. Excel : même récapitulatif, plus le détail par
              enfant et par jour.
            </p>
            {isDirection && (
              <div className="flex gap-2">
                {data.state === "CLOS" ? (
                  <Button variant="outline" onClick={() => setReopening(true)}><LockOpen aria-hidden />Rouvrir le trimestre</Button>
                ) : (
                  <Button onClick={() => void close()} disabled={!data.checks.monthsOver || data.checks.toReview > 0}>
                    <Lock aria-hidden />
                    Clore le trimestre
                  </Button>
                )}
              </div>
            )}
          </section>
        </>
      )}
      {reopening && data && (
        <ReopenDialog
          label={data.label}
          onClose={() => setReopening(false)}
          onSubmit={async (reason) => update(await mealsApi.reopen(year, quarter, reason))}
        />
      )}
    </>
  );
}

function Check({ ok, children }: { ok: boolean; children: React.ReactNode }) {
  return (
    <li className="flex items-center gap-2">
      {ok ? <CircleCheck aria-hidden className="size-5 text-success-700" /> : <CircleDashed aria-hidden className="size-5 text-warning-700" />}
      {children}
    </li>
  );
}

function ReopenDialog({ label, onClose, onSubmit }: { label: string; onClose: () => void; onSubmit: (reason: string) => Promise<void> }) {
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Rouvrir le {label}</DialogTitle>
          <DialogDescription>Les pointages et les repas redeviennent modifiables. Le motif est conservé dans le journal.</DialogDescription>
        </DialogHeader>
        <FormField label="Motif"><Input value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} /></FormField>
        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button
            onClick={async () => {
              if (!reason.trim()) return setError("Indiquez le motif de la réouverture.");
              try {
                await onSubmit(reason.trim());
                onClose();
              } catch (e) {
                setError(errorMessage(e));
              }
            }}
          >
            Rouvrir
          </Button>
          <Button variant="ghost" onClick={onClose}>Annuler</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
