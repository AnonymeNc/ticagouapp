import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Camera, CloudOff, Moon, Palette, Baby, MessageSquareText, Pill, UtensilsCrossed, CalendarX2 } from "lucide-react";
import { errorMessage } from "@/lib/api";
import { cn } from "@/lib/utils";
import { kioskApi, newId, type EntryKind, type JournalEntry, type KioskChild } from "./api";
import { BackButton, Chip, HealthBadges, Initials, TouchButton, hm } from "./components";
import { useKiosk } from "./KioskContext";
import { shrinkPhoto } from "./photo";
import { openAttendance } from "./view";

/**
 * Onglet « Journée » de la tablette : transmissions de la journée (repas et biberon, sieste, change, médicament,
 * activité, photo, mot) pour un ou plusieurs enfants présents ; un médicament, pour un seul (ADR 0014). Écart à la maquette : pas d'écran dédié dans « Pointage tablette accueil » ; le
 * style reprend la grille de pointage. Hors connexion, tout sauf les photos part par la file d'attente.
 */
export const JOURNAL_KEY = ["kiosk-journal"] as const;

export const kindLabels: Record<EntryKind, string> = {
  REPAS: "Repas",
  SIESTE: "Sieste",
  CHANGE: "Change",
  MEDICAMENT: "Médicament",
  ACTIVITE: "Activité",
  PHOTO: "Photo",
  MOT: "Mot pour les parents",
};

const kindIcons: Record<EntryKind, typeof Moon> = {
  REPAS: UtensilsCrossed,
  SIESTE: Moon,
  CHANGE: Baby,
  MEDICAMENT: Pill,
  ACTIVITE: Palette,
  PHOTO: Camera,
  MOT: MessageSquareText,
};

const meals = [
  ["COLLATION", "Collation"],
  ["DEJEUNER", "Déjeuner"],
  ["GOUTER", "Goûter"],
  ["BIBERON", "Biberon"],
] as const;
const quantities = [
  ["TOUT", "Tout mangé"],
  ["BIEN", "Bien mangé"],
  ["PEU", "Un peu"],
  ["RIEN", "Rien mangé"],
] as const;
const bottleQuantities = [
  ["TOUT", "Tout bu"],
  ["BIEN", "Presque tout"],
  ["PEU", "Un peu"],
  ["RIEN", "Rien bu"],
] as const;
const changes = [
  ["MOUILLEE", "Couche mouillée"],
  ["SELLES", "Selles"],
  ["PROPRE", "Propre"],
  ["POT", "Pot / toilettes"],
] as const;

/** Résumé lisible d'une transmission (liste du jour, bureau). */
export function entrySummary(entry: Pick<JournalEntry, "kind" | "details" | "text">) {
  const d = entry.details;
  const label = (list: readonly (readonly [string, string])[], value?: string) => list.find(([v]) => v === value)?.[1];
  const parts: (string | undefined)[] = [];
  if (entry.kind === "REPAS") {
    const bottle = d.meal === "BIBERON";
    parts.push(label(meals, d.meal), d.volumeMl && `${d.volumeMl} ml`, label(bottle ? bottleQuantities : quantities, d.quantity)?.toLowerCase());
  }
  if (entry.kind === "MEDICAMENT") parts.push(d.medicine, d.dose);
  if (entry.kind === "SIESTE") parts.push(d.to ? `de ${d.from} à ${d.to}` : `endormi(e) à ${d.from}`);
  if (entry.kind === "CHANGE") parts.push(label(changes, d.change));
  if (entry.text) parts.push(entry.text);
  return parts.filter(Boolean).join(" · ");
}

function nowTime(date: Date) {
  return hm(date.toISOString());
}

