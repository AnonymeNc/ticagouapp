import { useState } from "react";
import { Check } from "lucide-react";
import { errorMessage, portal } from "../api";
import { Banner, ChildAvatar, Page, TouchButton, cn } from "../components";
import { addDays, dateFr, todayIso, weekdayDate } from "../format";
import { useSession } from "../session";

type When = "aujourdhui" | "demain" | "plusieurs";
const reasons = ["Maladie", "Rendez-vous", "Congés", "Autre"] as const;

/** Vrai si l'heure de Nouméa est avant HH:MM. */
function beforeCutoff(cutoff: string, now = new Date()) {
  const current = now.toLocaleTimeString("fr-FR", { timeZone: "Pacific/Noumea", hour: "2-digit", minute: "2-digit" });
  return current < cutoff;
}

/**
 * Signaler une absence (maquette « Signaler une absence »). Le bandeau sur le repas non facturé ne s'affiche que si la
 * crèche a réglé une heure limite (Paramètres › Portail parents) : sinon, aucune promesse n'est faite aux parents.
 * L'absence part à la crèche, qui la confirme ; seule l'équipe voit le motif.
 */
export function AbsencePage() {
  const { family, child } = useSession();
  const today = todayIso();
  const [when, setWhen] = useState<When>("demain");
  const [from, setFrom] = useState(addDays(today, 1));
  const [to, setTo] = useState(addDays(today, 2));
  const [reason, setReason] = useState<(typeof reasons)[number]>("Maladie");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState<string | null>(null);

  if (!child) return <Page title="Signaler une absence"><Banner>Aucun enfant n’est encore partagé par la crèche.</Banner></Page>;

  const days = when === "aujourdhui" ? [today, today] : when === "demain" ? [addDays(today, 1), addDays(today, 1)] : [from, to];
  const cutoff = family.mealAbsenceCutoff;

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const text = [reason, note.trim()].filter(Boolean).join(" — ");
      await portal.reportAbsence({ structureId: family.structureId, childId: child.id, fromDay: days[0], toDay: days[1], reason: text });
      setSent(days[0] === days[1] ? `le ${dateFr(days[0])}` : `du ${dateFr(days[0])} au ${dateFr(days[1])}`);
      setNote("");
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const choice = (id: When, label: string, date: string) => (
    <button key={id} type="button" aria-pressed={when === id} onClick={() => setWhen(id)}
      className={cn("flex min-h-[60px] w-full items-center gap-3 rounded-lg px-4 py-2 text-left",
        when === id ? "border-2 border-lagon-600 bg-lagon-100" : "border border-border-control bg-surface")}>
      <span className="flex grow flex-col">
        <span className="text-[17px] font-semibold">{label}</span>
        <span className="tabular text-[15px] text-ink-muted">{date}</span>
      </span>
      {when === id ? (
        <span className="flex size-7 items-center justify-center rounded-full bg-lagon-600 text-white"><Check aria-hidden className="size-[18px]" /></span>
      ) : (
        <span className="size-6 rounded-full border-2 border-border-control" />
      )}
    </button>
  );

  return (
    <Page title="Signaler une absence" subtitle="L’équipe sera prévenue tout de suite.">
      {sent ? (
        <>
          <Banner tone="success">C’est envoyé : la crèche est prévenue de l’absence de {child.firstName} {sent}.</Banner>
          <TouchButton variant="secondary" onClick={() => setSent(null)}>Signaler une autre absence</TouchButton>
        </>
      ) : (
        <form className="flex flex-col gap-[22px]" onSubmit={(e) => { e.preventDefault(); void submit(); }}>
          <div className="flex items-center gap-3 rounded-lg border border-border-subtle bg-surface px-3.5 py-3">
            <ChildAvatar child={child} structureId={family.structureId} size={44} />
            <div className="flex flex-col">
              <span className="text-[17px]"><strong className="font-semibold">{child.firstName}</strong> {child.lastInitial ? `${child.lastInitial}.` : ""}</span>
              {child.section && <span className="text-[15px] text-ink-muted">Section {child.section}</span>}
            </div>
          </div>

          <fieldset className="m-0 flex flex-col gap-2.5 border-0 p-0">
            <legend className="mb-2.5 p-0 text-[17px] font-semibold">Quand ?</legend>
            {choice("aujourdhui", "Aujourd’hui", weekdayDate(today))}
            {choice("demain", "Demain", weekdayDate(addDays(today, 1)))}
            {choice("plusieurs", "Plusieurs jours", "Choisir les dates")}
            {when === "plusieurs" && (
              <div className="grid grid-cols-2 gap-3 pt-1">
                <label className="flex flex-col gap-1.5 text-[16px] font-semibold">Du
                  <input type="date" min={today} value={from} onChange={(e) => { setFrom(e.target.value); if (e.target.value > to) setTo(e.target.value); }}
                    className="h-touch rounded-md border border-border-control bg-surface px-3 text-[17px]" />
                </label>
                <label className="flex flex-col gap-1.5 text-[16px] font-semibold">Au
                  <input type="date" min={from} value={to} onChange={(e) => setTo(e.target.value)}
                    className="h-touch rounded-md border border-border-control bg-surface px-3 text-[17px]" />
                </label>
              </div>
            )}
          </fieldset>

          <fieldset className="m-0 flex flex-col border-0 p-0">
            <legend className="mb-2.5 p-0 text-[17px] font-semibold">Pour quelle raison ?</legend>
            <div className="grid grid-cols-2 gap-2.5">
              {reasons.map((r) => (
                <button key={r} type="button" aria-pressed={reason === r} onClick={() => setReason(r)}
                  className={cn("flex min-h-touch items-center justify-center gap-1.5 rounded-full px-3 text-[16px] font-semibold",
                    reason === r ? "border-2 border-lagon-600 bg-lagon-100 text-lagon-700" : "border border-border-control bg-surface")}>
                  {reason === r && <Check aria-hidden className="size-[18px]" />}
                  {r}
                </button>
              ))}
            </div>
            <p className="mt-2 mb-0 text-[15px] text-ink-muted">Seule l’équipe de la crèche voit le motif.</p>
          </fieldset>

          <div className="flex flex-col gap-1.5">
            <label htmlFor="mot-equipe" className="text-[16px] font-semibold">Un mot pour l’équipe <span className="font-normal text-ink-muted">(facultatif)</span></label>
            <textarea id="mot-equipe" rows={3} maxLength={250} value={note} onChange={(e) => setNote(e.target.value)}
              placeholder="Par exemple : fièvre depuis cette nuit, retour prévu jeudi."
              className="rounded-md border border-border-control bg-surface p-3 text-[17px]" />
          </div>

          {cutoff && (
            <Banner>
              Prévenez avant {cutoff} : le repas du jour ne vous sera pas facturé.
              {when === "aujourdhui" && !beforeCutoff(cutoff) && " Pour aujourd’hui, l’heure est passée."}
            </Banner>
          )}
          {error && <Banner tone="danger">{error}</Banner>}
          <TouchButton type="submit" disabled={busy || (when === "plusieurs" && (!from || !to))}>Prévenir la crèche</TouchButton>
        </form>
      )}
    </Page>
  );
}
