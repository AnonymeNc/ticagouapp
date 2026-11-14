import { RemoteApproval } from "./RemoteApproval";
import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { CircleAlert, IdCard, Phone, ShieldX, UserRoundPlus } from "lucide-react";
import { cn } from "@/lib/utils";
import { instructionLabels, kioskApi, type Candidate, type KioskChild } from "./api";
import { JOURNAL_KEY, entrySummary, kindLabels } from "./JournalScreen";
import { HealthDetails, Initials, TouchButton, hhmm, hm } from "./components";
import { useKiosk } from "./KioskContext";
import { PinPad } from "./PinPad";
import { openAttendance } from "./view";

/** Départ : qui vient chercher l'enfant (maquette « Départ, choix de la personne »). */
export function DepartureScreen({ child, onDone }: { child: KioskChild; onDone: () => void }) {
  const { act, now, today, dismiss } = useKiosk();
  const [chosen, setChosen] = useState<Candidate | "autre" | null>(null);
  const [unauthorized, setUnauthorized] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Passage figé à l'ouverture : l'affichage optimiste du départ ne doit pas fermer l'écran avant la réponse.
  const [attendance] = useState(() => openAttendance(child));
  const section = today?.sections.find((s) => s.id === child.sectionId)?.name;
  const time = hm(now().toISOString());

  useEffect(() => {
    if (!attendance) onDone();
  }, [attendance, onDone]);

  if (!attendance) return null;
  if (unauthorized) {
    return <UnauthorizedScreen child={child} attendanceId={attendance.id} onCancel={onDone} onDone={onDone} />;
  }

  const validate = async () => {
    if (chosen === "autre") {
      setUnauthorized(true);
      return;
    }
    if (!chosen) return;
    setError(null);
    const result = await act({
      type: "DEPART",
      childId: child.id,
      attendanceId: attendance.id,
      pickup: { kind: chosen.kind, personId: chosen.id },
    });
    if (result?.outcome === "REFUSEE") {
      dismiss(result.clientId);
      setError(result.message);
      return;
    }
    onDone();
  };

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6 pt-6">
      <div className="flex items-center gap-4">
        <Initials name={`${child.firstName} ${child.lastName}`} size="lg" />
        <div className="grow">
          <h1 className="m-0 text-touch-title">Départ de {child.firstName} {child.lastName}</h1>
          <p className="m-0 text-touch-body text-muted-foreground">
            {section}
            {section && " · "}Présent depuis {hm(attendance.arrivedAt)}
            {attendance.droppedOffBy && <> · Déposé par : {attendance.droppedOffBy.toLowerCase()}</>}
          </p>
        </div>
        <span className="text-numeric-lg tabular-nums">{time}</span>
      </div>
      <HealthDetails health={child.health} />

      {attendance.instructions.length > 0 && (
        <section aria-labelledby="transmettre" className="flex flex-col gap-2 rounded-lg border border-border bg-card p-5">
          <h2 id="transmettre" className="m-0 text-touch-label">À transmettre</h2>
          <ul className="m-0 flex list-none flex-col gap-1 p-0 text-touch-body">
            {attendance.instructions.map((i) => (
              <li key={i.id} className={cn(i.kind === "MEDICAMENT" && !i.givenAt && "font-semibold text-warning-700")}>
                {i.kind === "MEDICAMENT"
                  ? i.givenAt
                    ? `Médicament donné à ${hm(i.givenAt)}${i.givenBy ? ` par ${i.givenBy}` : ""}${i.medicationDose ? ` (${i.medicationDose})` : ""}`
                    : `Médicament prévu à ${hhmm(i.medicationAt!)} : pas encore donné`
                  : i.kind === "AUTRE"
                    ? i.details
                    : instructionLabels[i.kind]}
              </li>
            ))}
          </ul>
        </section>
      )}

      <DayRecap childId={child.id} firstName={child.firstName} />

      <section aria-labelledby="qui-vient" className="flex flex-col gap-3">
        <h2 id="qui-vient" className="m-0 text-touch-label">Qui vient chercher {child.firstName} ?</h2>
        <div role="radiogroup" aria-labelledby="qui-vient" className="grid grid-cols-2 gap-4 lg:grid-cols-3">
          {child.pickup.map((person) => (
            <button
              key={person.id}
              type="button"
              role="radio"
              aria-checked={chosen !== "autre" && chosen?.id === person.id}
              onClick={() => setChosen(person)}
              className={cn(
                "flex items-center gap-4 rounded-lg border-2 bg-card p-4 text-left shadow-1",
                chosen !== "autre" && chosen?.id === person.id ? "border-lagon-600 bg-lagon-50" : "border-border",
              )}
            >
              <Initials name={person.name} />
              <span className="flex flex-col">
                <span className="text-touch-label">{person.name}</span>
                <span className="text-base text-muted-foreground">{person.relationship}</span>
                {person.kind === "AUTORISEE" && (
                  <span className="mt-1 inline-flex items-center gap-1 text-small font-semibold text-info-700">
                    <IdCard aria-hidden className="size-4" />
                    Pièce d’identité
                  </span>
                )}
              </span>
            </button>
          ))}
          <button
            type="button"
            role="radio"
            aria-checked={chosen === "autre"}
            onClick={() => setChosen("autre")}
            className={cn(
              "flex items-center gap-4 rounded-lg border-2 border-dashed bg-card p-4 text-left",
              chosen === "autre" ? "border-danger-700 bg-danger-100" : "border-border",
            )}
          >
            <UserRoundPlus aria-hidden className="size-10 text-danger-700" />
            <span className="flex flex-col">
              <span className="text-touch-label">Une autre personne</span>
              <span className="text-base text-muted-foreground">Absente de la liste : la direction doit valider</span>
            </span>
          </button>
        </div>
      </section>

      {error && (
        <p role="alert" className="m-0 flex items-center gap-2 text-touch-label text-danger-700">
          <CircleAlert aria-hidden className="size-6" />
          {error}
        </p>
      )}
      <div className="flex flex-wrap justify-between gap-4">
        <TouchButton variant="secondary" onClick={onDone}>Annuler, {child.firstName} reste</TouchButton>
        <TouchButton onClick={() => void validate()} disabled={!chosen}>
          {chosen === "autre"
            ? "Continuer avec une autre personne"
            : chosen
              ? `Valider le départ avec ${chosen.name}`
              : `Départ à ${time}`}
        </TouchButton>
      </div>
    </div>
  );
}

