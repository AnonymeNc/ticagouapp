import { useState } from "react";
import { Link } from "react-router";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Banknote, FileSpreadsheet, FileText, Send } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { todayIso } from "@/lib/age";
import { errorMessage } from "@/lib/api";
import { isoToDateFr } from "@/lib/datetime";
import { formatMonthLong, formatXpf } from "@/lib/format";
import { selectClass } from "@/lib/formFields";
import { cn } from "@/lib/utils";
import { billingApi, billingKeys, type FunderRequest } from "./api";
import { BillingHeader, RequestStatusPill, billableMonths, previousMonth } from "./components";
import { PaymentDialog } from "./PaymentDialog";

/**
 * Demandes aux financeurs (maquette « Demandes aux financeurs ») : une demande par financeur et par mois, avec le
 * détail par enfant, l'envoi noté à la main, les justificatifs (relevés de présence) et l'export Excel.
 */
export function FunderRequestsPage() {
  const [month, setMonth] = useState(previousMonth());
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [paying, setPaying] = useState<FunderRequest | null>(null);
  const [error, setError] = useState<string | null>(null);
  const queryClient = useQueryClient();
  const requests = useQuery({ queryKey: billingKeys.requests(month), queryFn: ({ signal }) => billingApi.requests(month, signal) });
  const history = useQuery({ queryKey: billingKeys.requests("all"), queryFn: ({ signal }) => billingApi.requests(null, signal) });
  const list = requests.data ?? [];
  const selected = list.find((r) => r.id === selectedId) ?? list[0] ?? null;
  const previous = (history.data ?? []).filter((r) => r.month < month);

  const run = async (action: () => Promise<unknown>) => {
    setError(null);
    try {
      await action();
      await queryClient.invalidateQueries({ queryKey: ["billing"] });
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <>
      <BillingHeader
        title="Demandes aux financeurs"
        actions={list.length > 0 && (
          <Button variant="outline" onClick={() => void run(() => billingApi.requestsXlsx(month))}><FileSpreadsheet aria-hidden />Tout exporter (Excel)</Button>
        )}
      />
      <FormField label="Mois">
        <select className={cn(selectClass, "w-auto self-start")} value={month} onChange={(e) => { setMonth(e.target.value); setSelectedId(null); }}>
          {billableMonths().map((m) => <option key={m} value={m}>{formatMonthLong(m)}</option>)}
        </select>
      </FormField>
      {error && <Alert>{error}</Alert>}
      {requests.isError && <Alert>{errorMessage(requests.error)}</Alert>}
      {requests.data && list.length === 0 && (
        <p className="m-0 text-muted-foreground">
          Aucune demande pour {formatMonthLong(month)}. Elles sont préparées en même temps que les factures, depuis{" "}
          <Link to="/facturation" className="text-lagon-700">Facturer le mois</Link>.
        </p>
      )}

      {selected && (
        <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,2fr)]">
          <ul className="m-0 flex list-none flex-col gap-2 p-0" aria-label="Financeurs du mois">
            {list.map((r) => (
              <li key={r.id}>
                <button
                  type="button"
                  aria-current={r.id === selected.id}
                  onClick={() => setSelectedId(r.id)}
                  className={cn("flex w-full flex-col gap-1 rounded-lg border bg-card p-4 text-left shadow-1", r.id === selected.id ? "border-lagon-700" : "border-border-subtle")}
                >
                  <span className="flex items-center justify-between gap-2"><strong>{r.funderName}</strong><RequestStatusPill status={r.status} /></span>
                  <span className="text-small text-muted-foreground">{r.number} · {r.lines.length} enfant{r.lines.length > 1 ? "s" : ""}</span>
                  <span className="text-numeric-lg tabular-nums">{formatXpf(r.total)}</span>
                </button>
              </li>
            ))}
          </ul>
          <section aria-labelledby="demande" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <h2 id="demande" className="m-0 text-title-2">{selected.funderName} · {formatMonthLong(selected.month)}</h2>
                <p className="m-0 text-small text-muted-foreground">
                  {selected.number}{selected.conventionRef && ` · Convention n° ${selected.conventionRef}`}
                  {selected.sentOn && ` · envoyée le ${isoToDateFr(selected.sentOn)} par ${selected.sentBy}`}
                </p>
              </div>
              <div className="flex flex-wrap gap-2">
                {!selected.sentOn && (
                  <Button onClick={() => void run(() => billingApi.markRequestSent(selected.id, todayIso()))}><Send aria-hidden />Marquer comme envoyée</Button>
                )}
                <Button variant="outline" onClick={() => void run(() => billingApi.requestPdf(selected.id, selected.number))}><FileText aria-hidden />Demande et justificatifs</Button>
                {selected.remaining > 0 && <Button variant="outline" onClick={() => setPaying(selected)}><Banknote aria-hidden />Enregistrer le règlement</Button>}
              </div>
            </div>
            <table className="w-full border-collapse text-left tabular-nums">
              <thead>
                <tr className="border-b border-border-subtle text-label text-muted-foreground">
                  <th scope="col" className="py-2 pr-3">Enfant</th>
                  <th scope="col" className="py-2 pr-3">Parent bénéficiaire</th>
                  <th scope="col" className="py-2 pr-3 text-right">Jours de présence</th>
                  <th scope="col" className="py-2 pr-3">Facture famille</th>
                  <th scope="col" className="py-2 text-right">Montant de l’aide</th>
                </tr>
              </thead>
              <tbody>
                {selected.lines.map((l) => (
                  <tr key={`${l.childId}-${l.invoiceId}`} className="border-b border-border-subtle">
                    <td className="py-2 pr-3">{l.childName}</td>
                    <td className="py-2 pr-3">{l.beneficiary ?? "—"}</td>
                    <td className="py-2 pr-3 text-right">{l.attendanceDays}</td>
                    <td className="py-2 pr-3"><Link to={`/facturation/factures/${l.invoiceId}`} className="text-lagon-700">{l.invoiceNumber}</Link></td>
                    <td className="py-2 text-right">{formatXpf(l.amount)}</td>
                  </tr>
                ))}
                <tr className="font-semibold">
                  <td colSpan={4} className="py-2 pr-3">Total à réclamer</td>
                  <td className="py-2 text-right">{formatXpf(selected.total)}</td>
                </tr>
              </tbody>
            </table>
            {selected.received > 0 && <p className="m-0 text-small">Reçu : {formatXpf(selected.received)} · reste {formatXpf(selected.remaining)}</p>}
          </section>
        </div>
      )}

      {previous.length > 0 && (
        <section aria-labelledby="precedentes" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
          <h2 id="precedentes" className="m-0 text-title-2">Demandes des mois précédents</h2>
          <table className="w-full border-collapse text-left tabular-nums">
            <thead>
              <tr className="border-b border-border-subtle text-label text-muted-foreground">
                <th scope="col" className="py-2 pr-3">Période</th>
                <th scope="col" className="py-2 pr-3">Financeur</th>
                <th scope="col" className="py-2 pr-3 text-right">Demandé</th>
                <th scope="col" className="py-2 pr-3 text-right">Reçu</th>
                <th scope="col" className="py-2 pr-3">Envoyée le</th>
                <th scope="col" className="py-2">État</th>
              </tr>
            </thead>
            <tbody>
              {previous.map((r) => (
                <tr key={r.id} className="border-b border-border-subtle">
                  <td className="py-2 pr-3">{formatMonthLong(r.month)}</td>
                  <td className="py-2 pr-3">{r.funderName}</td>
                  <td className="py-2 pr-3 text-right">{formatXpf(r.total)}</td>
                  <td className="py-2 pr-3 text-right">{r.received > 0 ? formatXpf(r.received) : "—"}</td>
                  <td className="py-2 pr-3">{r.sentOn ? isoToDateFr(r.sentOn) : "—"}</td>
                  <td className="py-2">
                    <RequestStatusPill status={r.status} />
                    {r.status === "PARTIELLE" && <span className="ml-2 text-small text-muted-foreground">Écart de {formatXpf(r.remaining)} à justifier</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}

      {paying && (
        <PaymentDialog
          target={{ funderRequestId: paying.id, title: `${paying.number} · ${paying.funderName}`, remaining: paying.remaining }}
          onClose={() => setPaying(null)}
          onDone={() => { setPaying(null); void queryClient.invalidateQueries({ queryKey: ["billing"] }); }}
        />
      )}
    </>
  );
}
