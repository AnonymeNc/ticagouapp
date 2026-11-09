import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router";
import { CalendarPlus, ChevronLeft, Clock } from "lucide-react";
import { errorMessage, portal, type BookingData } from "../api";
import { Badge, Banner, Page, TextField, TouchButton } from "../components";
import { addDays, dateFr, todayIso, weekdayDate } from "../format";
import { useSession } from "../session";

const bookingsKey = ["bookings"] as const;
const statusBadge: Record<BookingData["status"], { tone: "info" | "success" | "neutral"; label: string }> = {
  DEMANDEE: { tone: "info", label: "En attente" },
  ACCEPTEE: { tone: "success", label: "Acceptée" },
  REFUSEE: { tone: "neutral", label: "Refusée" },
  ANNULEE: { tone: "neutral", label: "Annulée" },
};
const hm = (time: string) => time.slice(0, 5);

/**
 * Demandes à la crèche (lot 2) : un accueil en plus (un jour hors contrat, à des heures précises), que la direction
 * accepte ou refuse avec un motif ; un retard pour aujourd'hui, affiché sur la tablette de l'équipe. Les règles de
 * délai sont celles de la crèche : une demande trop tardive est refusée d'office avec l'explication.
 */
export function DemandesPage() {
  const { family, child } = useSession();
  const queryClient = useQueryClient();
  const bookings = useQuery({ queryKey: bookingsKey, queryFn: ({ signal }) => portal.bookings(signal), refetchInterval: 60_000 });
  const [day, setDay] = useState(addDays(todayIso(), 2));
  const [arrives, setArrives] = useState("08:00");
  const [leaves, setLeaves] = useState("12:00");
  const [note, setNote] = useState("");
  const [lateAt, setLateAt] = useState("17:45");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState<string | null>(null);

  if (!child) return <Page title="Demandes"><Banner>Aucun enfant n’est encore partagé par la crèche.</Banner></Page>;
  const mine = (bookings.data ?? []).filter((b) => b.structureId === family.structureId && b.data.childId === child.id);

  const run = async (action: () => Promise<unknown>, done: string) => {
    setBusy(true);
    setError(null);
    setSent(null);
    try {
      await action();
      setSent(done);
      setNote("");
      await queryClient.invalidateQueries({ queryKey: bookingsKey });
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Page title="Demandes" subtitle={`Un accueil en plus ou un retard pour ${child.firstName}.`}>
      <Link to="/absence" className="inline-flex min-h-11 items-center gap-1 self-start text-[16px] font-semibold text-lagon-700 no-underline">
        <ChevronLeft aria-hidden className="size-5" />Absence
      </Link>

      <section aria-labelledby="accueil-en-plus" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-surface p-4 shadow-[var(--shadow-1)]">
        <h2 id="accueil-en-plus" className="m-0 flex items-center gap-2 text-[19px] font-semibold">
          <CalendarPlus aria-hidden className="size-5 text-lagon-700" />Demander un accueil en plus
        </h2>
        <TextField label="Jour" type="date" min={todayIso()} value={day} onChange={(e) => setDay(e.target.value)} hint={day ? weekdayDate(day) : undefined} />
        <div className="grid grid-cols-2 gap-3">
          <TextField label="Arrivée" type="time" value={arrives} onChange={(e) => setArrives(e.target.value)} />
          <TextField label="Départ" type="time" value={leaves} onChange={(e) => setLeaves(e.target.value)} />
        </div>
        <TextField label="Un mot pour la crèche (facultatif)" value={note} maxLength={300} onChange={(e) => setNote(e.target.value)} />
        <TouchButton disabled={busy || !day || !arrives || !leaves}
          onClick={() => void run(() => portal.requestBooking({ structureId: family.structureId, childId: child.id, day,
            arrivesAt: arrives, leavesAt: leaves, note: note.trim() || null }), "Demande envoyée : la crèche vous répond ici.")}>
          Envoyer la demande
        </TouchButton>
      </section>

      <section aria-labelledby="retard" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-surface p-4 shadow-[var(--shadow-1)]">
        <h2 id="retard" className="m-0 flex items-center gap-2 text-[19px] font-semibold">
          <Clock aria-hidden className="size-5 text-lagon-700" />Prévenir d’un retard aujourd’hui
        </h2>
        <TextField label="Je viendrai vers" type="time" value={lateAt} onChange={(e) => setLateAt(e.target.value)} />
        <TouchButton variant="secondary" disabled={busy || !lateAt}
          onClick={() => void run(() => portal.reportLate({ structureId: family.structureId, childId: child.id, at: lateAt, note: null }),
            `L’équipe est prévenue : vous viendrez vers ${lateAt}.`)}>
          Prévenir l’équipe
        </TouchButton>
      </section>

      {sent && <Banner tone="success">{sent}</Banner>}
      {error && <Banner tone="danger">{error}</Banner>}

      {mine.length > 0 && (
        <section aria-labelledby="mes-demandes" className="flex flex-col gap-2">
          <h2 id="mes-demandes" className="m-0 text-[19px] font-semibold">Mes demandes</h2>
          <ul className="m-0 flex list-none flex-col gap-2 p-0">
            {mine.map((b) => (
              <li key={b.id} className="flex flex-col gap-1 rounded-lg border border-border-subtle bg-surface px-4 py-3">
                <span className="flex items-center gap-2">
                  <span className="tabular grow text-[17px] font-semibold">{dateFr(b.data.day)}, {hm(b.data.arrivesAt)} – {hm(b.data.leavesAt)}</span>
                  <Badge tone={statusBadge[b.data.status].tone}>{statusBadge[b.data.status].label}</Badge>
                </span>
                {b.data.answer && <span className="text-[15px] text-ink-muted">{b.data.answer}</span>}
              </li>
            ))}
          </ul>
        </section>
      )}
    </Page>
  );
}
