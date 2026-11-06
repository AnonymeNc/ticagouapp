import { useState, type ReactNode } from "react";
import { Link } from "react-router";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { CircleCheck, FileStack, TriangleAlert } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { TonePill } from "@/features/enrollment/components";
import { errorMessage } from "@/lib/api";
import { isoToDateFr } from "@/lib/datetime";
import { formatMonthLong, formatXpf } from "@/lib/format";
import { selectClass } from "@/lib/formFields";
import { billingApi, billingKeys, type Anomaly, type FamilyRow, type GenerationResult, type MonthPreview } from "./api";
import { BillingHeader, Kpi, billableMonths, previousMonth } from "./components";

const anomalyTitles: Record<Anomaly["type"], string> = {
  DEPART_MANQUANT: "Pointage manquant",
  SANS_CONTRAT: "Contrat expiré ou absent",
  JUSTIFICATIF_MANQUANT: "Justificatif manquant",
  SANS_PAYEUR: "Payeur manquant",
};

/** Lien de correction d'une anomalie (Présences, contrat, absences de l'enfant). */
function anomalyLink(a: Anomaly): { to: string; label: string } | null {
  if (a.type === "DEPART_MANQUANT" && a.days.length > 0) return { to: `/presences?date=${a.days[0]}`, label: "Compléter le pointage" };
  if (a.type === "SANS_CONTRAT" && a.childId) return { to: `/enfants/${a.childId}?onglet=contrat`, label: "Voir le contrat" };
  if (a.type === "JUSTIFICATIF_MANQUANT" && a.childId) return { to: `/enfants/${a.childId}?onglet=presences`, label: "Joindre le justificatif" };
  return null;
}

const amount = (n: number) => (n === 0 ? "—" : formatXpf(n));

/**
 * Facturer le mois (maquette « Facturer le mois ») : prévisualisation de toutes les familles, anomalies à corriger,
 * génération en lot des factures et des demandes aux financeurs.
 */
export function BillingMonthPage() {
  const [month, setMonth] = useState(previousMonth());
  const [detail, setDetail] = useState<FamilyRow | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [result, setResult] = useState<GenerationResult | null>(null);
  const queryClient = useQueryClient();
  const preview = useQuery({ queryKey: billingKeys.preview(month), queryFn: ({ signal }) => billingApi.preview(month, signal) });
  const data = preview.data;
  const ready = data?.families.filter((f) => f.status === "PRETE") ?? [];
  const label = formatMonthLong(month);

  return (
    <>
      <BillingHeader
        title={`Facturation ${label.startsWith("a") || label.startsWith("o") ? "d’" : "de "}${label}`}
        actions={
          data?.generationAllowed && ready.length > 0 && (
            <Button onClick={() => setConfirming(true)}><FileStack aria-hidden />Générer les factures</Button>
          )
        }
      />
      <div className="flex flex-wrap items-end gap-3">
        <FormField label="Mois facturé">
          <select className={selectClass} value={month} onChange={(e) => { setMonth(e.target.value); setResult(null); }}>
            {billableMonths().map((m) => <option key={m} value={m}>{formatMonthLong(m)}</option>)}
          </select>
        </FormField>
      </div>

      {result && (
        <div role="status" className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-success-700/30 bg-success-100 p-4 text-success-700">
          <span className="flex items-center gap-2">
            <CircleCheck aria-hidden className="size-5" />
            {result.invoices} facture{result.invoices > 1 ? "s" : ""} générée{result.invoices > 1 ? "s" : ""}
            {result.invoices > 1 ? `, de ${result.firstNumber} à ${result.lastNumber}` : ` : ${result.firstNumber}`}.
            {result.funderRequests.length > 0 && ` ${result.funderRequests.length} demande${result.funderRequests.length > 1 ? "s" : ""} de règlement préparée${result.funderRequests.length > 1 ? "s" : ""} pour les financeurs.`}
          </span>
          {result.funderRequests.length > 0 && <Link to="/facturation/financeurs" className="text-label font-semibold">Voir les demandes aux financeurs</Link>}
        </div>
      )}
      {preview.isError && <Alert>{errorMessage(preview.error)}</Alert>}
      {preview.isPending && <p className="text-muted-foreground">Calcul des factures du mois…</p>}

      {data && (
        <>
          <dl className="m-0 grid grid-cols-2 gap-4 md:grid-cols-4">
            <Kpi label="Total des frais de garde" value={formatXpf(data.totals.careTotal)} hint="Forfaits, suppléments, absences déduites" />
            <Kpi label="À facturer aux familles" value={formatXpf(data.totals.familyTotal)}
                 hint={`${data.totals.families} famille${data.totals.families > 1 ? "s" : ""}, ${data.families.reduce((n, f) => n + f.children.length, 0)} enfants`} />
            <Kpi label="À réclamer aux financeurs" value={formatXpf(data.totals.aidTotal)}
                 hint={`${data.funders.length} financeur${data.funders.length > 1 ? "s" : ""}`} />
            <Kpi label="Prêtes à facturer" value={`${data.totals.ready} sur ${data.totals.families}`}
                 hint={data.totals.withAnomalies > 0 ? `${data.totals.withAnomalies} famille${data.totals.withAnomalies > 1 ? "s" : ""} à vérifier`
                   : data.totals.invoiced > 0 ? `${data.totals.invoiced} déjà facturée${data.totals.invoiced > 1 ? "s" : ""}` : undefined} />
          </dl>

          {data.anomalies.length > 0 && <Anomalies preview={data} />}
          <FamiliesTable preview={data} onOpen={setDetail} />
          {!data.generationAllowed && <p className="m-0 text-small text-muted-foreground">Ce mois n’a pas commencé : on ne peut pas encore le facturer.</p>}
        </>
      )}

      {detail && <FamilyDialog month={month} row={detail} onClose={() => setDetail(null)} />}
      {confirming && data && (
        <GenerateDialog
          preview={data}
          onClose={() => setConfirming(false)}
          onDone={(r) => {
            setResult(r);
            setConfirming(false);
            void queryClient.invalidateQueries({ queryKey: ["billing"] });
          }}
        />
      )}
    </>
  );
}

