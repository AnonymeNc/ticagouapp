import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Baby, CalendarX2, IdCard, NotebookPen, Pill, Plus } from "lucide-react";
import { useModules } from "@/features/license/useModules";
import { useNow } from "@/hooks/useNow";
import { cn } from "@/lib/utils";
import { kioskApi, newId, type KioskChild } from "./api";
import { ArrivalScreen } from "./ArrivalScreen";
import { HealthBadges, Initials, TouchButton, hhmm, hm } from "./components";
import { DepartureScreen } from "./DepartureScreen";
import { canPoint, useKiosk } from "./KioskContext";
import { JOURNAL_KEY, JournalScreen } from "./JournalScreen";
import { StaffScreen } from "./StaffScreen";
import { childStatus, gridChildren, minutes, openAttendance, type ChildStatus } from "./view";

type Screen =
  | { kind: "grid" }
  | { kind: "arrival"; childId: string; attendanceId: string }
  | { kind: "departure"; childId: string }
  | { kind: "staff" }
  | { kind: "journal" }
  | { kind: "unplanned" };

/** Grille de pointage par section (maquette « Grille de pointage ») et enchaînement des écrans. */
export function KioskHomePage() {
  const { today, todayError, session, act } = useKiosk();
  const [screen, setScreen] = useState<Screen>({ kind: "grid" });
  const [section, setSection] = useState<string | null>(null);
  const back = () => setScreen({ kind: "grid" });

  if (!today) {
    return (
      <p className="pt-8 text-touch-body text-muted-foreground">
        {todayError ? "Le serveur ne répond pas et aucun pointage du jour n’est enregistré sur cette tablette." : "Chargement…"}
      </p>
    );
  }

  const arrive = (child: KioskChild) => {
    const attendanceId = newId();
    void act({ type: "ARRIVEE", childId: child.id, attendanceId, sectionId: child.sectionId });
    setScreen({ kind: "arrival", childId: child.id, attendanceId });
  };

  const child = "childId" in screen ? today.children.find((c) => c.id === screen.childId) : undefined;
  if (screen.kind === "arrival" && child) return <ArrivalScreen child={child} attendanceId={screen.attendanceId} onDone={back} />;
  if (screen.kind === "departure" && child) return <DepartureScreen child={child} onDone={back} />;
  if (screen.kind === "staff") return <StaffScreen onDone={back} />;
  if (screen.kind === "journal") return <JournalScreen onDone={back} />;
  if (screen.kind === "unplanned") return <UnplannedPicker section={section} onPick={arrive} onCancel={back} />;

  return (
    <Grid
      section={section}
      onSection={setSection}
      readOnly={!canPoint(session)}
      onChild={(c, status) => {
        if (status === "present") setScreen({ kind: "departure", childId: c.id });
        else if (status !== "parti") arrive(c);
      }}
      onStaff={() => setScreen({ kind: "staff" })}
      onJournal={() => setScreen({ kind: "journal" })}
      onUnplanned={() => setScreen({ kind: "unplanned" })}
    />
  );
}

const statusOrder: Record<ChildStatus, number> = { retard: 0, attendu: 1, present: 2, hors: 3, parti: 4 };

