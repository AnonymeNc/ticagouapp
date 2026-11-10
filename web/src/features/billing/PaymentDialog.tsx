import { useState } from "react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { DateInput } from "@/components/DateInput";
import { Input } from "@/components/ui/input";
import { todayIso } from "@/lib/age";
import { errorMessage } from "@/lib/api";
import { isoToDateFr, parseDateFr } from "@/lib/datetime";
import { formatXpf } from "@/lib/format";
import { cn } from "@/lib/utils";
import { billingApi, methodLabels, type Payment, type PaymentInput, type PaymentMethod } from "./api";

export type PaymentTarget = {
  /** Facture lettrée en premier (puis les autres impayés de la famille), ou demande au financeur. */
  invoiceId?: string;
  funderRequestId?: string;
  title: string;
  remaining: number;
};

const METHODS: PaymentMethod[] = ["VIREMENT", "CHEQUE", "ESPECES", "CARTE"];

/** Enregistrer un paiement (maquette « Enregistrer un paiement ») : champs selon le mode, paiement partiel signalé. */
export function PaymentDialog({ target, onClose, onDone }: { target: PaymentTarget; onClose: () => void; onDone: (p: Payment) => void }) {
  const [date, setDate] = useState(isoToDateFr(todayIso()));
  const [amountField, setAmountField] = useState(String(target.remaining));
  const [method, setMethod] = useState<PaymentMethod>("VIREMENT");
  const [reference, setReference] = useState("");
  const [bank, setBank] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const amount = Number(amountField.replace(/\s/g, ""));
  const remaining = Number.isFinite(amount) ? target.remaining - amount : target.remaining;

  const submit = async () => {
    const paidOn = parseDateFr(date);
    if (!paidOn) return setError("Saisissez la date au format JJ/MM/AAAA.");
    if (!Number.isInteger(amount) || amount <= 0) return setError("Saisissez un montant en francs, sans décimale.");
    if (amount > target.remaining) return setError(`Le montant dépasse le reste dû (${formatXpf(target.remaining)}).`);
    if (method === "CHEQUE" && !reference.trim()) return setError("Indiquez le numéro du chèque.");
    const body: PaymentInput = {
      ...(target.invoiceId ? { invoiceId: target.invoiceId } : { funderRequestId: target.funderRequestId }),
      paidOn,
      amount,
      method,
      reference: reference.trim() || null,
      bank: method === "CHEQUE" ? bank.trim() || null : null,
    };
    setBusy(true);
    setError(null);
    try {
      const payment = await billingApi.pay(body);
      if (payment.receiptNumber) await billingApi.receiptPdf(payment.id, payment.receiptNumber);
      onDone(payment);
    } catch (e) {
      setError(errorMessage(e));
      setBusy(false);
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Enregistrer un paiement</DialogTitle>
          <DialogDescription>{target.title} · reste dû {formatXpf(target.remaining)}</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-2 gap-3">
          <FormField label="Date du paiement"><DateInput value={date} onChange={(e) => setDate(e.target.value)} inputMode="numeric" /></FormField>
          <FormField label="Montant (F)"><Input value={amountField} onChange={(e) => setAmountField(e.target.value)} inputMode="numeric" /></FormField>
        </div>
        <fieldset className="m-0 flex flex-col gap-1.5 border-0 p-0">
          <legend className="mb-1.5 text-label">Mode de paiement</legend>
          <div className="flex flex-wrap gap-2">
            {METHODS.map((m) => (
              <button
                key={m}
                type="button"
                aria-pressed={method === m}
                onClick={() => setMethod(m)}
                className={cn("h-control rounded-full border px-3.5 text-label", method === m ? "border-lagon-700 bg-lagon-50 text-lagon-700" : "border-border")}
              >
                {methodLabels[m]}
              </button>
            ))}
          </div>
        </fieldset>
        {method === "VIREMENT" && <FormField label="Référence du virement"><Input value={reference} maxLength={80} onChange={(e) => setReference(e.target.value)} /></FormField>}
        {method === "CHEQUE" && (
          <div className="grid grid-cols-2 gap-3">
            <FormField label="N° du chèque"><Input value={reference} maxLength={80} onChange={(e) => setReference(e.target.value)} /></FormField>
            <FormField label="Banque émettrice"><Input value={bank} maxLength={80} onChange={(e) => setBank(e.target.value)} /></FormField>
          </div>
        )}
        {method === "ESPECES" && (
          <p className="m-0 text-small text-muted-foreground">Un reçu numéroté sera imprimé pour la famille. Pensez à déposer les espèces dans le coffre.</p>
        )}
        {method === "CARTE" && <FormField label="N° de transaction (ticket du terminal)"><Input value={reference} maxLength={80} onChange={(e) => setReference(e.target.value)} /></FormField>}
        {remaining > 0 && amount > 0 && <p className="m-0 text-small text-warning-700">Paiement partiel : il restera {formatXpf(remaining)} à régler.</p>}
        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button disabled={busy} onClick={() => void submit()}>Enregistrer le paiement</Button>
          <Button variant="ghost" onClick={onClose}>Annuler</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