/**
 * Personne non autorisée (maquette « Personne non autorisée ») : le départ reste bloqué tant qu'une personne
 * de la direction n'a pas saisi son code personnel. Hors connexion, le code est vérifié au retour du serveur.
 */
function UnauthorizedScreen({
  child,
  attendanceId,
  onCancel,
  onDone,
}: {
  child: KioskChild;
  attendanceId: string;
  onCancel: () => void;
  onDone: () => void;
}) {
  const { act, today, dismiss, now } = useKiosk();
  const directors = today?.directors ?? [];
  const [director, setDirector] = useState(directors[0] ?? null);
  const [name, setName] = useState("");
  const [relationship, setRelationship] = useState("");
  const [idChecked, setIdChecked] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  const submit = async (pin: string) => {
    if (!name.trim() || !relationship.trim()) {
      setError("Indiquez qui se présente et son lien avec l’enfant, puis faites saisir le code.");
      return;
    }
    if (!director) return;
    setError(null);
    setPending(true);
    try {
      const result = await act({
        type: "DEPART",
        childId: child.id,
        attendanceId,
        pickup: { kind: "AUTRE" },
        override: { personName: name.trim(), relationship: relationship.trim(), idChecked, directorId: director.userId, directorPin: pin },
      });
      if (result?.outcome === "REFUSEE") {
        dismiss(result.clientId);
        setError(result.message ?? "Départ refusé.");
        return;
      }
      onDone();
    } finally {
      setPending(false);
    }
  };

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6 pt-6">
      <div role="alert" className="flex items-start gap-4 rounded-lg border-2 border-danger-700 bg-danger-100 px-6 py-4 text-danger-700">
        <ShieldX aria-hidden className="size-10 shrink-0" />
        <div className="grow">
          <h1 className="m-0 text-touch-title">Personne NON autorisée</h1>
          <p className="m-0 text-touch-body">
            Cette personne ne figure pas sur la liste de {child.firstName} {child.lastName}. Gardez {child.firstName} avec
            vous : le départ est bloqué tant que la direction ne l’a pas validé.
          </p>
        </div>
        <span className="text-numeric-lg tabular-nums">{hm(now().toISOString())}</span>
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <div className="flex flex-col gap-5">
          <section aria-labelledby="qui-se-presente" className="flex flex-col gap-3">
            <h2 id="qui-se-presente" className="m-0 text-touch-label">Qui se présente ?</h2>
            <label className="flex flex-col gap-2 text-touch-label font-normal">
              Prénom et nom
              <input value={name} onChange={(e) => setName(e.target.value)} maxLength={170} className="h-touch rounded-md border border-input bg-card px-4 text-touch-body" />
            </label>
            <label className="flex flex-col gap-2 text-touch-label font-normal">
              Lien avec {child.firstName}
              <input value={relationship} onChange={(e) => setRelationship(e.target.value)} maxLength={120} className="h-touch rounded-md border border-input bg-card px-4 text-touch-body" />
            </label>
            <label className="flex items-center gap-3 text-touch-label font-normal">
              <input type="checkbox" checked={idChecked} onChange={(e) => setIdChecked(e.target.checked)} className="size-6" />
              Pièce d’identité présentée et vérifiée
            </label>
          </section>
          <section aria-labelledby="parents" className="flex flex-col gap-2">
            <h2 id="parents" className="m-0 text-touch-label">Parents à joindre</h2>
            <ul className="m-0 flex list-none flex-col gap-2 p-0">
              {child.parents.map((p) => (
                <li key={p.name} className="flex items-center justify-between rounded-md border border-border bg-card px-4 py-3">
                  <span className="text-touch-label">
                    {p.name} <span className="font-normal text-muted-foreground">{p.relationship}</span>
                  </span>
                  <a href={`tel:${p.phone.replace(/\s/g, "")}`} className="flex items-center gap-2 text-touch-label text-lagon-700">
                    <Phone aria-hidden className="size-5" />
                    {p.phone}
                  </a>
                </li>
              ))}
              {child.parents.length === 0 && <li className="text-touch-body text-muted-foreground">Aucun téléphone enregistré.</li>}
            </ul>
          </section>
        </div>

        <section aria-labelledby="validation" className="flex flex-col items-center gap-3 rounded-lg border border-border bg-card p-5">
          <h2 id="validation" className="m-0 self-start text-touch-label">Validation de la directrice</h2>
          {directors.length === 0 ? (
            <p className="m-0 text-touch-body text-muted-foreground">
              Aucune personne de la direction n’a de code PIN : le départ se valide au bureau.
            </p>
          ) : (
            <>
              {directors.length > 1 && (
                <div className="flex flex-wrap gap-2 self-start">
                  {directors.map((d) => (
                    <button
                      key={d.userId}
                      type="button"
                      aria-pressed={director?.userId === d.userId}
                      onClick={() => setDirector(d)}
                      className={cn("h-touch rounded-full border px-4 text-touch-label", director?.userId === d.userId ? "border-lagon-600 bg-lagon-100 text-lagon-700" : "border-border")}
                    >
                      {d.displayName}
                    </button>
                  ))}
                </div>
              )}
              <p className="m-0 self-start text-touch-body text-muted-foreground">
                Code personnel de {director?.displayName}, à saisir par elle
              </p>
              <PinPad label={`Code personnel de ${director?.displayName}`} onComplete={submit} error={error} pending={pending} />
            </>
          )}
          <RemoteApproval attendanceId={attendanceId} childFirstName={child.firstName} personName={name}
            relationship={relationship} idChecked={idChecked} onApproved={onDone} />
        </section>
      </div>

      <TouchButton variant="secondary" className="self-start" onClick={onCancel}>
        Annuler le départ, {child.firstName} reste
      </TouchButton>
    </div>
  );
}

