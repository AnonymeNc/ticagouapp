import { useState } from "react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { DateInput } from "@/components/DateInput";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { errorMessage } from "@/lib/api";
import { parseDateFr } from "@/lib/datetime";
import { selectClass } from "@/lib/formFields";
import { OPEN_STAGES, enrollmentApi, stageLabels, type RequestRecord, type Stage } from "./api";

type Props = { record: RequestRecord; open: boolean; onClose: () => void; onDone: () => Promise<unknown> };

/** « 05/11/2026 » + « 09:30 » → « 2026-11-05T09:30 » */
function toLocalDateTime(date: string, time: string) {
  const iso = parseDateFr(date);
  return iso && time ? `${iso}T${time}` : null;
}

/** Changer d'étape : chaque étape demande l'information utile (relance, visite, réponse attendue). */
export function StageDialog({ record, open, onClose, onDone, initial }: Props & { initial?: Stage }) {
  const reopening = record.stage === "REFUSEE";
  const [stage, setStage] = useState<Stage>(initial ?? (reopening ? "NOUVELLE" : record.stage));
  const [date, setDate] = useState("");
  const [time, setTime] = useState("09:30");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const choices: Stage[] = reopening ? OPEN_STAGES : [...OPEN_STAGES, "REFUSEE"];

  const submit = async () => {
    setError(null);
    const day = date.trim() ? parseDateFr(date) : null;
    if (date.trim() && !day) {
      setError("Date attendue au format JJ/MM/AAAA.");
      return;
    }
    if (stage === "VISITE_PREVUE" && !record.visitAt && !day) {
      setError("Indiquez la date de la visite.");
      return;
    }
    if (stage === "REFUSEE" && !reason.trim()) {
      setError("Indiquez le motif du refus.");
      return;
    }
    setPending(true);
    try {
      await enrollmentApi.changeStage(record.id, {
        stage,
        followUpOn: stage === "A_RECONTACTER" ? day : null,
        visitAt: stage === "VISITE_PREVUE" && day ? toLocalDateTime(date, time) : null,
        offerExpiresOn: stage === "PLACE_PROPOSEE" ? day : null,
        reason: stage === "REFUSEE" ? reason.trim() : null,
      });
      await onDone();
      onClose();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setPending(false);
    }
  };

  const dateLabel: Partial<Record<Stage, string>> = {
    A_RECONTACTER: "Relance prévue le (facultatif)",
    VISITE_PREVUE: "Date de la visite",
    PLACE_PROPOSEE: "Réponse attendue avant le (facultatif)",
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{reopening ? "Rouvrir la demande" : stage === "REFUSEE" && initial === "REFUSEE" ? "Refuser la demande" : "Changer d’étape"}</DialogTitle>
          <DialogDescription>
            {record.childFirstName} {record.childLastName} · étape actuelle : {stageLabels[record.stage]}.
            {" "}L’étape « Contrat signé » est atteinte à la signature du contrat.
          </DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-4">
          {initial !== "REFUSEE" && (
            <FormField label="Nouvelle étape">
              <select className={selectClass} value={stage} onChange={(e) => setStage(e.target.value as Stage)}>
                {choices.map((s) => (
                  <option key={s} value={s}>{stageLabels[s]}</option>
                ))}
              </select>
            </FormField>
          )}
          {dateLabel[stage] && (
            <div className="grid grid-cols-2 gap-4">
              <FormField label={dateLabel[stage]!} hint={stage === "VISITE_PREVUE" && record.visitAt ? "Laisser vide pour garder la visite prévue." : undefined}>
                <DateInput placeholder="JJ/MM/AAAA" inputMode="numeric" value={date} onChange={(e) => setDate(e.target.value)} />
              </FormField>
              {stage === "VISITE_PREVUE" && (
                <FormField label="Heure">
                  <Input type="time" step={900} value={time} onChange={(e) => setTime(e.target.value)} />
                </FormField>
              )}
            </div>
          )}
          {stage === "REFUSEE" && (
            <FormField label="Motif du refus" hint="Visible sur la carte de la demande et dans son historique.">
              <Textarea value={reason} onChange={(e) => setReason(e.target.value)} maxLength={300} />
            </FormField>
          )}
          {error && <Alert>{error}</Alert>}
          <div className="flex gap-3">
            <Button onClick={() => void submit()} disabled={pending} variant={stage === "REFUSEE" ? "destructive" : "default"}>
              {pending ? "Enregistrement…" : stage === "REFUSEE" ? "Refuser la demande" : "Enregistrer"}
            </Button>
            <Button variant="ghost" onClick={onClose}>Annuler</Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

export function MoveVisitDialog({ record, open, onClose, onDone }: Props) {
  const [date, setDate] = useState("");
  const [time, setTime] = useState(record.visitAt?.slice(11, 16) ?? "09:30");
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    const visitAt = toLocalDateTime(date, time);
    if (!visitAt) {
      setError("Indiquez la nouvelle date au format JJ/MM/AAAA.");
      return;
    }
    try {
      await enrollmentApi.moveVisit(record.id, visitAt);
      await onDone();
      onClose();
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Déplacer la visite</DialogTitle>
          <DialogDescription>La nouvelle date est inscrite dans l’historique de la demande.</DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-4">
          <div className="grid grid-cols-2 gap-4">
            <FormField label="Nouvelle date">
              <DateInput placeholder="JJ/MM/AAAA" inputMode="numeric" value={date} onChange={(e) => setDate(e.target.value)} />
            </FormField>
            <FormField label="Heure">
              <Input type="time" step={900} value={time} onChange={(e) => setTime(e.target.value)} />
            </FormField>
          </div>
          {error && <Alert>{error}</Alert>}
          <div className="flex gap-3">
            <Button onClick={() => void submit()}>Déplacer</Button>
            <Button variant="ghost" onClick={onClose}>Annuler</Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
