import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { FileSpreadsheet, FileText } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { billableMonths, previousMonth } from "@/features/billing/components";
import { errorMessage } from "@/lib/api";
import { isoToDateFr } from "@/lib/datetime";
import { formatMonthLong, formatXpf } from "@/lib/format";
import { selectClass } from "@/lib/formFields";
import { cn } from "@/lib/utils";
import { formatHours, formatPerMille, reportingApi, reportingKeys, type Indicators } from "./api";

const categories: Record<string, string> = { PROVINCE: "Province", ORGANISME: "Organisme", EMPLOYEUR: "Employeur", COMMUNE: "Commune", AUTRE: "Autre" };

/**
 * Statistiques : bilan d'activité d'un mois ou d'une année civile, pour piloter la structure et pour les dossiers des
 * financeurs (PDF signé par la direction, Excel). Pas de maquette : style des cartes du tableau de bord.
 */
export function ReportingPage() {
  const [period, setPeriod] = useState(previousMonth());
  const [error, setError] = useState<string | null>(null);
  const report = useQuery({ queryKey: reportingKeys.report(period), queryFn: ({ signal }) => reportingApi.report(period, signal) });
  const years = Array.from(new Set(billableMonths().map((m) => m.slice(0, 4))));
  const r = report.data;

  const download = async (format: "pdf" | "xlsx") => {
    setError(null);
    try {
      await reportingApi.download(period, format);
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="m-0 font-display text-display">Statistiques</h1>
          <p className="mt-1 mb-0 text-muted-foreground">Fréquentation, occupation, absences, facturation et aides.</p>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" disabled={!r} onClick={() => void download("pdf")}><FileText aria-hidden />Bilan PDF</Button>
          <Button variant="outline" disabled={!r} onClick={() => void download("xlsx")}><FileSpreadsheet aria-hidden />Excel</Button>
        </div>
      </div>
      <FormField label="Période">
        <select className={cn(selectClass, "w-auto self-start")} value={period} onChange={(e) => setPeriod(e.target.value)}>
          <optgroup label="Mois">{billableMonths().map((m) => <option key={m} value={m}>{formatMonthLong(m)}</option>)}</optgroup>
          <optgroup label="Année civile">{years.map((y) => <option key={y} value={y}>Année {y}</option>)}</optgroup>
        </select>
      </FormField>
      {report.isError && <Alert>{errorMessage(report.error)}</Alert>}
      {error && <Alert>{error}</Alert>}

      {r && (
        <>
          <p className="m-0 text-muted-foreground">
            Du {isoToDateFr(r.from)} au {isoToDateFr(r.to)} · {r.openDays} jours d’ouverture · {r.capacity} places agréées · {r.enrolled} enfants sous contrat
          </p>
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
            <Tile label="Enfants accueillis" value={String(r.total.children)} />
            <Tile label="Heures réalisées" value={formatHours(r.total.realizedMinutes)} hint={`${formatHours(r.total.plannedMinutes)} contractualisées`} />
            <Tile label="Taux d’occupation" value={formatPerMille(r.total.occupancy)} hint="Heures réalisées / places × heures d’ouverture" meter={r.total.occupancy} />
            <Tile label="Taux de réalisation" value={formatPerMille(r.total.realization)} hint="Heures réalisées / contractualisées" meter={r.total.realization} />
          </div>

          <Card title="Par section">
            <IndicatorsTable rows={[...r.sections.map((s) => ({ key: s.sectionId, name: s.name, places: s.capacity, i: s.indicators })),
              { key: "total", name: "Structure", places: r.capacity, i: r.total, total: true }]} />
          </Card>

          {r.months.length > 0 && (
            <Card title="Par mois">
              <IndicatorsTable rows={r.months.map((m) => ({ key: m.month, name: formatMonthLong(m.month.slice(0, 7)), places: m.openDays, i: m.indicators }))} placesLabel="Jours d’ouverture" />
            </Card>
          )}

          <div className="grid gap-4 lg:grid-cols-2">
            <Card title="Aides déduites des factures">
              {r.funders.length === 0 ? <p className="m-0 text-muted-foreground">Aucune aide sur la période.</p> : (
                <table className="w-full border-collapse text-left">
                  <thead className="text-small text-muted-foreground"><tr><th className="py-1 font-normal">Financeur</th><th className="py-1 font-normal">Enfants</th><th className="py-1 text-right font-normal">Montant</th></tr></thead>
                  <tbody>
                    {r.funders.map((f) => (
                      <tr key={f.name} className="border-t border-border-subtle">
                        <td className="py-1.5">{f.name} <span className="text-small text-muted-foreground">· {categories[f.category] ?? f.category}</span></td>
                        <td className="py-1.5 tabular-nums">{f.children}</td>
                        <td className="py-1.5 text-right tabular-nums">{formatXpf(f.amount)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </Card>
            <Card title="Facturation des mois de la période">
              <dl className="m-0 grid grid-cols-[1fr_max-content] gap-x-6 gap-y-1 tabular-nums">
                <dt>Factures émises</dt><dd className="m-0 text-right">{r.money.invoices}</dd>
                <dt>Frais de garde (hors TGC)</dt><dd className="m-0 text-right">{formatXpf(r.money.invoiced)}</dd>
                <dt>Aides déduites</dt><dd className="m-0 text-right">{formatXpf(r.money.aids)}</dd>
                <dt>Reste à charge des familles</dt><dd className="m-0 text-right">{formatXpf(r.money.families)}</dd>
                <dt>Avoirs</dt><dd className="m-0 text-right">{formatXpf(r.money.credited)}</dd>
                <dt className="font-semibold">Reste dû aujourd’hui</dt><dd className="m-0 text-right font-semibold">{formatXpf(r.money.remaining)}</dd>
              </dl>
            </Card>
          </div>

          <Card title="Absences">
            {r.absences.length === 0 ? <p className="m-0 text-muted-foreground">Aucune absence enregistrée.</p> : (
              <ul className="m-0 flex list-none flex-wrap gap-x-8 gap-y-1 p-0">
                {r.absences.map((a) => (
                  <li key={a.reason}><strong className="tabular-nums">{a.days} j</strong> {a.reason}{a.deductible && <span className="text-small text-muted-foreground"> · déduite</span>}</li>
                ))}
              </ul>
            )}
          </Card>
          <p className="m-0 text-small text-muted-foreground">
            Heures contractualisées : planning des contrats signés, hors plannings variables. Les jours fériés ouverts au planning
            comptent comme des jours d’ouverture.
          </p>
        </>
      )}
    </>
  );
}

function Tile({ label, value, hint, meter }: { label: string; value: string; hint?: string; meter?: number }) {
  return (
    <section className="flex flex-col gap-1 rounded-lg border border-border-subtle bg-card p-5 shadow-1" aria-label={label}>
      <span className="text-small text-muted-foreground">{label}</span>
      <strong className="text-numeric-lg tabular-nums">{value}</strong>
      {meter !== undefined && (
        <span aria-hidden className="h-2 overflow-hidden rounded-full bg-muted">
          <span className="block h-full rounded-full bg-lagon-600" style={{ width: `${Math.min(meter / 10, 100)}%` }} />
        </span>
      )}
      {hint && <span className="text-small text-muted-foreground">{hint}</span>}
    </section>
  );
}

function Card({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1" aria-label={title}>
      <h2 className="m-0 text-title-3">{title}</h2>
      {children}
    </section>
  );
}

type Row = { key: string; name: string; places: number; i: Indicators; total?: boolean };

function IndicatorsTable({ rows, placesLabel = "Places" }: { rows: Row[]; placesLabel?: string }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full border-collapse text-left">
        <thead className="text-small text-muted-foreground">
          <tr>
            <th className="py-1 font-normal" />
            <th className="py-1 text-right font-normal">{placesLabel}</th>
            <th className="py-1 text-right font-normal">Enfants</th>
            <th className="py-1 text-right font-normal">Réalisées</th>
            <th className="py-1 text-right font-normal">Contractualisées</th>
            <th className="py-1 pl-6 font-normal">Occupation</th>
            <th className="py-1 text-right font-normal">Réalisation</th>
          </tr>
        </thead>
        <tbody className="tabular-nums">
          {rows.map((row) => (
            <tr key={row.key} className={cn("border-t", row.total ? "border-border font-semibold" : "border-border-subtle")}>
              <td className="py-1.5">{row.name}</td>
              <td className="py-1.5 text-right">{row.places}</td>
              <td className="py-1.5 text-right">{row.i.children}</td>
              <td className="py-1.5 text-right">{formatHours(row.i.realizedMinutes)}</td>
              <td className="py-1.5 text-right">{formatHours(row.i.plannedMinutes)}</td>
              <td className="py-1.5 pl-6">
                <span className="flex items-center gap-2">
                  <span aria-hidden className="h-2 w-24 overflow-hidden rounded-full bg-muted">
                    <span className="block h-full rounded-full bg-lagon-600" style={{ width: `${Math.min(row.i.occupancy / 10, 100)}%` }} />
                  </span>
                  {formatPerMille(row.i.occupancy)}
                </span>
              </td>
              <td className="py-1.5 text-right">{formatPerMille(row.i.realization)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