/**
 * Récap de la journée au départ (lot 1) : ce que l'équipe a noté pour l'enfant, à raconter au parent qui vient le
 * chercher. Hors connexion, la liste déjà chargée reste affichée ; sans liste, rien. Écart : pas dans la maquette
 * « Départ, choix de la personne ».
 */
function DayRecap({ childId, firstName }: { childId: string; firstName: string }) {
  const journal = useQuery({ queryKey: JOURNAL_KEY, queryFn: ({ signal }) => kioskApi.journal(signal), refetchInterval: 60_000 });
  const entries = (journal.data?.entries ?? [])
    .filter((e) => e.children.some((c) => c.id === childId))
    .sort((a, b) => a.occurredAt.localeCompare(b.occurredAt));
  if (entries.length === 0) return null;
  const photos = entries.filter((e) => e.kind === "PHOTO").length;
  const videos = entries.filter((e) => e.kind === "VIDEO").length;
  const told = entries.filter((e) => e.kind !== "PHOTO" && e.kind !== "VIDEO");
  const taken = [photos > 0 && `${photos} photo${photos > 1 ? "s" : ""}`, videos > 0 && `${videos} vidéo${videos > 1 ? "s" : ""}`]
    .filter(Boolean).join(" et ");
  return (
    <section aria-labelledby="journee" className="flex flex-col gap-2 rounded-lg border border-border bg-card p-5">
      <h2 id="journee" className="m-0 text-touch-label">La journée de {firstName}</h2>
      <ul className="m-0 flex list-none flex-col gap-1 p-0 text-touch-body">
        {told.map((e) => {
          const summary = entrySummary(e);
          return (
            <li key={e.id} className="flex gap-3">
              <span className="w-14 shrink-0 tabular-nums text-muted-foreground">{hm(e.occurredAt)}</span>
              <span><strong className="font-semibold">{kindLabels[e.kind]}</strong>{summary ? ` · ${summary}` : ""}</span>
            </li>
          );
        })}
      </ul>
      {taken && (
        <p className="m-0 text-touch-body text-muted-foreground">
          {taken} {photos + videos > 1 ? "prises" : "prise"} aujourd’hui
        </p>
      )}
    </section>
  );
}