export function JournalScreen({ onDone }: { onDone: () => void }) {
  const { today, session, online, act, now } = useKiosk();
  const queryClient = useQueryClient();
  const journal = useQuery({ queryKey: JOURNAL_KEY, queryFn: ({ signal }) => kioskApi.journal(signal), refetchInterval: 60_000 });
  const [selected, setSelected] = useState<string[]>([]);
  const [kind, setKind] = useState<EntryKind | null>(null);
  const [details, setDetails] = useState<Record<string, string>>({});
  const [text, setText] = useState("");
  // Heure modifiée pour une saisie faite après coup ; sinon, celle de l'enregistrement.
  const [time, setTime] = useState<string | null>(null);
  const [shared, setShared] = useState(true);
  const [photo, setPhoto] = useState<Blob | null>(null);
  const [status, setStatus] = useState<{ tone: "ok" | "error"; text: string } | null>(null);
  const [sending, setSending] = useState(false);

  const present = (today?.children ?? []).filter((c) => openAttendance(c));
  const consents = new Set(journal.data?.photoConsents ?? []);
  const photoAllowed = selected.length > 0 && selected.every((id) => consents.has(id));

  const reset = () => {
    setKind(null);
    setDetails({});
    setText("");
    setTime(null);
    setPhoto(null);
    setShared(true);
  };

  const choose = (next: EntryKind) => {
    reset();
    setKind(next);
    setStatus(null);
    if (next === "SIESTE") setDetails({ from: nowTime(now()) });
    // Une photo ne se partage que sur décision de l'éducatrice.
    if (next === "PHOTO") setShared(false);
  };

  const toggle = (child: KioskChild) =>
    setSelected((list) => (list.includes(child.id) ? list.filter((id) => id !== child.id) : [...list, child.id]));

  const ready =
    kind !== null &&
    selected.length > 0 &&
    (kind !== "REPAS" || (details.meal && details.quantity)) &&
    (kind !== "SIESTE" || details.from) &&
    (kind !== "CHANGE" || details.change) &&
    (kind !== "MEDICAMENT" || (details.medicine?.trim() && selected.length === 1)) &&
    ((kind !== "ACTIVITE" && kind !== "MOT") || text.trim().length > 0) &&
    (kind !== "PHOTO" || photo !== null);

  const save = async () => {
    if (!kind || !session) return;
    const entry = {
      kind,
      childIds: selected,
      details,
      text: text.trim() || null,
      shared: kind === "PHOTO" ? shared && photoAllowed : shared,
      ...(time ? { time } : {}),
    };
    setSending(true);
    try {
      if (kind === "PHOTO") {
        const result = await kioskApi.photo(
          {
            clientId: newId(),
            type: "TRANSMISSION",
            occurredAt: now().toISOString(),
            offline: false,
            userId: session.userId,
            sessionId: session.sessionId,
            entry,
          },
          photo!,
        );
        if (result.outcome === "REFUSEE") throw new Error(result.message ?? "Photo refusée.");
      } else {
        await act({ type: "TRANSMISSION", entry });
      }
      const names = present.filter((c) => selected.includes(c.id)).map((c) => c.firstName).join(", ");
      setStatus({ tone: "ok", text: `${kindLabels[kind]} enregistré${kind === "PHOTO" ? "e" : ""} pour ${names}.` });
      reset();
      setSelected([]);
      void queryClient.invalidateQueries({ queryKey: JOURNAL_KEY });
    } catch (error) {
      setStatus({ tone: "error", text: errorMessage(error) });
    } finally {
      setSending(false);
    }
  };

  const absences = journal.data?.reportedAbsences ?? [];
  const childName = (id: string) => today?.children.find((c) => c.id === id)?.firstName ?? "Un enfant";

  return (
    <div className="flex flex-col gap-5 pt-5">
      <div className="flex items-center gap-4">
        <BackButton label="Pointage" onClick={onDone} />
        <h1 className="m-0 grow text-touch-title">Journée des enfants</h1>
      </div>

      {absences.length > 0 && (
        <div role="status" className="flex items-start gap-3 rounded-lg bg-info-100 px-5 py-3 text-info-700">
          <CalendarX2 aria-hidden className="mt-1 size-6 shrink-0" />
          <span className="text-touch-label font-normal">
            <strong className="font-semibold">Absences signalées par les parents :</strong>{" "}
            {absences.map((a) => `${childName(a.childId)}${a.reason ? ` (${a.reason})` : ""}`).join(", ")}
          </span>
        </div>
      )}

      <section aria-labelledby="journal-children" className="flex flex-col gap-3">
        <h2 id="journal-children" className="m-0 text-touch-label">1. Pour qui ? <span className="font-normal text-muted-foreground">(plusieurs possibles)</span></h2>
        {present.length === 0 ? (
          <p className="m-0 text-touch-body text-muted-foreground">Aucun enfant présent pour le moment.</p>
        ) : (
          <ul className="m-0 grid list-none grid-cols-2 gap-3 p-0 md:grid-cols-3 xl:grid-cols-4">
            {present.map((c) => {
              const on = selected.includes(c.id);
              return (
                <li key={c.id}>
                  <button
                    type="button"
                    aria-pressed={on}
                    onClick={() => toggle(c)}
                    className={cn(
                      "flex min-h-touch-lg w-full items-center gap-3 rounded-lg border bg-card px-4 py-2 text-left",
                      on ? "border-2 border-lagon-600 bg-lagon-50" : "border-border",
                    )}
                  >
                    <Initials name={`${c.firstName} ${c.lastName}`} />
                    <span className="flex grow flex-col gap-1">
                      <span className="text-touch-label">{c.firstName}</span>
                      <HealthBadges health={c.health} />
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </section>

      <section aria-labelledby="journal-kind" className="flex flex-col gap-3">
        <h2 id="journal-kind" className="m-0 text-touch-label">2. Quoi ?</h2>
        <div className="flex flex-wrap gap-3">
          {(Object.keys(kindLabels) as EntryKind[]).map((k) => {
            const Icon = kindIcons[k];
            const disabled = k === "PHOTO" && !online;
            return (
              <button
                key={k}
                type="button"
                aria-pressed={kind === k}
                disabled={disabled}
                onClick={() => choose(k)}
                className={cn(
                  "flex h-touch-lg items-center gap-2 rounded-lg border px-5 text-touch-label disabled:opacity-50",
                  kind === k ? "border-2 border-lagon-600 bg-lagon-100 text-lagon-700" : "border-border bg-card",
                )}
              >
                {disabled ? <CloudOff aria-hidden className="size-6" /> : <Icon aria-hidden className="size-6" />}
                {kindLabels[k]}
              </button>
            );
          })}
        </div>
        {!online && <p className="m-0 text-base text-muted-foreground">Photos indisponibles hors connexion ; le reste est gardé et envoyé au retour du serveur.</p>}
      </section>

      {kind && (
        <section aria-label={kindLabels[kind]} className="flex flex-col gap-4 rounded-lg border border-border-subtle bg-card p-5">
          {kind === "REPAS" && (
            <>
              <div className="flex flex-wrap gap-2">
                {meals.map(([value, label]) => (
                  <Chip key={value} selected={details.meal === value}
                    onClick={() => setDetails({ meal: value, ...(details.quantity ? { quantity: details.quantity } : {}) })}>{label}</Chip>
                ))}
              </div>
              <div className="flex flex-wrap gap-2">
                {(details.meal === "BIBERON" ? bottleQuantities : quantities).map(([value, label]) => (
                  <Chip key={value} selected={details.quantity === value} onClick={() => setDetails({ ...details, quantity: value })}>{label}</Chip>
                ))}
              </div>
              {details.meal === "BIBERON" && (
                <label className="flex w-fit flex-col gap-1 text-touch-label">
                  Quantité bue en ml <span className="font-normal text-muted-foreground">(facultatif)</span>
                  <input inputMode="numeric" maxLength={3} value={details.volumeMl ?? ""}
                    onChange={(e) => setDetails({ ...details, volumeMl: e.target.value.replace(/\D/g, "") })}
                    className="h-touch w-32 rounded-md border border-input px-3 text-touch-body" />
                </label>
              )}
            </>
          )}
          {kind === "SIESTE" && (
            <div className="flex flex-wrap items-end gap-4">
              <label className="flex flex-col gap-1 text-touch-label">
                Endormi(e) à
                <input type="time" value={details.from ?? ""} onChange={(e) => setDetails({ ...details, from: e.target.value })}
                  className="h-touch rounded-md border border-input px-3 text-touch-body" />
              </label>
              <label className="flex flex-col gap-1 text-touch-label">
                Réveil <span className="font-normal text-muted-foreground">(facultatif)</span>
                <input type="time" value={details.to ?? ""} onChange={(e) => setDetails({ ...details, to: e.target.value })}
                  className="h-touch rounded-md border border-input px-3 text-touch-body" />
              </label>
              <TouchButton variant="secondary" onClick={() => setDetails({ ...details, to: nowTime(now()) })}>Réveillé(e) maintenant</TouchButton>
            </div>
          )}
          {kind === "CHANGE" && (
            <div className="flex flex-wrap gap-2">
              {changes.map(([value, label]) => (
                <Chip key={value} selected={details.change === value} onClick={() => setDetails({ change: value })}>{label}</Chip>
              ))}
            </div>
          )}
          {kind === "MEDICAMENT" && (
            <>
              <div className="flex flex-wrap gap-4">
                <label className="flex grow flex-col gap-1 text-touch-label">
                  Médicament donné
                  <input maxLength={100} value={details.medicine ?? ""} onChange={(e) => setDetails({ ...details, medicine: e.target.value })}
                    className="h-touch rounded-md border border-input px-3 text-touch-body" />
                </label>
                <label className="flex flex-col gap-1 text-touch-label">
                  Dose <span className="font-normal text-muted-foreground">(facultatif)</span>
                  <input maxLength={50} value={details.dose ?? ""} onChange={(e) => setDetails({ ...details, dose: e.target.value })}
                    className="h-touch rounded-md border border-input px-3 text-touch-body" />
                </label>
              </div>
              {selected.length > 1 && <p className="m-0 text-base text-warning-700">Un médicament se note enfant par enfant : gardez un seul enfant sélectionné.</p>}
            </>
          )}
          {(kind === "REPAS" || kind === "MEDICAMENT") && (
            <label className="flex w-fit flex-col gap-1 text-touch-label">
              Heure
              <input type="time" value={time ?? nowTime(now())} onChange={(e) => setTime(e.target.value)}
                className="h-touch rounded-md border border-input px-3 text-touch-body" />
            </label>
          )}
          {kind === "PHOTO" && (
            <label className="flex h-touch-lg w-fit cursor-pointer items-center gap-2 rounded-lg border border-border bg-card px-6 text-touch-label shadow-1">
              <Camera aria-hidden className="size-6" />
              {photo ? "Reprendre la photo" : "Prendre la photo"}
              <input
                type="file"
                accept="image/*"
                capture="environment"
                className="sr-only"
                onChange={async (e) => {
                  const file = e.target.files?.[0];
                  if (file) setPhoto(await shrinkPhoto(file).catch(() => file));
                }}
              />
            </label>
          )}
          <label className="flex flex-col gap-1 text-touch-label">
            {kind === "ACTIVITE" ? "Activité" : kind === "MOT" ? "Mot pour les parents" : "Précision"}
            {kind !== "ACTIVITE" && kind !== "MOT" && <span className="font-normal text-muted-foreground"> (facultatif)</span>}
            <textarea value={text} maxLength={500} rows={2} onChange={(e) => setText(e.target.value)}
              className="rounded-md border border-input p-3 text-touch-body" />
          </label>
          <label className="flex items-center gap-3 text-touch-label font-normal">
            <input
              type="checkbox"
              className="size-7"
              checked={kind === "PHOTO" ? shared && photoAllowed : shared}
              disabled={kind === "PHOTO" && !photoAllowed}
              onChange={(e) => setShared(e.target.checked)}
            />
            Partager avec les familles sur le portail
          </label>
          {kind === "PHOTO" && selected.length > 0 && !photoAllowed && (
            <p className="m-0 text-base text-warning-700">
              Partage impossible : l’autorisation de photographier manque pour{" "}
              {present.filter((c) => selected.includes(c.id) && !consents.has(c.id)).map((c) => c.firstName).join(", ")}. La
              photo reste dans le journal de la crèche.
            </p>
          )}
          <TouchButton disabled={!ready || sending} onClick={() => void save()} className="self-start">
            Enregistrer
          </TouchButton>
        </section>
      )}

      {status && (
        <p role="status" className={cn("m-0 rounded-md px-4 py-3 text-touch-label font-normal",
          status.tone === "ok" ? "bg-success-100 text-success-700" : "bg-danger-100 text-danger-700")}>
          {status.text}
        </p>
      )}

      <section aria-labelledby="journal-today" className="flex flex-col gap-2">
        <h2 id="journal-today" className="m-0 text-touch-label">Aujourd’hui</h2>
        {(journal.data?.entries ?? []).length === 0 ? (
          <p className="m-0 text-touch-body text-muted-foreground">Aucune transmission pour le moment.</p>
        ) : (
          <ul className="m-0 flex list-none flex-col gap-2 p-0">
            {[...journal.data!.entries].reverse().map((e) => (
              <li key={e.id} className="flex items-center gap-4 rounded-md border border-border-subtle bg-card px-4 py-3 text-touch-body">
                <time className="tabular-nums text-muted-foreground">{hm(e.occurredAt)}</time>
                <span className="font-semibold">{kindLabels[e.kind]}</span>
                <span className="grow">{e.children.map((c) => c.firstName).join(", ")} · {entrySummary(e)}</span>
                {!e.shared && <span className="text-base text-muted-foreground">non partagé</span>}
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
