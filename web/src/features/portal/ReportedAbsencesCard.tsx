import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { CalendarX2 } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { Button } from "@/components/ui/button";
import { billingApi } from "@/features/billing/api";
import { errorMessage } from "@/lib/api";
import { isoToDateFr } from "@/lib/datetime";
import { selectClass } from "@/lib/formFields";
import { portalApi, portalKeys } from "./api";

/**
 * Tableau de bord : absences signalées par les parents sur le portail. La direction choisit le motif (déductible ou
 * non) en confirmant : l'absence entre alors dans la facturation ; ou elle l'écarte. Rien ne s'affiche sans portail.
 */
export function ReportedAbsencesCard({ canConfirm }: { canConfirm: boolean }) {
  const queryClient = useQueryClient();
  const absences = useQuery({ queryKey: portalKeys.absences, queryFn: ({ signal }) => portalApi.reportedAbsences(signal), refetchInterval: 60_000 });
  const reasons = useQuery({ queryKey: ["billing", "absence-reasons"], queryFn: ({ signal }) => billingApi.reasons(signal), enabled: canConfirm });
  const [chosen, setChosen] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const list = absences.data ?? [];
  if (list.length === 0) return null;
  const active = (reasons.data ?? []).filter((r) => r.active);

  const run = async (action: () => Promise<unknown>) => {
    setError(null);
    try {
      await action();
      await queryClient.invalidateQueries({ queryKey: portalKeys.absences });
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <section aria-labelledby="absences-signalees" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <h2 id="absences-signalees" className="m-0 flex items-center gap-2 text-title-2">
        <CalendarX2 aria-hidden className="size-5 text-info-700" />
        Absences signalées par les parents
      </h2>
      <ul className="m-0 flex list-none flex-col gap-2 p-0">
        {list.map((a) => (
          <li key={a.id} className="flex flex-wrap items-center gap-3 border-b border-border-subtle py-2">
            <span className="grow">
              <strong className="font-semibold">{a.childName}</strong>{" "}
              {a.fromDay === a.toDay ? `le ${isoToDateFr(a.fromDay)}` : `du ${isoToDateFr(a.fromDay)} au ${isoToDateFr(a.toDay)}`}
              {a.reason && <> · « {a.reason} »</>}
              {a.guardianName && <span className="text-small text-muted-foreground"> · signalée par {a.guardianName}</span>}
            </span>
            {canConfirm && (
              <>
                <label className="sr-only" htmlFor={`motif-${a.id}`}>Motif</label>
                <select id={`motif-${a.id}`} className={selectClass} value={chosen[a.id] ?? ""}
                  onChange={(e) => setChosen({ ...chosen, [a.id]: e.target.value })}>
                  <option value="">Motif…</option>
                  {active.map((r) => <option key={r.id} value={r.id}>{r.label}{r.deductible ? " (déduite)" : ""}</option>)}
                </select>
                <Button size="sm" disabled={!chosen[a.id]} onClick={() => void run(() => portalApi.confirmAbsence(a.id, chosen[a.id]))}>Confirmer</Button>
                <Button size="sm" variant="ghost" onClick={() => void run(() => portalApi.dismissAbsence(a.id))}>Écarter</Button>
              </>
            )}
          </li>
        ))}
      </ul>
      {error && <Alert>{error}</Alert>}
    </section>
  );
}
