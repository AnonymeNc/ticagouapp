import { useState } from "react";
import { CircleCheck, CloudOff } from "lucide-react";
import { roleLabels } from "@/features/auth/types";
import { ApiError } from "@/lib/api";
import { cn } from "@/lib/utils";
import { kioskApi, newId, type KioskStaff } from "./api";
import { BackButton, Initials, TouchButton, hm } from "./components";
import { useKiosk } from "./KioskContext";
import { PinPad } from "./PinPad";
import { openStaffAttendance, ratioView } from "./view";

type Pending = { pin: string; staff?: KioskStaff };

/**
 * Pointage du personnel (maquette « Pointage du personnel, hors connexion ») : en ligne, le code seul suffit ;
 * hors connexion, la personne touche son nom puis tape son code, vérifié au retour du serveur.
 */
export function StaffScreen({ onDone }: { onDone: () => void }) {
  const { today, online, enqueue, refresh, act, session } = useKiosk();
  const [selected, setSelected] = useState<KioskStaff | null>(null);
  const [nameMode, setNameMode] = useState(false);
  const [askSection, setAskSection] = useState<Pending | null>(null);
  const [moving, setMoving] = useState<KioskStaff | null>(null);
  const [feedback, setFeedback] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const staff = today?.staff ?? [];
  const adults = today ? ratioView(today).global.adults : 0;
  const byName = !online || nameMode;

  const present = staff.filter((s) => openStaffAttendance(s));
  const left = staff.filter((s) => !openStaffAttendance(s) && s.attendances.length > 0);
  const sectionName = (id: string | null) => today?.sections.find((s) => s.id === id)?.name;

  const announce = (name: string, arrival: boolean, at: string, counts: boolean) => {
    const next = counts ? adults + (arrival ? 1 : -1) : adults;
    setFeedback(
      `${arrival ? "Arrivée" : "Départ"} de ${name} à ${hm(at)}.` +
        (counts ? ` L’encadrement passe à ${next} adulte${next > 1 ? "s" : ""}.` : ""),
    );
  };

  /** Hors connexion (ou code partagé) : opération de la file, code vérifié par le serveur. */
  const punchByName = async (person: KioskStaff, pin: string, sectionId?: string | null) => {
    const open = openStaffAttendance(person);
    const section = sectionId ?? person.homeSectionId;
    if (!open && !section) {
      setAskSection({ pin, staff: person });
      return;
    }
    const at = new Date().toISOString();
    await enqueue({
      type: "POINTAGE_PERSONNEL",
      userId: person.userId,
      pin,
      staffAction: open ? "DEPART" : "ARRIVEE",
      sectionId: open ? undefined : section,
    });
    announce(person.displayName, !open, at, person.countsInRatio);
    setSelected(null);
    setAskSection(null);
  };

  /** En ligne : le serveur reconnaît la personne à son seul code. */
  const punchByPin = async (pin: string, sectionId?: string | null) => {
    try {
      const result = await kioskApi.staffPunch({ clientId: newId(), pin, sectionId });
      const person = staff.find((s) => s.userId === result.userId);
      announce(result.displayName, result.action === "ARRIVEE", result.at, person?.countsInRatio ?? false);
      setAskSection(null);
      refresh();
    } catch (e) {
      if (e instanceof ApiError && e.code === "section_required") setAskSection({ pin });
      else if (e instanceof ApiError && e.code === "pin_ambiguous") {
        setNameMode(true);
        setError(e.message);
      } else if (e instanceof ApiError) setError(e.message);
      else {
        setNameMode(true);
        setError("Le serveur ne répond pas : touchez votre nom, puis tapez votre code.");
      }
    }
  };

  const submit = async (pin: string) => {
    setError(null);
    setFeedback(null);
    setPending(true);
    try {
      if (selected) await punchByName(selected, pin);
      else await punchByPin(pin);
    } finally {
      setPending(false);
    }
  };

  const chooseSection = async (sectionId: string) => {
    if (!askSection) return;
    setPending(true);
    try {
      if (askSection.staff) await punchByName(askSection.staff, askSection.pin, sectionId);
      else await punchByPin(askSection.pin, sectionId);
    } finally {
      setPending(false);
    }
  };

  const status = (s: KioskStaff) => {
    const open = openStaffAttendance(s);
    const last = s.attendances.at(-1);
    if (s.attendances.some((a) => a.pending)) return { text: "En attente d’envoi", tone: "warning" as const };
    if (open) return { text: `Arrivée ${hm(open.arrivedAt)}${sectionName(open.sectionId) ? ` · ${sectionName(open.sectionId)}` : ""}`, tone: "success" as const };
    if (last?.departedAt) return { text: `Partie ${hm(last.departedAt)}`, tone: "muted" as const };
    return { text: "Pas encore arrivée", tone: "muted" as const };
  };

  return (
    <div className="flex flex-col gap-5 pt-5">
      <BackButton label="Retour aux enfants" onClick={onDone} />
      <h1 className="m-0 text-touch-title">Pointage du personnel</h1>
      {feedback && (
        <p role="status" className="m-0 flex items-center gap-3 rounded-lg bg-success-100 px-5 py-3 text-touch-label font-normal text-success-700">
          <CircleCheck aria-hidden className="size-7 shrink-0" />
          {feedback}
        </p>
      )}

      <div className="grid gap-6 lg:grid-cols-[1fr_400px]">
        <section aria-labelledby="equipe" className="flex flex-col gap-3">
          <h2 id="equipe" className="m-0 text-touch-label">
            Équipe du jour <span className="font-normal text-muted-foreground">· {present.length} présent{present.length > 1 ? "s" : ""} · {left.length} parti{left.length > 1 ? "s" : ""}</span>
          </h2>
          <ul className="m-0 flex list-none flex-col gap-2 p-0">
            {staff.map((s) => {
              const st = status(s);
              const open = openStaffAttendance(s);
              return (
                <li key={s.userId} className="flex items-center gap-3">
                  <button
                    type="button"
                    disabled={!byName || !s.hasPin}
                    aria-pressed={selected?.userId === s.userId}
                    onClick={() => {
                      setSelected(s);
                      setError(null);
                    }}
                    className={cn(
                      "flex grow items-center gap-4 rounded-lg border-2 bg-card px-4 py-3 text-left",
                      selected?.userId === s.userId ? "border-lagon-600 bg-lagon-50" : "border-border",
                    )}
                  >
                    <Initials name={s.displayName} tone={open ? "lagon" : "muted"} />
                    <span className="flex grow flex-col">
                      <span className="text-touch-label">
                        {s.displayName} <span className="font-normal text-muted-foreground">{s.jobTitle ?? roleLabels[s.role]}</span>
                      </span>
                      <span
                        className={cn(
                          "text-base",
                          st.tone === "success" && "text-success-700",
                          st.tone === "warning" && "font-semibold text-warning-700",
                          st.tone === "muted" && "text-muted-foreground",
                        )}
                      >
                        {st.text}
                      </span>
                    </span>
                  </button>
                  {open && session && (session.role === "DIRECTION" || session.role === "EDUCATRICE") && (
                    <TouchButton variant="secondary" onClick={() => setMoving(s)} aria-label={`Changer ${s.displayName} de section`}>
                      Section
                    </TouchButton>
                  )}
                </li>
              );
            })}
          </ul>
        </section>

        <section aria-labelledby="code" className="flex flex-col items-center gap-3 rounded-lg border border-border bg-card p-5">
          {moving ? (
            <>
              <h2 id="code" className="m-0 text-touch-label">Section de {moving.displayName}</h2>
              <SectionChoice
                sections={today?.sections ?? []}
                onPick={(sectionId) => {
                  void act({ type: "SECTION_PERSONNEL", staffUserId: moving.userId, sectionId });
                  setFeedback(`${moving.displayName} passe en section ${sectionName(sectionId)}.`);
                  setMoving(null);
                }}
              />
              <TouchButton variant="secondary" onClick={() => setMoving(null)}>Annuler</TouchButton>
            </>
          ) : askSection ? (
            <>
              <h2 id="code" className="m-0 text-touch-label">Dans quelle section commencez-vous ?</h2>
              <SectionChoice sections={today?.sections ?? []} onPick={(id) => void chooseSection(id)} disabled={pending} />
              <TouchButton variant="secondary" onClick={() => setAskSection(null)}>Annuler</TouchButton>
            </>
          ) : (
            <>
              <h2 id="code" className="m-0 text-touch-label">
                {selected ? `Code de ${selected.displayName}` : "Votre code personnel"}
              </h2>
              <p className="m-0 text-center text-touch-body text-muted-foreground">
                {byName && !selected
                  ? online
                    ? "Touchez votre nom dans la liste, puis tapez votre code."
                    : "Hors connexion : touchez votre nom, puis tapez votre code. Il sera vérifié au retour du serveur."
                  : "Arrivée ou départ, la tablette le reconnaît seule."}
              </p>
              {!online && <CloudOff aria-hidden className="size-6 text-warning-700" />}
              {(!byName || selected) && (
                <PinPad
                  label={selected ? `Code de ${selected.displayName}` : "Votre code personnel"}
                  onComplete={submit}
                  error={error}
                  pending={pending}
                  cancel={selected ? { label: "Retour", onClick: () => setSelected(null) } : undefined}
                />
              )}
              {byName && !selected && error && <p role="alert" className="m-0 text-touch-label text-danger-700">{error}</p>}
            </>
          )}
        </section>
      </div>
    </div>
  );
}

function SectionChoice({
  sections,
  onPick,
  disabled,
}: {
  sections: { id: string; name: string }[];
  onPick: (id: string) => void;
  disabled?: boolean;
}) {
  return (
    <div className="grid w-full gap-3">
      {sections.map((s) => (
        <TouchButton key={s.id} variant="secondary" disabled={disabled} onClick={() => onPick(s.id)}>
          {s.name}
        </TouchButton>
      ))}
    </div>
  );
}
