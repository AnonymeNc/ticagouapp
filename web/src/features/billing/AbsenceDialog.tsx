import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { todayIso } from "@/lib/age";
import { errorMessage } from "@/lib/api";
import { isoToDateFr, parseDateFr } from "@/lib/datetime";
import { isoToField, selectClass, textOrNull } from "@/lib/formFields";
import { billingApi, billingKeys, type Absence } from "./api";

type Props = {
  /** Enfant imposé (fiche enfant) ou à choisir (Présences). */
  childId?: string;
  children?: { id: string; name: string }[];
  /** Documents du dossier de l'enfant, proposés comme justificatif. */
  documents?: { id: string; name: string }[];
  absence?: Absence;
  defaultDay?: string;
  onClose: () => void;
  onSaved: () => void;
};

/** Signaler une absence (bureau) : motif, période, date de prévenance, justificatif du dossier. */
export function AbsenceDialog({ childId, children = [], documents, absence, defaultDay = todayIso(), onClose, onSaved }: Props) {
  const reasons = useQuery({ queryKey: billingKeys.reasons, queryFn: ({ signal }) => billingApi.reasons(signal) });
  const [child, setChild] = useState(childId ?? absence?.childId ?? children[0]?.id ?? "");
  const [from, setFrom] = useState(isoToField(absence?.fromDay ?? defaultDay));
  const [to, setTo] = useState(isoToField(absence?.toDay ?? defaultDay));
  const [notified, setNotified] = useState(isoToField(absence?.notifiedOn ?? todayIso()));
  const [reasonId, setReasonId] = useState(absence?.reasonId ?? "");
  const [documentId, setDocumentId] = useState(absence?.documentId ?? "");
  const [note, setNote] = useState(absence?.note ?? "");
  const [error, setError] = useState<string | null>(null);
  const options = (reasons.data ?? []).filter((r) => r.active || r.id === absence?.reasonId);
  const reason = options.find((r) => r.id === (reasonId || options[0]?.id));

  const submit = async () => {
    const fromDay = parseDateFr(from);
    const toDay = parseDateFr(to);
    const notifiedOn = parseDateFr(notified);
    if (!fromDay || !toDay || !notifiedOn) return setError("Saisissez les dates au format JJ/MM/AAAA.");
    if (!child) return setError("Choisissez l’enfant.");
    if (!reason) return setError("Choisissez le motif.");
    const body = { fromDay, toDay, reasonId: reason.id, notifiedOn, documentId: documentId || null, note: textOrNull(note) };
    try {
      await (absence ? billingApi.updateAbsence(absence.id, body) : billingApi.createAbsence(child, body));
      onSaved();
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{absence ? "Modifier l’absence" : "Signaler une absence"}</DialogTitle>
          <DialogDescription>Le motif décide de la déduction sur la facture du mois (carence, préavis, justificatif).</DialogDescription>
        </DialogHeader>
        {!childId && !absence && (
          <FormField label="Enfant">
            <select className={selectClass} value={child} onChange={(e) => setChild(e.target.value)}>
              {children.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          </FormField>
        )}
        <div className="grid grid-cols-2 gap-3">
          <FormField label="Du"><Input value={from} onChange={(e) => setFrom(e.target.value)} inputMode="numeric" /></FormField>
          <FormField label="Au"><Input value={to} onChange={(e) => setTo(e.target.value)} inputMode="numeric" /></FormField>
        </div>
        <FormField label="Motif">
          <select className={selectClass} value={reason?.id ?? ""} onChange={(e) => setReasonId(e.target.value)}>
            {options.map((r) => <option key={r.id} value={r.id}>{r.label}{r.deductible ? "" : " (non déduite)"}</option>)}
          </select>
        </FormField>
        <FormField label="Prévenue le" hint={reason?.noticeApplies ? "Le préavis des congés s’applique à ce motif." : undefined}>
          <Input value={notified} onChange={(e) => setNotified(e.target.value)} inputMode="numeric" />
        </FormField>
        {documents ? (
          <FormField label="Justificatif" hint={reason?.proofRequired ? "Exigé pour déduire l’absence." : undefined}>
            <select className={selectClass} value={documentId} onChange={(e) => setDocumentId(e.target.value)}>
              <option value="">Aucun</option>
              {documents.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
          </FormField>
        ) : (
          reason?.proofRequired && <p className="m-0 text-small text-warning-700">Justificatif exigé : joignez-le au dossier de l’enfant (onglet Documents), puis rattachez-le à l’absence depuis sa fiche.</p>
        )}
        <FormField label="Note (facultatif)"><Input value={note} maxLength={300} onChange={(e) => setNote(e.target.value)} /></FormField>
        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button onClick={() => void submit()}>Enregistrer</Button>
          <Button variant="ghost" onClick={onClose}>Annuler</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

/** « du 20/10/2026 au 21/10/2026 » ou « le 20/10/2026 » */
export function absencePeriod(a: Pick<Absence, "fromDay" | "toDay">): string {
  return a.fromDay === a.toDay ? `le ${isoToDateFr(a.fromDay)}` : `du ${isoToDateFr(a.fromDay)} au ${isoToDateFr(a.toDay)}`;
}
