import { useMemo, useState } from "react";
import { Link } from "react-router";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { FileText, Printer, Send } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { todayIso } from "@/lib/age";
import { errorMessage } from "@/lib/api";
import { isoToDateFr } from "@/lib/datetime";
import { formatMonthLong, formatXpf } from "@/lib/format";
import { selectClass } from "@/lib/formFields";
import { billingApi, billingKeys, methodLabels, reminderLevels, type InvoiceSummary, type Payment, type Reminder } from "./api";
import { BillingHeader, Kpi } from "./components";
import { PaymentDialog } from "./PaymentDialog";

/**
 * Encaissements et relances (maquette « Encaissements et relances ») : encaissé du mois, à venir, impayés échus,
 * paiements, lettres de relance et attestations annuelles de frais de garde.
 */
export function CollectionsPage() {
  const queryClient = useQueryClient();
  const collections = useQuery({ queryKey: billingKeys.collections, queryFn: ({ signal }) => billingApi.collections(signal) });
  const [paying, setPaying] = useState<InvoiceSummary | null>(null);
  const [reminder, setReminder] = useState<{ invoice: InvoiceSummary; reminder: Reminder } | null>(null);
  const [cancelling, setCancelling] = useState<Payment | null>(null);
  const [error, setError] = useState<string | null>(null);
  const data = collections.data;
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["billing"] });

  const remind = async (invoice: InvoiceSummary) => {
    setError(null);
    try {
      setReminder({ invoice, reminder: await billingApi.remind(invoice.id) });
      void refresh();
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  const receipt = async (p: Payment) => {
    setError(null);
    try {
      await billingApi.receiptPdf(p.id, p.receiptNumber!);
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <>
      <BillingHeader title="Encaissements et relances" />
      {collections.isError && <Alert>{errorMessage(collections.error)}</Alert>}
      {error && <Alert>{error}</Alert>}
      {data && (
        <>
          <dl className="m-0 grid grid-cols-1 gap-4 md:grid-cols-3">
            <Kpi label={`Encaissé en ${formatMonthLong(todayIso().slice(0, 7)).split(" ")[0]}`} value={formatXpf(data.collectedThisMonth)}
                 hint={`${data.collectedCount} paiement${data.collectedCount > 1 ? "s" : ""}, familles et financeurs`} />
            <Kpi label="À venir (non échu)" value={formatXpf(data.upcoming)}
                 hint={data.upcomingCount === 0 ? "Aucune facture en attente" : `${data.upcomingCount} facture${data.upcomingCount > 1 ? "s" : ""}${data.nextDueOn ? `, prochaine échéance ${isoToDateFr(data.nextDueOn)}` : ""}`} />
            <Kpi label="Impayés échus" value={formatXpf(data.overdue)} tone={data.overdue > 0 ? "danger" : undefined}
                 hint={`${data.overdueCount} facture${data.overdueCount > 1 ? "s" : ""}${data.overdueOver30 > 0 ? `, dont ${data.overdueOver30} de plus de 30 jours` : ""}`} />
          </dl>

          <section aria-labelledby="impayes" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
            <h2 id="impayes" className="m-0 text-title-2">Factures impayées</h2>
            {data.unpaid.length === 0 ? <p className="m-0 text-muted-foreground">Toutes les factures sont réglées.</p> : (
              <table className="w-full border-collapse text-left tabular-nums">
                <thead>
                  <tr className="border-b border-border-subtle text-label text-muted-foreground">
                    <th scope="col" className="py-2 pr-3">Facture</th>
                    <th scope="col" className="py-2 pr-3">Famille</th>
                    <th scope="col" className="py-2 pr-3">Échéance</th>
                    <th scope="col" className="py-2 pr-3 text-right">Reste dû</th>
                    <th scope="col" className="py-2 pr-3">Retard</th>
                    <th scope="col" className="py-2 pr-3">Relances</th>
                    <th scope="col" className="py-2"><span className="sr-only">Actions</span></th>
                  </tr>
                </thead>
                <tbody>
                  {data.unpaid.map((i) => (
                    <tr key={i.id} className="border-b border-border-subtle">
                      <td className="py-2 pr-3"><Link to={`/facturation/factures/${i.id}`} className="font-semibold text-lagon-700">{i.number}</Link></td>
                      <td className="py-2 pr-3">{i.payerName}<div className="text-small text-muted-foreground">{i.familyLabel}</div></td>
                      <td className="py-2 pr-3">{isoToDateFr(i.dueOn)}</td>
                      <td className="py-2 pr-3 text-right">
                        {formatXpf(i.remaining)}
                        {i.remaining < i.familyTotal && <div className="text-small text-muted-foreground">sur {formatXpf(i.familyTotal)}</div>}
                      </td>
                      <td className={i.daysLate > 30 ? "py-2 pr-3 font-semibold text-danger-700" : "py-2 pr-3"}>
                        {i.daysLate > 0 ? `${i.daysLate} jour${i.daysLate > 1 ? "s" : ""}` : "—"}
                      </td>
                      <td className="py-2 pr-3">{i.reminderLevel === 0 ? "—" : reminderLevels[i.reminderLevel - 1]}</td>
                      <td className="py-2">
                        <div className="flex justify-end gap-2">
                          <Button size="sm" variant="outline" onClick={() => setPaying(i)}>Encaisser</Button>
                          {i.daysLate > 0 && i.reminderLevel < 3 && <Button size="sm" variant="ghost" onClick={() => void remind(i)}>Relancer</Button>}
                        </div>
                      </td>
                    </tr>
                  ))}
                  <tr className="font-semibold">
                    <td colSpan={3} className="py-2 pr-3">Total des impayés</td>
                    <td className="py-2 pr-3 text-right">{formatXpf(data.unpaid.reduce((n, i) => n + i.remaining, 0))}</td>
                    <td colSpan={3} />
                  </tr>
                </tbody>
              </table>
            )}
          </section>

          <section aria-labelledby="paiements" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
            <h2 id="paiements" className="m-0 text-title-2">Derniers paiements enregistrés</h2>
            {data.recentPayments.length === 0 ? <p className="m-0 text-muted-foreground">Aucun paiement enregistré.</p> : (
              <table className="w-full border-collapse text-left tabular-nums">
                <thead>
                  <tr className="border-b border-border-subtle text-label text-muted-foreground">
                    <th scope="col" className="py-2 pr-3">Date</th>
                    <th scope="col" className="py-2 pr-3">Payeur</th>
                    <th scope="col" className="py-2 pr-3">Affecté à</th>
                    <th scope="col" className="py-2 pr-3">Mode</th>
                    <th scope="col" className="py-2 pr-3 text-right">Montant</th>
                    <th scope="col" className="py-2"><span className="sr-only">Actions</span></th>
                  </tr>
                </thead>
                <tbody>
                  {data.recentPayments.map((p) => (
                    <tr key={p.id} className={p.cancelled ? "border-b border-border-subtle text-muted-foreground" : "border-b border-border-subtle"}>
                      <td className="py-2 pr-3">{isoToDateFr(p.paidOn)}</td>
                      <td className="py-2 pr-3">{p.payerName}</td>
                      <td className="py-2 pr-3">{p.allocations.map((a) => a.number).join(", ")}</td>
                      <td className="py-2 pr-3">
                        {methodLabels[p.method]}
                        {p.method === "CHEQUE" && p.reference && ` n° ${p.reference}`}
                        {p.receiptNumber && ` · reçu ${p.receiptNumber}`}
                        {p.cancelled && <div className="text-small">Annulé : {p.cancelReason}</div>}
                      </td>
                      <td className={p.cancelled ? "py-2 pr-3 text-right line-through" : "py-2 pr-3 text-right"}>{formatXpf(p.amount)}</td>
                      <td className="py-2">
                        <div className="flex justify-end gap-2">
                          {p.receiptNumber && <Button size="sm" variant="ghost" onClick={() => void receipt(p)}>Reçu</Button>}
                          {!p.cancelled && <Button size="sm" variant="ghost" onClick={() => setCancelling(p)}>Annuler</Button>}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
          <Attestations />
        </>
      )}

      {paying && (
        <PaymentDialog
          target={{ invoiceId: paying.id, title: `${paying.number} · ${paying.payerName}`, remaining: paying.remaining }}
          onClose={() => setPaying(null)}
          onDone={() => { setPaying(null); void refresh(); }}
        />
      )}
      {reminder && <ReminderDialog {...reminder} onClose={() => { setReminder(null); void refresh(); }} />}
      {cancelling && <CancelDialog payment={cancelling} onClose={() => setCancelling(null)} onDone={() => { setCancelling(null); void refresh(); }} />}
    </>
  );
}

/** Lettre de relance (texte de la maquette), imprimée en PDF ; l'envoi est noté à la main. */
function ReminderDialog({ invoice, reminder, onClose }: { invoice: InvoiceSummary; reminder: Reminder; onClose: () => void }) {
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState(reminder.sentOn !== null);
  const level = reminderLevels[reminder.level - 1];
  const run = async (action: () => Promise<unknown>) => {
    setError(null);
    try {
      await action();
    } catch (e) {
      setError(errorMessage(e));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-xl">
        <DialogHeader>
          <DialogTitle>Lettre de relance · {level}</DialogTitle>
          <DialogDescription>{invoice.number} · {invoice.payerName}</DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-2 rounded-md border border-border-subtle bg-muted/40 p-4 text-small">
          <p className="m-0">Madame, Monsieur,</p>
          <p className="m-0">
            Sauf erreur de notre part, la facture {invoice.number} de {formatMonthLong(invoice.month)}, arrivée à échéance le{" "}
            {isoToDateFr(invoice.dueOn)}, n’est pas encore réglée. Le montant restant dû est de {formatXpf(invoice.remaining)}.
          </p>
          <p className="m-0">
            Si vous rencontrez une difficulté, n’hésitez pas à en parler avec la direction : nous chercherons ensemble une
            solution. Si votre règlement a été effectué entre-temps, merci de ne pas tenir compte de ce courrier.
          </p>
        </div>
        {error && <Alert>{error}</Alert>}
        <div className="flex flex-wrap gap-3">
          <Button onClick={() => void run(() => billingApi.reminderPdf(reminder.id, invoice.number, reminder.level))}><Printer aria-hidden />Imprimer</Button>
          {!sent && (
            <Button variant="outline" onClick={() => void run(async () => { await billingApi.markReminderSent(reminder.id, todayIso()); setSent(true); })}>
              <Send aria-hidden />Noter comme envoyée
            </Button>
          )}
          <Button variant="ghost" onClick={onClose}>Fermer</Button>
        </div>
        {sent && <p className="m-0 text-small text-success-700">Envoi noté.</p>}
      </DialogContent>
    </Dialog>
  );
}

function CancelDialog({ payment, onClose, onDone }: { payment: Payment; onClose: () => void; onDone: () => void }) {
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Annuler le paiement de {formatXpf(payment.amount)}</DialogTitle>
          <DialogDescription>{payment.payerName} · {isoToDateFr(payment.paidOn)}. Le paiement reste visible, barré, et le motif est conservé.</DialogDescription>
        </DialogHeader>
        <FormField label="Motif"><Input value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} /></FormField>
        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button
            onClick={async () => {
              if (!reason.trim()) return setError("Indiquez le motif de l’annulation.");
              try {
                await billingApi.cancelPayment(payment.id, reason.trim());
                onDone();
              } catch (e) {
                setError(errorMessage(e));
              }
            }}
          >
            Annuler le paiement
          </Button>
          <Button variant="ghost" onClick={onClose}>Retour</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

/** Attestation annuelle de frais de garde, par famille. */
function Attestations() {
  const invoices = useQuery({ queryKey: billingKeys.invoices("all"), queryFn: ({ signal }) => billingApi.invoices({}, signal) });
  const families = useMemo(() => {
    const byId = new Map<string, string>();
    for (const i of invoices.data ?? []) byId.set(i.familyId, i.familyLabel);
    return [...byId].sort((a, b) => a[1].localeCompare(b[1], "fr"));
  }, [invoices.data]);
  const years = useMemo(() => [...new Set((invoices.data ?? []).map((i) => Number(i.month.slice(0, 4))))].sort((a, b) => b - a), [invoices.data]);
  const [familyId, setFamilyId] = useState("");
  const [year, setYear] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  if (families.length === 0) return null;
  const chosenFamily = familyId || families[0][0];
  const chosenYear = year ?? years[0];
  return (
    <section aria-labelledby="attestations" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <h2 id="attestations" className="m-0 text-title-2">Attestations de frais de garde</h2>
      <p className="m-0 text-small text-muted-foreground">Somme payée par la famille dans l’année, aides des financeurs déduites (déclaration de revenus).</p>
      <div className="flex flex-wrap items-end gap-3">
        <FormField label="Famille">
          <select className={selectClass} value={chosenFamily} onChange={(e) => setFamilyId(e.target.value)}>
            {families.map(([id, label]) => <option key={id} value={id}>{label}</option>)}
          </select>
        </FormField>
        <FormField label="Année">
          <select className={selectClass} value={chosenYear} onChange={(e) => setYear(Number(e.target.value))}>
            {years.map((y) => <option key={y} value={y}>{y}</option>)}
          </select>
        </FormField>
        <Button
          variant="outline"
          onClick={async () => {
            setError(null);
            try {
              await billingApi.attestationPdf(chosenFamily, chosenYear);
            } catch (e) {
              setError(errorMessage(e));
            }
          }}
        >
          <FileText aria-hidden />Éditer l’attestation
        </Button>
      </div>
      {error && <Alert>{error}</Alert>}
    </section>
  );
}