function Grid({
  section,
  onSection,
  readOnly,
  onChild,
  onStaff,
  onJournal,
  onUnplanned,
}: {
  section: string | null;
  onSection: (id: string | null) => void;
  readOnly: boolean;
  onChild: (child: KioskChild, status: ChildStatus) => void;
  onStaff: () => void;
  onJournal: () => void;
  onUnplanned: () => void;
}) {
  const { today, act, now, online } = useKiosk();
  const modules = useModules("device");
  useNow();
  // Absences signalées sur le portail parents (rien quand le portail n'est pas activé).
  const journal = useQuery({ queryKey: JOURNAL_KEY, queryFn: ({ signal }) => kioskApi.journal(signal), enabled: online, refetchInterval: 60_000 });
  const reported = journal.data?.reportedAbsences ?? [];
  const nowMinutes = (() => {
    const [h, m] = hm(now().toISOString()).split(":").map(Number);
    return h * 60 + m;
  })();
  const all = gridChildren(today!);
  const inSection = all.filter((c) => section === null || c.sectionId === section);
  const statuses = new Map(inSection.map((c) => [c.id, childStatus(c, nowMinutes)]));
  const present = inSection.filter((c) => statuses.get(c.id) === "present").length;
  const departed = inSection.filter((c) => statuses.get(c.id) === "parti").length;
  const expected = inSection.filter((c) => c.planned).length;
  const sorted = [...inSection].sort((a, b) => a.firstName.localeCompare(b.firstName, "fr"));
  const sectionName = today!.sections.find((s) => s.id === section)?.name;

  const reminders = useMemo(
    () =>
      today!.children.flatMap((c) => {
        const open = openAttendance(c);
        return (open?.instructions ?? [])
          .filter((i) => i.kind === "MEDICAMENT" && !i.givenAt && i.medicationAt && minutes(i.medicationAt) - 15 <= nowMinutes)
          .map((i) => ({ child: c, instruction: i }));
      }),
    [today, nowMinutes],
  );

  const tab = (id: string | null, label: string, count: number) => (
    <button
      key={id ?? "all"}
      type="button"
      aria-pressed={section === id}
      onClick={() => onSection(id)}
      className={cn(
        "flex h-touch items-center gap-2 rounded-full px-5 text-touch-label",
        section === id ? "bg-card text-lagon-700 shadow-1" : "text-foreground",
      )}
    >
      {label}
      <span className="rounded-full bg-muted px-2 text-base text-muted-foreground">{count}</span>
    </button>
  );

  return (
    <div className="flex flex-col gap-5 pt-5">
      {reminders.map(({ child, instruction }) => (
        <div key={instruction.id} role="alert" className="flex items-center gap-4 rounded-lg bg-warning-100 px-5 py-3 text-warning-700">
          <Pill aria-hidden className="size-7 shrink-0" />
          <span className="grow text-touch-label font-normal">
            <strong className="font-semibold">Médicament pour {child.firstName} à {hhmm(instruction.medicationAt!)}</strong>
            {instruction.medicationDose && <> : {instruction.medicationDose}</>}
          </span>
          {!readOnly && (
            <TouchButton variant="secondary" onClick={() => void act({ type: "MEDICAMENT_DONNE", childId: child.id, instructionId: instruction.id })}>
              Médicament donné
            </TouchButton>
          )}
        </div>
      ))}

      <div className="flex flex-wrap items-center gap-4">
        <nav aria-label="Sections" className="flex flex-wrap gap-1 rounded-[20px] bg-muted p-1">
          {tab(null, "Toutes", all.length)}
          {today!.sections.map((s) => tab(s.id, s.name, all.filter((c) => c.sectionId === s.id).length))}
        </nav>
        <p className="m-0 grow text-touch-body" aria-live="polite">
          <strong className="text-numeric-lg">{present}</strong> / {expected} présents / attendus
          {departed > 0 && <> · {departed} parti{departed > 1 ? "s" : ""}</>}
        </p>
        {!readOnly && modules.has("PORTAIL_PARENTS") && (
          <TouchButton variant="secondary" onClick={onJournal}>
            <NotebookPen aria-hidden className="size-6" />
            Journée
          </TouchButton>
        )}
        <TouchButton variant="secondary" onClick={onStaff}>
          <IdCard aria-hidden className="size-6" />
          Pointage personnel
        </TouchButton>
      </div>

      {reported.length > 0 && (
        <div role="status" className="flex items-start gap-3 rounded-lg bg-info-100 px-5 py-3 text-info-700">
          <CalendarX2 aria-hidden className="mt-1 size-6 shrink-0" />
          <span className="text-touch-label font-normal">
            <strong className="font-semibold">Absence signalée par les parents :</strong>{" "}
            {reported
              .map((a) => `${today!.children.find((c) => c.id === a.childId)?.firstName ?? "un enfant"}${a.reason ? ` (${a.reason})` : ""}`)
              .join(", ")}
          </span>
        </div>
      )}

      {readOnly && (
        <p className="m-0 rounded-md bg-muted px-4 py-3 text-touch-label font-normal text-muted-foreground">
          Consultation seulement : le pointage des enfants est réservé à la direction et aux éducatrices.
        </p>
      )}

      {sorted.length === 0 ? (
        <div className="flex flex-col items-center gap-4 py-12 text-center">
          <Baby aria-hidden className="size-14 text-lagon-600" strokeWidth={1.5} />
          <h2 className="m-0 text-touch-title">
            {sectionName ? `Aucun enfant attendu en section ${sectionName} aujourd’hui` : "Aucun enfant attendu aujourd’hui"}
          </h2>
          <p className="m-0 max-w-2xl text-touch-body text-muted-foreground">
            Le planning du jour ne prévoit personne {sectionName ? "dans cette section" : ""}. Un accueil imprévu reste
            possible : il sera ajouté à la grille et à la facturation du mois.
          </p>
          <div className="flex gap-3">
            {section && <TouchButton variant="secondary" onClick={() => onSection(null)}>Voir toutes les sections</TouchButton>}
            {!readOnly && <TouchButton onClick={onUnplanned}>Accueillir un enfant hors planning</TouchButton>}
          </div>
        </div>
      ) : (
        <ul className="m-0 grid list-none grid-cols-2 gap-4 p-0 md:grid-cols-3 xl:grid-cols-4">
          {sorted
            .sort((a, b) => statusOrder[statuses.get(a.id)!] - statusOrder[statuses.get(b.id)!])
            .map((c) => (
              <li key={c.id}>
                <ChildTile child={c} status={statuses.get(c.id)!} disabled={readOnly} onClick={() => onChild(c, statuses.get(c.id)!)} />
              </li>
            ))}
        </ul>
      )}

      {sorted.length > 0 && !readOnly && (
        <TouchButton variant="secondary" className="self-start" onClick={onUnplanned}>
          <Plus aria-hidden className="size-6" />
          Accueillir un enfant hors planning
        </TouchButton>
      )}
    </div>
  );
}

