import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { staffApi, staffKeys } from "@/features/staff/api";
import { errorMessage } from "@/lib/api";
import { isoToDateFr } from "@/lib/datetime";

/** Réglages du module Personnel : délai d'alerte des qualifications, pause par défaut, durée hebdomadaire de référence. */
export function StaffSettingsSection() {
  const queryClient = useQueryClient();
  const settings = useQuery({ queryKey: staffKeys.settings, queryFn: ({ signal }) => staffApi.settings(signal) });
  const [form, setForm] = useState({ alertDays: "", breakMinutes: "", weeklyHours: "" });
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (settings.data) {
      setForm({
        alertDays: String(settings.data.alertDays),
        breakMinutes: String(settings.data.defaultBreakMinutes),
        weeklyHours: String(settings.data.referenceWeeklyMinutes / 60),
      });
    }
  }, [settings.data]);

  return (
    <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <div>
        <h2 className="m-0 text-title-3">Personnel</h2>
        <p className="m-0 text-muted-foreground">
          Alerte avant l’expiration d’une qualification, pause déduite d’un pointage sans planning (au-delà de 6 h).
          {settings.data && ` En vigueur depuis le ${isoToDateFr(settings.data.validFrom)}.`}
        </p>
      </div>
      <div className="flex flex-wrap items-end gap-3">
        <FormField label="Alerte (jours avant l’expiration)"><Input className="w-32" inputMode="numeric" value={form.alertDays} onChange={(e) => setForm({ ...form, alertDays: e.target.value })} /></FormField>
        <FormField label="Pause par défaut (min)"><Input className="w-32" inputMode="numeric" value={form.breakMinutes} onChange={(e) => setForm({ ...form, breakMinutes: e.target.value })} /></FormField>
        <FormField label="Durée de référence (h / semaine)"><Input className="w-32" inputMode="decimal" value={form.weeklyHours} onChange={(e) => setForm({ ...form, weeklyHours: e.target.value })} /></FormField>
        <Button
          variant="outline"
          onClick={async () => {
            setError(null);
            setMessage(null);
            const alertDays = Number(form.alertDays);
            const defaultBreakMinutes = Number(form.breakMinutes);
            const referenceWeeklyMinutes = Math.round(Number(form.weeklyHours.replace(",", ".")) * 60);
            if (![alertDays, defaultBreakMinutes, referenceWeeklyMinutes].every(Number.isFinite)) return setError("Saisissez des nombres.");
            try {
              await staffApi.updateSettings({ validFrom: settings.data!.validFrom, alertDays, defaultBreakMinutes, referenceWeeklyMinutes });
              await queryClient.invalidateQueries({ queryKey: ["staff"] });
              setMessage("Réglages enregistrés.");
            } catch (e) {
              setError(errorMessage(e));
            }
          }}
        >
          Enregistrer
        </Button>
      </div>
      {message && <p className="m-0 text-small text-success-700">{message}</p>}
      {error && <Alert>{error}</Alert>}
    </section>
  );
}