function Anomalies({ preview }: { preview: MonthPreview }) {
  const families = new Set(preview.anomalies.map((a) => a.familyId)).size;
  return (
    <section aria-labelledby="anomalies" className="flex flex-col gap-3 rounded-lg border border-warning-700/30 bg-warning-100/40 p-5">
      <h2 id="anomalies" className="m-0 flex items-center gap-2 text-title-2">
        <TriangleAlert aria-hidden className="size-5 text-warning-700" />
        {families} famille{families > 1 ? "s" : ""} à vérifier avant de facturer
      </h2>
      <ul className="m-0 flex list-none flex-col gap-2 p-0">
        {preview.anomalies.map((a, i) => {
          const link = anomalyLink(a);
          return (
            <li key={i} className="flex flex-wrap items-center justify-between gap-3 rounded-md bg-card p-3">
              <div className="flex flex-col gap-0.5">
                <strong className="text-label">{anomalyTitles[a.type]}</strong>
                <span>{a.message}</span>
              </div>
              {link && <Link to={link.to} className="text-label font-semibold text-lagon-700">{link.label}</Link>}
            </li>
          );
        })}
      </ul>
    </section>
  );
}

function FamiliesTable({ preview, onOpen }: { preview: MonthPreview; onOpen: (row: FamilyRow) => void }) {
  const sum = (pick: (f: FamilyRow) => number) => preview.families.reduce((n, f) => n + pick(f), 0);
  const states: Record<FamilyRow["status"], ReactNode> = {
    PRETE: <TonePill tone="success">Prête</TonePill>,
    ANOMALIE: <TonePill tone="warning">À vérifier</TonePill>,
    FACTUREE: <TonePill tone="info">Facturée</TonePill>,
    RIEN: <TonePill tone="neutral">Rien à facturer</TonePill>,
  };
  return (
    <section aria-labelledby="familles" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <h2 id="familles" className="m-0 text-title-2">Familles du mois</h2>
      {preview.families.length === 0 ? (
        <p className="m-0 text-muted-foreground">Aucun enfant sous contrat ce mois-ci.</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full border-collapse text-left tabular-nums">
            <caption className="sr-only">Montants par famille, avant génération des factures</caption>
            <thead>
              <tr className="border-b border-border-subtle text-label text-muted-foreground">
                <th scope="col" className="py-2 pr-3">Famille</th>
                <th scope="col" className="py-2 pr-3 text-right">Forfait</th>
                <th scope="col" className="py-2 pr-3 text-right">Heures compl.</th>
                <th scope="col" className="py-2 pr-3 text-right">Dépassements</th>
                <th scope="col" className="py-2 pr-3 text-right">Absences déduites</th>
                {preview.funders.map((f) => <th key={f.id} scope="col" className="py-2 pr-3 text-right">{f.name}</th>)}
                <th scope="col" className="py-2 pr-3 text-right">Part famille</th>
                <th scope="col" className="py-2 pr-3 text-right">Part financeurs</th>
                <th scope="col" className="py-2">État</th>
              </tr>
            </thead>
            <tbody>
              {preview.families.map((f) => (
                <tr key={f.familyId} className="border-b border-border-subtle">
                  <th scope="row" className="py-2 pr-3 text-left font-normal">
                    {f.invoiceId ? (
                      <Link to={`/facturation/factures/${f.invoiceId}`} className="font-semibold whitespace-nowrap text-lagon-700">{f.familyLabel}</Link>
                    ) : (
                      <button type="button" className="text-left font-semibold whitespace-nowrap text-lagon-700" onClick={() => onOpen(f)}>{f.familyLabel}</button>
                    )}
                    <div className="text-small text-muted-foreground">{f.children.join(", ")}{f.invoiceNumber && ` · ${f.invoiceNumber}`}</div>
                  </th>
                  <td className="py-2 pr-3 text-right">{amount(f.forfait)}</td>
                  <td className="py-2 pr-3 text-right">{amount(f.extraHours)}</td>
                  <td className="py-2 pr-3 text-right">{amount(f.overtime)}</td>
                  <td className="py-2 pr-3 text-right">{f.absences === 0 ? "—" : formatXpf(-f.absences)}</td>
                  {preview.funders.map((x) => <td key={x.id} className="py-2 pr-3 text-right">{amount(f.aids[x.id] ?? 0)}</td>)}
                  <td className="py-2 pr-3 text-right font-semibold">{formatXpf(f.familyTotal)}</td>
                  <td className="py-2 pr-3 text-right">{amount(f.aidTotal)}</td>
                  <td className="py-2">{states[f.status]}</td>
                </tr>
              ))}
              <tr className="border-t-2 border-border font-semibold">
                <th scope="row" className="py-2 pr-3 text-left">Total · {preview.families.length} famille{preview.families.length > 1 ? "s" : ""}</th>
                <td className="py-2 pr-3 text-right">{formatXpf(sum((f) => f.forfait))}</td>
                <td className="py-2 pr-3 text-right">{formatXpf(sum((f) => f.extraHours))}</td>
                <td className="py-2 pr-3 text-right">{formatXpf(sum((f) => f.overtime))}</td>
                <td className="py-2 pr-3 text-right">{formatXpf(-sum((f) => f.absences))}</td>
                {preview.funders.map((x) => <td key={x.id} className="py-2 pr-3 text-right">{formatXpf(preview.totals.aidByFunder[x.id] ?? 0)}</td>)}
                <td className="py-2 pr-3 text-right">{formatXpf(preview.totals.familyTotal)}</td>
                <td className="py-2 pr-3 text-right">{formatXpf(preview.totals.aidTotal)}</td>
                <td />
              </tr>
            </tbody>
          </table>
        </div>
      )}
      <p className="m-0 text-small text-muted-foreground">
        Les heures complémentaires, les départs après la fermeture et les absences déduites suivent les règles de
        Paramètres › Barème et facturation. Cliquez sur une famille pour voir le détail de sa facture.
      </p>
    </section>
  );
}

