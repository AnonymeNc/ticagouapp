import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { errorMessage } from "@/lib/api";
import { isoToDateFr } from "@/lib/datetime";
import { portalApi, portalKeys } from "./api";

/** Règles des demandes d'accueil des parents (lot 2) : délai minimum et horizon, datés à partir d'aujourd'hui. */
export function BookingRulesSection() {
  const queryClient = useQueryClient();
  const rules = useQuery({ queryKey: portalKeys.bookingRules, queryFn: ({ signal }) => portalApi.bookingRules(signal) });
  const [notice, setNotice] = useState<string | null>(null);
  const [ahead, setAhead] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  if (!rules.data) return null;
  const minNotice = notice ?? String(rules.data.minNoticeHours);
  const maxAhead = ahead ?? String(rules.data.maxDaysAhead);

  const save = async () => {
    setError(null);
    setMessage(null);
    try {
      await portalApi.updateBookingRules({ minNoticeHours: Number(minNotice), maxDaysAhead: Number(maxAhead) });
      await queryClient.invalidateQueries({ queryKey: portalKeys.bookingRules });
      setNotice(null);
      setAhead(null);
      setMessage("Règles enregistrées, en vigueur dès aujourd’hui.");
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <section aria-labelledby="regles-demandes" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <h2 id="regles-demandes" className="m-0 text-title-3">Demandes d’accueil des parents</h2>
      <p className="m-0 max-w-3xl text-muted-foreground">
        Une demande hors de ces règles est refusée d’office, avec le motif. Une demande acceptée rend l’enfant attendu ce
        jour-là ; le temps passé est facturé en heures complémentaires.
        {rules.data.validFrom ? ` En vigueur depuis le ${isoToDateFr(rules.data.validFrom)}.` : " Règles par défaut."}
      </p>
      <div className="flex flex-wrap gap-4">
        <FormField label="Délai minimum (heures)" className="w-56">
          <Input type="number" min={0} max={336} value={minNotice} onChange={(e) => setNotice(e.target.value)} />
        </FormField>
        <FormField label="Jusqu’à (jours à l’avance)" className="w-56">
          <Input type="number" min={1} max={365} value={maxAhead} onChange={(e) => setAhead(e.target.value)} />
        </FormField>
      </div>
      {message && <p role="status" className="m-0 text-success-700">{message}</p>}
      {error && <Alert>{error}</Alert>}
      <Button className="self-start" onClick={() => void save()}>Enregistrer les règles</Button>
    </section>
  );
}
