import { useEffect, useRef, useState } from "react";
import { CircleCheck, Minus, Plus } from "lucide-react";
import { formatLongDate } from "@/lib/datetime";
import { instructionLabels, newId, type InstructionInput, type InstructionKind, type KioskChild } from "./api";
import { Chip, HealthDetails, Initials, TouchButton, childAge, hm } from "./components";
import { useKiosk } from "./KioskContext";

const AUTO_RETURN_S = 20;
const KINDS: InstructionKind[] = ["A_MAL_DORMI", "PEU_MANGE", "MEDICAMENT", "NEZ_QUI_COULE", "SIESTE_AVANCER", "AUTRE"];

/** Minutes → « 14:00 » */
const clock = (total: number) => {
  const m = ((total % 1440) + 1440) % 1440;
  return `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;
};

/**
 * Arrivée enregistrée (maquette « Arrivée enregistrée ») : santé de l'enfant, personne qui le dépose,
 * consignes du parent. Sans action, retour à la grille après 20 secondes (l'arrivée reste enregistrée).
 */
export function ArrivalScreen({ child, attendanceId, onDone }: { child: KioskChild; attendanceId: string; onDone: () => void }) {
  const { act, now, today } = useKiosk();
  const arrivedAt = useRef(now());
  const [droppedOffBy, setDroppedOffBy] = useState<string | null>(null);
  const [kinds, setKinds] = useState<InstructionKind[]>([]);
  const [other, setOther] = useState("");
  const [hours, mins] = hm(arrivedAt.current.toISOString()).split(":").map(Number);
  // Prise proposée à la demi-heure qui suit, au moins 30 minutes après l'arrivée.
  const startMinutes = Math.ceil((hours * 60 + mins + 30) / 30) * 30;
  const [medicationAt, setMedicationAt] = useState(startMinutes);
  const [dose, setDose] = useState("");
  const [prescription, setPrescription] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [remaining, setRemaining] = useState(AUTO_RETURN_S);

  useEffect(() => {
    if (remaining <= 0) {
      onDone();
      return;
    }
    const timer = setTimeout(() => setRemaining((r) => r - 1), 1000);
    return () => clearTimeout(timer);
  }, [remaining, onDone]);

  const touched = () => setRemaining(AUTO_RETURN_S);
  const relationships = [...new Set(child.pickup.filter((p) => p.kind === "RESPONSABLE").map((p) => p.relationship))];
  const section = today?.sections.find((s) => s.id === child.sectionId)?.name;
  const toggle = (kind: InstructionKind) => setKinds((current) => (current.includes(kind) ? current.filter((k) => k !== kind) : [...current, kind]));

  const save = async () => {
    if (kinds.includes("AUTRE") && !other.trim()) {
      setError("Précisez l’autre consigne.");
      return;
    }
    const instructions: InstructionInput[] = kinds.map((kind) => ({
      id: newId(),
      kind,
      details: kind === "AUTRE" ? other.trim() : null,
      medicationAt: kind === "MEDICAMENT" ? clock(medicationAt) : null,
      medicationDose: kind === "MEDICAMENT" ? dose.trim() || null : null,
      prescriptionGiven: kind === "MEDICAMENT" && prescription,
    }));
    void act({ type: "CONSIGNES", childId: child.id, attendanceId, droppedOffBy, instructions });
    onDone();
  };

  const cancel = () => {
    void act({ type: "ANNULER_ARRIVEE", childId: child.id, attendanceId });
    onDone();
  };

  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-6 pt-6" onPointerDown={touched} onKeyDown={touched}>
      <div className="flex items-center gap-4 rounded-lg bg-success-100 px-6 py-4 text-success-700">
        <CircleCheck aria-hidden className="size-10 shrink-0" />
        <div className="grow">
          <h1 className="m-0 text-touch-title">Arrivée de {child.firstName} enregistrée</h1>
          <p className="m-0 text-touch-body">
            {formatLongDate(arrivedAt.current)} à {hm(arrivedAt.current.toISOString())}
            {section && <> · section {section}</>}
          </p>
        </div>
        <span className="text-numeric-lg tabular-nums">{hm(arrivedAt.current.toISOString())}</span>
      </div>

      <div className="flex items-center gap-4">
        <Initials name={`${child.firstName} ${child.lastName}`} size="lg" />
        <div>
          <p className="m-0 text-touch-title">{child.firstName}</p>
          <p className="m-0 text-touch-body text-muted-foreground">{child.lastName} · {childAge(child)}</p>
        </div>
      </div>
      <HealthDetails health={child.health} />

      <section aria-labelledby="depose-par" className="flex flex-col gap-3">
        <h2 id="depose-par" className="m-0 text-touch-label">Déposé par</h2>
        <div className="flex flex-wrap gap-3">
          {[...relationships, "Autre personne"].map((r) => (
            <Chip key={r} selected={droppedOffBy === r} onClick={() => setDroppedOffBy(droppedOffBy === r ? null : r)}>
              {r}
            </Chip>
          ))}
        </div>
      </section>

      <section aria-labelledby="consignes" className="flex flex-col gap-3">
        <h2 id="consignes" className="m-0 text-touch-label">
          Consigne du parent <span className="font-normal text-muted-foreground">facultatif</span>
        </h2>
        <div className="flex flex-wrap gap-3">
          {KINDS.map((kind) => (
            <Chip key={kind} selected={kinds.includes(kind)} onClick={() => toggle(kind)}>
              {instructionLabels[kind]}
            </Chip>
          ))}
        </div>
        {kinds.includes("AUTRE") && (
          <label className="flex flex-col gap-2 text-touch-label">
            Autre consigne
            <input
              value={other}
              onChange={(e) => setOther(e.target.value)}
              maxLength={300}
              className="h-touch rounded-md border border-input bg-card px-4 text-touch-body"
            />
          </label>
        )}
        {kinds.includes("MEDICAMENT") && (
          <fieldset className="flex flex-col gap-4 rounded-lg border border-border bg-card p-5">
            <legend className="px-1 text-touch-label">Médicament à donner</legend>
            <div className="flex items-center gap-4">
              <span className="text-touch-label font-normal">Heure de la prise</span>
              <button type="button" aria-label="Quinze minutes plus tôt" onClick={() => setMedicationAt((m) => m - 15)} className="flex size-touch items-center justify-center rounded-md border border-border">
                <Minus aria-hidden className="size-6" />
              </button>
              <output aria-label="Heure de la prise" className="text-numeric-lg tabular-nums">{clock(medicationAt)}</output>
              <button type="button" aria-label="Quinze minutes plus tard" onClick={() => setMedicationAt((m) => m + 15)} className="flex size-touch items-center justify-center rounded-md border border-border">
                <Plus aria-hidden className="size-6" />
              </button>
            </div>
            <label className="flex flex-col gap-2 text-touch-label">
              Médicament et dose
              <input
                value={dose}
                onChange={(e) => setDose(e.target.value)}
                maxLength={150}
                placeholder="Paracétamol sirop, dose de l’ordonnance"
                className="h-touch rounded-md border border-input bg-card px-4 text-touch-body"
              />
            </label>
            <label className="flex items-center gap-3 text-touch-label font-normal">
              <input type="checkbox" checked={prescription} onChange={(e) => setPrescription(e.target.checked)} className="size-6" />
              Ordonnance remise par le parent
            </label>
            <p className="m-0 text-touch-body text-muted-foreground">Rappel sur la tablette à {clock(medicationAt - 15)}</p>
          </fieldset>
        )}
        {error && <p role="alert" className="m-0 text-touch-label text-danger-700">{error}</p>}
      </section>

      <div className="flex flex-wrap items-center gap-4">
        <TouchButton variant="danger" onClick={cancel}>Annuler cette arrivée</TouchButton>
        <span className="grow text-touch-body text-muted-foreground" aria-live="off">
          Sans action, retour à la grille dans {remaining} s
        </span>
        <TouchButton onClick={() => void save()}>Enregistrer les consignes</TouchButton>
      </div>
    </div>
  );
}
