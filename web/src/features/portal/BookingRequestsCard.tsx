import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { CalendarPlus, Clock } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { errorMessage } from "@/lib/api";
import { isoToDateFr } from "@/lib/datetime";
import { portalApi, portalKeys, type Booking } from "./api";

const hm = (time: string | null) => (time ? time.slice(0, 5) : "");

/**
 * Tableau de bord : demandes d'accueil occasionnel des parents (lot 2) et retards annoncés pour aujourd'hui. Accepter
 * rend l'enfant attendu ce jour-là ; au-delà des places de la section, la direction donne un motif, tracé. Un refus
 * part à la famille avec son motif. Écart : pas de maquette bureau pour cette carte.
 */
export function BookingRequestsCard({ canAnswer }: { canAnswer: boolean }) {
  const queryClient = useQueryClient();
  const bookings = useQuery({ queryKey: portalKeys.bookings, queryFn: ({ signal }) => portalApi.bookings(signal), refetchInterval: 60_000 });
  const [reasons, setReasons] = useState<Record<string, string>>({});
  const [forcing, setForcing] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const list = bookings.data ?? [];
  if (list.length === 0) return null;
  const requests = list.filter((b) => b.kind === "RESERVATION");
  const lates = list.filter((b) => b.kind === "RETARD");

  const run = async (action: () => Promise<unknown>) => {
    setError(null);
    try {
      await action();
      setForcing(null);
      await queryClient.invalidateQueries({ queryKey: portalKeys.bookings });
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  const places = (b: Booking) =>
    b.freePlaces === null ? null : b.freePlaces > 0 ? `${b.freePlaces} place${b.freePlaces > 1 ? "s" : ""} libre${b.freePlaces > 1 ? "s" : ""}` : "Section complète";

  return (
    <section aria-labelledby="demandes-parents" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <h2 id="demandes-parents" className="m-0 flex items-center gap-2 text-title-2">
        <CalendarPlus aria-hidden className="size-5 text-lagon-700" />
        Demandes des parents
      </h2>
      {lates.length > 0 && (
        <ul aria-label="Retards annoncés" className="m-0 flex list-none flex-col gap-1 rounded-md bg-info-100 p-3 text-info-700">
          {lates.map((b) => (
            <li key={b.id} className="flex items-center gap-2">
              <Clock aria-hidden className="size-4" />
              <span><strong className="font-semibold">{b.childName}</strong> : récupéré vers {hm(b.leavesAt)}
                {b.guardianName && <> par {b.guardianName}</>}{b.note && <> · « {b.note} »</>}</span>
            </li>
          ))}
        </ul>
      )}
      <ul aria-label="Demandes d’accueil" className="m-0 flex list-none flex-col gap-2 p-0">
        {requests.map((b) => (
          <li key={b.id} className="flex flex-col gap-2 border-b border-border-subtle py-2">
            <div className="flex flex-wrap items-center gap-3">
              <span className="grow">
                <strong className="font-semibold">{b.childName}</strong> le {isoToDateFr(b.day)}, de {hm(b.arrivesAt)} à {hm(b.leavesAt)}
                {b.note && <> · « {b.note} »</>}
                {b.guardianName && <span className="text-small text-muted-foreground"> · demandé par {b.guardianName}</span>}
              </span>
              {places(b) && (
                <span className={b.freePlaces !== null && b.freePlaces <= 0 ? "text-small font-semibold text-warning-700" : "text-small text-muted-foreground"}>
                  {places(b)}
                </span>
              )}
            </div>
            {canAnswer && (
              <div className="flex flex-wrap items-center gap-2">
                <Input aria-label={`Motif pour ${b.childName}`} placeholder={forcing === b.id ? "Pourquoi accepter malgré la section complète ?" : "Motif du refus"}
                  value={reasons[b.id] ?? ""} maxLength={300} className="w-auto grow"
                  onChange={(e) => setReasons({ ...reasons, [b.id]: e.target.value })} />
                {b.freePlaces !== null && b.freePlaces <= 0 && forcing !== b.id ? (
                  <Button size="sm" variant="outline" onClick={() => setForcing(b.id)}>Accepter malgré tout…</Button>
                ) : (
                  <Button size="sm" disabled={forcing === b.id && !reasons[b.id]?.trim()}
                    onClick={() => void run(() => portalApi.acceptBooking(b.id, forcing === b.id ? reasons[b.id] : null))}>
                    Accepter
                  </Button>
                )}
                <Button size="sm" variant="ghost" disabled={!reasons[b.id]?.trim()}
                  onClick={() => void run(() => portalApi.refuseBooking(b.id, reasons[b.id]))}>Refuser</Button>
              </div>
            )}
          </li>
        ))}
      </ul>
      {error && <Alert>{error}</Alert>}
    </section>
  );
}
