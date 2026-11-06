import { useState } from "react";
import { Link, useParams } from "react-router";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Banknote, FileMinus, FileText, Send } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { todayIso } from "@/lib/age";
import { errorMessage } from "@/lib/api";
import { formatDate, formatTime, isoToDateFr, parseDateFr } from "@/lib/datetime";
import { formatMonthLong, formatXpf } from "@/lib/format";
import { billingApi, billingKeys, methodLabels, type Invoice } from "./api";
import { InvoiceStatusPill } from "./components";
import { PaymentDialog } from "./PaymentDialog";

type Editing = "payment" | "sent" | "credit" | null;

/** Facture (maquette « Facture FA-… ») : frais de garde, aides, reste à charge, conditions, historique. */
export function InvoicePage() {
  const { id = "" } = useParams();
  const queryClient = useQueryClient();
  const invoice = useQuery({ queryKey: billingKeys.invoice(id), queryFn: ({ signal }) => billingApi.invoice(id, signal) });
  const [editing, setEditing] = useState<Editing>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = () => queryClient.invalidateQueries({ queryKey: ["billing"] });
  const pdf = async (action: () => Promise<void>) => {
    setError(null);
    try {
      await action();
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  if (invoice.isPending) return <p className="text-muted-foreground">Chargement de la facture…</p>;
  if (invoice.isError) return <Alert>{errorMessage(invoice.error)}</Alert>;
  const data = invoice.data;
  const s = data.summary;
  const care = data.lines.filter((l) => l.kind !== "AIDE");
  const aids = data.lines.filter((l) => l.kind === "AIDE");
  const several = new Set(data.lines.map((l) => l.childId)).size > 1;
  const open = s.status !== "ANNULEE" && s.remaining > 0;

  return (
    <>
      <Link to="/facturation" className="inline-flex items-center gap-1.5 text-label text-lagon-700 print:hidden">
        <ArrowLeft aria-hidden className="size-4" />
        Facturation de {formatMonthLong(s.month)}
      </Link>
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex flex-col gap-1">
          <h1 className="m-0 font-display text-display">Facture {s.number}</h1>
          <div className="flex flex-wrap items-center gap-2">
            <InvoiceStatusPill status={s.status} />
            <span className="text-muted-foreground">{s.familyLabel} · émise le {isoToDateFr(s.issuedOn)}</span>
          </div>
        </div>
        <div className="flex flex-wrap gap-2 print:hidden">
          <Button variant="outline" onClick={() => void pdf(() => billingApi.invoicePdf(id, s.number))}><FileText aria-hidden />Aperçu PDF</Button>
          <Button variant="outline" onClick={() => setEditing("sent")}><Send aria-hidden />Noter comme envoyée</Button>
          {s.status !== "ANNULEE" && <Button variant="outline" onClick={() => setEditing("credit")}><FileMinus aria-hidden />Faire un avoir</Button>}
          {open && <Button onClick={() => setEditing("payment")}><Banknote aria-hidden />Enregistrer un paiement</Button>}
        </div>
      </div>
      {error && <Alert>{error}</Alert>}

      <div className="grid gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
        <div className="flex flex-col gap-4">
          <dl className="m-0 grid grid-cols-2 gap-4 rounded-lg border border-border-subtle bg-card p-5 shadow-1 md:grid-cols-4">
            <Info label="Facture adressée à" value={s.payerName} />
            <Info label="Période" value={`${isoToDateFr(`${s.month}-01`)} au ${lastDay(s.month)}`} />
            <Info label="Contrat" value={data.contractText ?? "—"} />
            <Info label="Échéance" value={isoToDateFr(s.dueOn)} />
          </dl>

          <section aria-labelledby="frais" className="flex flex-col gap-2 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
            <h2 id="frais" className="m-0 text-title-2">Frais de garde du mois</h2>
            <table className="w-full border-collapse text-left tabular-nums">
              <tbody>
                {care.map((l, i) => (
                  <tr key={i} className="border-b border-border-subtle align-top">
                    <td className="py-2 pr-3">
                      <div>{several && l.childName && <strong>{l.childName} · </strong>}{l.label}</div>
                      {l.detail && <div className="text-small text-muted-foreground">{l.detail}</div>}
                      {l.tgcAmount !== 0 && <div className="text-small text-muted-foreground">dont TGC {formatXpf(l.tgcAmount)}</div>}
                    </td>
                    <td className="py-2 text-right whitespace-nowrap">{formatXpf(l.total)}</td>
                  </tr>
                ))}
                <tr className="font-semibold">
                  <td className="py-2 pr-3">Total des frais de garde</td>
                  <td className="py-2 text-right">{formatXpf(data.careTotal + data.tgcTotal)}</td>
                </tr>
              </tbody>
            </table>
          </section>

          {aids.length > 0 && (
            <section aria-labelledby="aides" className="flex flex-col gap-2 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
              <h2 id="aides" className="m-0 text-title-2">Aides qui réduisent la facture</h2>
              <table className="w-full border-collapse text-left tabular-nums">
                <tbody>
                  {aids.map((l, i) => (
                    <tr key={i} className="border-b border-border-subtle align-top">
                      <td className="py-2 pr-3">
                        <div>{several && l.childName && <strong>{l.childName} · </strong>}{l.label}</div>
                        {l.detail && <div className="text-small text-muted-foreground">{l.detail}</div>}
                      </td>
                      <td className="py-2 text-right whitespace-nowrap">{formatXpf(l.amount)}</td>
                    </tr>
                  ))}
                  <tr className="font-semibold">
                    <td className="py-2 pr-3">Total des aides</td>
                    <td className="py-2 text-right">{formatXpf(-s.aidTotal)}</td>
                  </tr>
                </tbody>
              </table>
              <p className="m-0 text-small text-muted-foreground">Ces aides sont réglées par les financeurs directement à la crèche.</p>
            </section>
          )}

          <div className="flex items-center justify-between rounded-lg border-l-4 border-lagon-700 bg-lagon-50 p-5">
            <div>
              <div className="text-label text-muted-foreground">Reste à la charge de la famille</div>
              {data.rounding !== 0 && <div className="text-small text-muted-foreground">dont arrondi {formatXpf(data.rounding)}</div>}
            </div>
            <div className="text-numeric-lg font-semibold text-lagon-700 tabular-nums">{formatXpf(s.familyTotal)}</div>
          </div>
        </div>

        <div className="flex flex-col gap-4">
          <section aria-labelledby="reglement" className="flex flex-col gap-2 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
            <h2 id="reglement" className="m-0 text-title-2">Règlement</h2>
            <dl className="m-0 grid grid-cols-2 gap-1 tabular-nums">
              <dt className="text-muted-foreground">Payé</dt><dd className="m-0 text-right">{formatXpf(s.paid)}</dd>
              {s.credited > 0 && <><dt className="text-muted-foreground">Avoirs</dt><dd className="m-0 text-right">{formatXpf(s.credited)}</dd></>}
              <dt className="font-semibold">Reste dû</dt><dd className="m-0 text-right font-semibold">{formatXpf(s.remaining)}</dd>
            </dl>
            {data.payments.length > 0 && (
              <ul className="m-0 flex list-none flex-col gap-1 p-0 text-small">
                {data.payments.map((p) => (
                  <li key={p.paymentId} className={p.cancelled ? "text-muted-foreground line-through" : ""}>
                    {isoToDateFr(p.paidOn)} · {methodLabels[p.method]}{p.reference && ` ${p.reference}`} · {formatXpf(p.amount)}
                  </li>
                ))}
              </ul>
            )}
            {data.creditNotes.map((n) => (
              <div key={n.id} className="flex items-center justify-between gap-2 text-small">
                <span>{n.number} · {n.fullCancel ? "avoir total" : "avoir"} de {formatXpf(n.familyTotal)}</span>
                <Button variant="ghost" size="sm" onClick={() => void pdf(() => billingApi.creditNotePdf(n.id, n.number))}>PDF</Button>
              </div>
            ))}
          </section>
          <section aria-labelledby="historique" className="flex flex-col gap-2 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
            <h2 id="historique" className="m-0 text-title-2">Historique</h2>
            <ol className="m-0 flex list-none flex-col gap-2 p-0">
              {data.events.map((e, i) => (
                <li key={i} className="flex flex-col">
                  <span>{e.label}</span>
                  <span className="text-small text-muted-foreground">{e.author} · {formatDate(new Date(e.at))} à {formatTime(new Date(e.at))}</span>
                </li>
              ))}
              {open && <li className="text-small text-muted-foreground">Paiement attendu avant le {isoToDateFr(s.dueOn)}</li>}
            </ol>
          </section>
        </div>
      </div>

      {editing === "payment" && (
        <PaymentDialog
          target={{ invoiceId: id, title: `${s.number} · ${s.payerName}`, remaining: s.remaining }}
          onClose={() => setEditing(null)}
          onDone={() => { setEditing(null); void refresh(); }}
        />
      )}
      {editing === "sent" && <SentDialog invoice={data} onClose={() => setEditing(null)} onDone={() => { setEditing(null); void refresh(); }} />}
      {editing === "credit" && <CreditNoteDialog invoice={data} onClose={() => setEditing(null)} onDone={() => { setEditing(null); void refresh(); }} />}
    </>
  );
}

function Info({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-label text-muted-foreground">{label}</dt>
      <dd className="m-0">{value}</dd>
    </div>
  );
}

function lastDay(month: string): string {
  const [y, m] = month.split("-").map(Number);
  return isoToDateFr(new Date(Date.UTC(y, m, 0)).toISOString().slice(0, 10));
}

function SentDialog({ invoice, onClose, onDone }: { invoice: Invoice; onClose: () => void; onDone: () => void }) {
  const [date, setDate] = useState(isoToDateFr(todayIso()));
  const [channel, setChannel] = useState("remise en main propre");
  const [error, setError] = useState<string | null>(null);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Noter la facture comme envoyée</DialogTitle>
          <DialogDescription>Aucun e-mail n’est envoyé par le logiciel : imprimez le PDF, remettez-le ou postez-le, puis notez-le ici.</DialogDescription>
        </DialogHeader>
        <FormField label="Envoyée le"><Input value={date} onChange={(e) => setDate(e.target.value)} inputMode="numeric" /></FormField>
        <FormField label="Comment">
          <Input value={channel} maxLength={80} onChange={(e) => setChannel(e.target.value)} />
        </FormField>
        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button
            onClick={async () => {
              const sentOn = parseDateFr(date);
              if (!sentOn) return setError("Saisissez la date au format JJ/MM/AAAA.");
              try {
                await billingApi.markSent(invoice.summary.id, sentOn, channel.trim() || null);
                onDone();
              } catch (e) {
                setError(errorMessage(e));
              }
            }}
          >
            Noter l’envoi
          </Button>
          <Button variant="ghost" onClick={onClose}>Annuler</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function CreditNoteDialog({ invoice, onClose, onDone }: { invoice: Invoice; onClose: () => void; onDone: () => void }) {
  const [full, setFull] = useState(false);
  const [reason, setReason] = useState("");
  const [label, setLabel] = useState("");
  const [amountField, setAmountField] = useState("");
  const [error, setError] = useState<string | null>(null);
  const s = invoice.summary;
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Faire un avoir sur {s.number}</DialogTitle>
          <DialogDescription>
            La facture ne change pas : l’avoir prend son propre numéro. Un avoir total annule la facture et permet de
            refacturer le mois de {formatMonthLong(s.month)}.
          </DialogDescription>
        </DialogHeader>
        <fieldset className="m-0 flex gap-4 border-0 p-0">
          <legend className="sr-only">Type d’avoir</legend>
          <label className="flex items-center gap-2"><input type="radio" checked={!full} onChange={() => setFull(false)} />Avoir partiel</label>
          <label className="flex items-center gap-2"><input type="radio" checked={full} onChange={() => setFull(true)} />Avoir total (annulation)</label>
        </fieldset>
        <FormField label="Motif"><Input value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} /></FormField>
        {!full && (
          <div className="grid grid-cols-[2fr_1fr] gap-3">
            <FormField label="Libellé"><Input value={label} maxLength={200} onChange={(e) => setLabel(e.target.value)} /></FormField>
            <FormField label="Montant (F)" hint={`Au plus ${formatXpf(s.remaining)}`}>
              <Input value={amountField} onChange={(e) => setAmountField(e.target.value)} inputMode="numeric" />
            </FormField>
          </div>
        )}
        {full && s.paid > 0 && <Alert>Des paiements sont lettrés sur cette facture : annulez-les d’abord depuis Encaissements et relances.</Alert>}
        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button
            onClick={async () => {
              if (!reason.trim()) return setError("Indiquez le motif de l’avoir.");
              const amount = Number(amountField.replace(/\s/g, ""));
              if (!full && (!label.trim() || !Number.isInteger(amount) || amount <= 0)) {
                return setError("Indiquez le libellé et le montant de l’avoir, en francs.");
              }
              try {
                const note = await billingApi.creditNote(s.id, {
                  reason: reason.trim(), full, lines: full ? [] : [{ label: label.trim(), amount }],
                });
                await billingApi.creditNotePdf(note.id, note.number);
                onDone();
              } catch (e) {
                setError(errorMessage(e));
              }
            }}
          >
            Émettre l’avoir
          </Button>
          <Button variant="ghost" onClick={onClose}>Annuler</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
