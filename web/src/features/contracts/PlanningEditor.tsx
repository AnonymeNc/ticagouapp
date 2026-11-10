import type { OpeningHours } from "@/features/structure/useStructure";
import { DAY_NAMES, formatMinutes, formatXpf, hhmm, span, timeToMinutes } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { BillingMode, DayInput } from "./api";

export type DayState = { on: boolean; arrivesAt: string; leavesAt: string };
export type DaysState = Record<number, DayState>;

/** Planning de départ : jours ouverts, cochés selon la demande ou le contrat. */
export function initialDays(openDays: number[], chosen: DayInput[], fallback = { arrivesAt: "07:30", leavesAt: "16:30" }): DaysState {
  const state: DaysState = {};
  for (const d of openDays) {
    const day = chosen.find((c) => c.dayOfWeek === d);
    state[d] = day
      ? { on: true, arrivesAt: hhmm(day.arrivesAt), leavesAt: hhmm(day.leavesAt) }
      : { on: false, ...fallback };
  }
  return state;
}

export const toDayInputs = (days: DaysState): DayInput[] =>
  Object.entries(days)
    .filter(([, d]) => d.on)
    .map(([day, d]) => ({ dayOfWeek: Number(day), arrivesAt: d.arrivesAt, leavesAt: d.leavesAt }));

export const weeklyMinutes = (days: DaysState) =>
  Object.values(days).filter((d) => d.on).reduce((sum, d) => sum + Math.max(0, span(d.arrivesAt, d.leavesAt)), 0);

/**
 * Saisie du planning type, au quart d'heure, avec une barre par jour sur l'amplitude d'ouverture
 * (maquette Contrat d'accueil › étape 1).
 */