function FamilyDialog({ month, row, onClose }: { month: string; row: FamilyRow; onClose: () => void }) {
  const detail = useQuery({
    queryKey: billingKeys.familyPreview(month, row.familyId),
    queryFn: ({ signal }) => billingApi.familyPreview(month, row.familyId, signal),
  });
  const d = detail.data;
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>{row.familyLabel} · {formatMonthLong(month)}</DialogTitle>
          <DialogDescription>Aperçu de la facture avant génération{row.payerName && `, adressée à ${row.payerName}`}.</DialogDescription>
        </DialogHeader>
        {detail.isError && <Alert>{errorMessage(detail.error)}</Alert>}
        {d && (
          <div className="flex flex-col gap-3">
            {d.contractText && <p className="m-0 text-small text-muted-foreground">Contrat : {d.contractText}</p>}
            <table className="w-full border-collapse text-left tabular-nums">
              <tbody>
                {d.lines.map((l, i) => (
                  <tr key={i} className="border-b border-border-subtle align-top">
                    <td className="py-2 pr-3">
                      <div>{l.childName && row.children.length > 1 && <strong>{l.childName} · </strong>}{l.label}</div>
                      {l.detail && <div className="text-small text-muted-foreground">{l.detail}</div>}
                    </td>
                    <td className="py-2 text-right whitespace-nowrap">{formatXpf(l.total)}</td>
                  </tr>
                ))}
                {d.rounding !== 0 && (
                  <tr className="border-b border-border-subtle"><td className="py-2 pr-3">Arrondi</td><td className="py-2 text-right">{formatXpf(d.rounding)}</td></tr>
                )}
                <tr className="font-semibold"><td className="py-2 pr-3">Reste à la charge de la famille</td><td className="py-2 text-right">{formatXpf(d.familyTotal)}</td></tr>
              </tbody>
            </table>
            <p className="m-0 text-small text-muted-foreground">Échéance si la facture est générée aujourd’hui : {isoToDateFr(d.dueOn)}.</p>
            {d.notes.length > 0 && (
              <ul className="m-0 flex flex-col gap-1 pl-5 text-small text-muted-foreground">
                {d.notes.map((n, i) => <li key={i}>{n}</li>)}
              </ul>
            )}
            {d.anomalies.map((a, i) => <Alert key={i}>{a.message}</Alert>)}
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

function GenerateDialog({ preview, onClose, onDone }: { preview: MonthPreview; onClose: () => void; onDone: (r: GenerationResult) => void }) {
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const ready = preview.families.filter((f) => f.status === "PRETE");
  const blocked = preview.families.filter((f) => f.status === "ANOMALIE");
  const byFunder = preview.funders
    .map((x) => ({ ...x, total: ready.reduce((n, f) => n + (f.aids[x.id] ?? 0), 0), children: ready.filter((f) => (f.aids[x.id] ?? 0) > 0).length }))
    .filter((x) => x.total > 0);
  const label = formatMonthLong(preview.month);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-xl">
        <DialogHeader>
          <DialogTitle>Générer les factures de {label}</DialogTitle>
          <DialogDescription>
            Vérifiez avant de générer. Les factures prennent un numéro définitif et ne pourront plus être supprimées,
            seulement annulées par un avoir.
          </DialogDescription>
        </DialogHeader>
        <section className="flex flex-col gap-1">
          <h3 className="m-0 flex justify-between text-label">
            <span>{ready.length} facture{ready.length > 1 ? "s" : ""} familles</span>
            <span className="tabular-nums">{formatXpf(ready.reduce((n, f) => n + f.familyTotal, 0))}</span>
          </h3>
          <ul className="m-0 max-h-48 list-none overflow-y-auto p-0 text-small">
            {ready.map((f) => (
              <li key={f.familyId} className="flex justify-between border-b border-border-subtle py-1">
                <span>{f.familyLabel}{f.payerName && ` · ${f.payerName}`}</span>
                <span className="tabular-nums">{formatXpf(f.familyTotal)}</span>
              </li>
            ))}
          </ul>
        </section>
        {byFunder.length > 0 && (
          <section className="flex flex-col gap-1">
            <h3 className="m-0 flex justify-between text-label">
              <span>{byFunder.length} demande{byFunder.length > 1 ? "s" : ""} aux financeurs</span>
              <span className="tabular-nums">{formatXpf(byFunder.reduce((n, x) => n + x.total, 0))}</span>
            </h3>
            <ul className="m-0 list-none p-0 text-small">
              {byFunder.map((x) => (
                <li key={x.id} className="flex justify-between py-0.5">
                  <span>{x.name} · {x.children} famille{x.children > 1 ? "s" : ""}</span>
                  <span className="tabular-nums">{formatXpf(x.total)}</span>
                </li>
              ))}
            </ul>
          </section>
        )}
        {blocked.length > 0 && (
          <p className="m-0 text-small text-muted-foreground">
            Non facturées pour l’instant : {blocked.map((f) => f.familyLabel).join(", ")} (à vérifier). Vous pourrez les
            facturer une fois corrigées.
          </p>
        )}
        <p className="m-0 text-small text-muted-foreground">Aucun e-mail n’est envoyé : imprimez les factures, puis notez leur envoi.</p>
        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button
            disabled={busy}
            onClick={async () => {
              setBusy(true);
              setError(null);
              try {
                onDone(await billingApi.generate(preview.month));
              } catch (e) {
                setError(errorMessage(e));
                setBusy(false);
              }
            }}
          >
            Générer {ready.length} facture{ready.length > 1 ? "s" : ""}
          </Button>
          <Button variant="ghost" onClick={onClose}>Annuler</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