function ChildTile({ child, status, disabled, onClick }: { child: KioskChild; status: ChildStatus; disabled: boolean; onClick: () => void }) {
  const open = openAttendance(child);
  const last = child.attendances.at(-1);
  const pending = child.attendances.some((a) => a.pending);
  const label =
    status === "present" ? `Présent ${hm(open!.arrivedAt)}`
      : status === "parti" ? `Parti ${hm(last!.departedAt!)}`
        : status === "retard" ? `En retard · prévu ${hhmm(child.expectedAt!)}`
          : status === "attendu" ? `Attendu ${hhmm(child.expectedAt!)}`
            : "Hors planning";
  const aria =
    status === "present" ? `présent depuis ${hm(open!.arrivedAt)}. Toucher pour enregistrer le départ`
      : status === "parti" ? `parti à ${hm(last!.departedAt!)}`
        : status === "retard" ? `en retard, prévu à ${hhmm(child.expectedAt!)}. Toucher pour enregistrer l’arrivée`
          : `attendu${child.expectedAt ? ` à ${hhmm(child.expectedAt)}` : ""}. Toucher pour enregistrer l’arrivée`;
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled || status === "parti"}
      aria-label={`${child.firstName} ${child.lastName}, ${aria}`}
      className={cn(
        "flex min-h-36 w-full flex-col gap-2 rounded-lg border-2 p-4 text-left shadow-1",
        status === "present" && "border-success-600 bg-success-100",
        status === "retard" && "border-warning-500 bg-card",
        (status === "attendu" || status === "hors") && "border-border bg-card",
        status === "parti" && "border-transparent bg-muted opacity-70",
      )}
    >
      <span className="flex items-center gap-3">
        <Initials name={`${child.firstName} ${child.lastName}`} tone={status === "parti" ? "muted" : "lagon"} />
        <span className="flex min-w-0 flex-col">
          <span className="truncate text-touch-label">{child.firstName}</span>
          <span className="truncate text-base text-muted-foreground">{child.lastName}</span>
        </span>
      </span>
      {status !== "parti" && <HealthBadges health={child.health} />}
      <span
        className={cn(
          "mt-auto text-base font-semibold",
          status === "present" && "text-success-700",
          status === "retard" && "text-warning-700",
          (status === "attendu" || status === "hors" || status === "parti") && "text-muted-foreground",
        )}
      >
        {label}
        {pending && <span className="ml-2 font-normal">· en attente d’envoi</span>}
      </span>
    </button>
  );
}

/** Accueil hors planning : enfants inscrits non attendus aujourd'hui. */
function UnplannedPicker({ section, onPick, onCancel }: { section: string | null; onPick: (c: KioskChild) => void; onCancel: () => void }) {
  const { today } = useKiosk();
  const candidates = today!.children
    .filter((c) => !openAttendance(c) && !c.planned && (section === null || c.sectionId === section))
    .sort((a, b) => a.firstName.localeCompare(b.firstName, "fr"));
  return (
    <div className="flex flex-col gap-5 pt-5">
      <h1 className="m-0 text-touch-title">Accueillir un enfant hors planning</h1>
      <p className="m-0 text-touch-body text-muted-foreground">
        L’accueil sera ajouté à la grille et à la facturation du mois.
      </p>
      {candidates.length === 0 && <p className="text-touch-body">Tous les enfants inscrits sont déjà sur la grille.</p>}
      <ul className="m-0 grid list-none grid-cols-2 gap-4 p-0 md:grid-cols-3 xl:grid-cols-4">
        {candidates.map((c) => (
          <li key={c.id}>
            <button type="button" onClick={() => onPick(c)} className="flex w-full items-center gap-3 rounded-lg border border-border bg-card p-4 text-left shadow-1">
              <Initials name={`${c.firstName} ${c.lastName}`} />
              <span className="flex flex-col">
                <span className="text-touch-label">{c.firstName} {c.lastName}</span>
                <span className="text-base text-muted-foreground">
                  {today!.sections.find((s) => s.id === c.sectionId)?.name ?? "Sans section"}
                </span>
              </span>
            </button>
          </li>
        ))}
      </ul>
      <TouchButton variant="secondary" className="self-start" onClick={onCancel}>
        Retour à la grille
      </TouchButton>
    </div>
  );
}