export function PlanningEditor({ days, onChange, opening }: {
  days: DaysState;
  onChange: (days: DaysState) => void;
  opening: OpeningHours[];
}) {
  const opens = Math.min(...opening.map((o) => timeToMinutes(hhmm(o.opensAt))), 6 * 60 + 30);
  const closes = Math.max(...opening.map((o) => timeToMinutes(hhmm(o.closesAt))), 18 * 60);
  const width = closes - opens;
  const patch = (day: number, change: Partial<DayState>) => onChange({ ...days, [day]: { ...days[day], ...change } });

  return (
    <table className="w-full border-collapse text-left">
      <caption className="sr-only">Planning type par jour</caption>
      <thead>
        <tr className="border-b border-border-subtle text-label text-muted-foreground">
          <th scope="col" className="py-2 pr-3">Jour</th>
          <th scope="col" className="py-2 pr-3">Arrivée</th>
          <th scope="col" className="py-2 pr-3">Départ</th>
          <th scope="col" className="hidden py-2 pr-3 md:table-cell"><span className="sr-only">Amplitude</span></th>
          <th scope="col" className="py-2 text-right">Durée</th>
        </tr>
      </thead>
      <tbody>
        {Object.entries(days).map(([key, d]) => {
          const day = Number(key);
          const minutes = span(d.arrivesAt, d.leavesAt);
          const invalid = d.on && minutes <= 0;
          const name = DAY_NAMES[day - 1];
          return (
            <tr key={day} className="border-b border-border-subtle last:border-0">
              <th scope="row" className="py-2 pr-3 font-normal">
                <label className="inline-flex items-center gap-2">
                  <input type="checkbox" checked={d.on} onChange={(e) => patch(day, { on: e.target.checked })} className="size-4 accent-lagon-600" />
                  {name}
                </label>
              </th>
              <td className="py-2 pr-3">
                <input
                  type="time"
                  step={900}
                  aria-label={`Arrivée ${name.toLowerCase()}`}
                  disabled={!d.on}
                  value={d.arrivesAt}
                  onChange={(e) => patch(day, { arrivesAt: e.target.value })}
                  className="h-control rounded-md border border-input bg-card px-2 tabular-nums disabled:opacity-50"
                />
              </td>
              <td className="py-2 pr-3">
                <input
                  type="time"
                  step={900}
                  aria-label={`Départ ${name.toLowerCase()}`}
                  aria-invalid={invalid || undefined}
                  disabled={!d.on}
                  value={d.leavesAt}
                  onChange={(e) => patch(day, { leavesAt: e.target.value })}
                  className="h-control rounded-md border border-input bg-card px-2 tabular-nums disabled:opacity-50 aria-invalid:border-destructive"
                />
                {invalid && <p className="m-0 text-small font-semibold text-danger-700">Le départ doit suivre l’arrivée.</p>}
              </td>
              <td aria-hidden className="hidden w-[40%] py-2 pr-3 md:table-cell">
                <div className="relative h-2.5 rounded-full bg-muted">
                  {d.on && minutes > 0 && (
                    <div
                      className="absolute h-2.5 rounded-full bg-lagon-600"
                      style={{
                        left: `${Math.max(0, ((timeToMinutes(d.arrivesAt) - opens) / width) * 100)}%`,
                        width: `${Math.min(100, (minutes / width) * 100)}%`,
                      }}
                    />
                  )}
                </div>
              </td>
              <td className={cn("py-2 text-right tabular-nums", !d.on && "text-muted-foreground")}>
                {d.on ? (minutes > 0 ? formatMinutes(minutes) : "—") : "Pas d’accueil"}
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

/**
 * Formule de facturation du barème (« Temps plein », « Matins seulement »…) ou calcul à l'heure d'après le planning.
 * Rien à choisir quand le barème ne propose aucune formule.
 */
export function FormulaChoice({ value, quote, onChange }: {
  value: string | null;
  quote: { formulas?: { label: string; monthlyFee: number }[]; hourlyFee: number };
  onChange: (formula: string | null) => void;
}) {
  if (!quote.formulas?.length) return null;
  const options = [
    { key: null, title: "Calcul à l’heure", amount: `${formatXpf(quote.hourlyFee)} par mois`, detail: "D’après le planning et la tranche du barème." },
    ...quote.formulas.map((f) => ({ key: f.label, title: f.label, amount: `${formatXpf(f.monthlyFee)} par mois`, detail: "Forfait du barème, le même chaque mois." })),
  ];
  return (
    <fieldset className="m-0 flex flex-col gap-2 border-0 p-0">
      <legend className="mb-2 text-label">Formule de facturation</legend>
      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
        {options.map((o) => (
          <label key={o.key ?? ""} className={cn("flex cursor-pointer gap-3 rounded-lg border p-4", value === o.key ? "border-lagon-600 bg-lagon-50" : "border-input")}>
            <input type="radio" name="formula" checked={value === o.key} onChange={() => onChange(o.key)} className="mt-1 accent-lagon-600" />
            <span className="flex flex-col gap-1">
              <span className="font-semibold">{o.title} — <span className="tabular-nums">{o.amount}</span></span>
              <span className="text-small text-muted-foreground">{o.detail}</span>
            </span>
          </label>
        ))}
      </div>
    </fieldset>
  );
}

/**
 * Mensualisé ou au réel, avec les deux montants côte à côte : la directrice choisit avec la famille. Le défaut vient
 * des Paramètres (Barème et facturation).
 */
export function BillingModeChoice({ value, quote, onChange }: {
  value: BillingMode;
  quote: { weeklyMinutes: number; monthlyMinutes: number; weeksPerYear: number; hourlyRate: number; monthlyFee: number };
  onChange: (mode: BillingMode) => void;
}) {
  const week = (quote.weeklyMinutes * quote.hourlyRate) / 60;
  const options: { mode: BillingMode; title: string; amount: string; detail: string }[] = [
    {
      mode: "MENSUALISE", title: "Mensualisé", amount: `${formatXpf(quote.monthlyFee)} chaque mois`,
      detail: `${formatMinutes(quote.weeklyMinutes)} par semaine × ${quote.weeksPerYear} semaines ÷ 12 = ${formatMinutes(quote.monthlyMinutes)} par mois, le même montant toute l’année.`,
    },
    {
      mode: "REEL", title: "Au réel", amount: `de ${formatXpf(Math.round(4 * week))} à ${formatXpf(Math.round(5 * week))} selon le mois`,
      detail: `Les jours du planning dans le mois, à ${formatXpf(quote.hourlyRate)} de l’heure : 4 semaines → ${formatMinutes(4 * quote.weeklyMinutes)}, 5 semaines → ${formatMinutes(5 * quote.weeklyMinutes)}.`,
    },
  ];
  return (
    <fieldset className="m-0 flex flex-col gap-2 border-0 p-0">
      <legend className="mb-2 text-label">Mode de facturation</legend>
      <div className="grid gap-3 md:grid-cols-2">
        {options.map((o) => (
          <label key={o.mode} className={cn("flex cursor-pointer gap-3 rounded-lg border p-4", value === o.mode ? "border-lagon-600 bg-lagon-50" : "border-input")}>
            <input type="radio" name="billingMode" checked={value === o.mode} onChange={() => onChange(o.mode)} className="mt-1 accent-lagon-600" />
            <span className="flex flex-col gap-1">
              <span className="font-semibold">{o.title} — <span className="tabular-nums">{o.amount}</span></span>
              <span className="text-small text-muted-foreground">{o.detail}</span>
            </span>
          </label>
        ))}
      </div>
    </fieldset>
  );
}
