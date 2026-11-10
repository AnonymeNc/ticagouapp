import { useState, type ReactNode } from "react";
import { Link } from "react-router";
import { Clock, Download, Receipt, TrendingDown } from "lucide-react";
import { Button, buttonVariants } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { xpf } from "@/features/billing/labels";
import { errorMessage } from "@/lib/api";
import { cn } from "@/lib/utils";
import {
  exportHref,
  presetRange,
  rateLabel,
  useReport,
  useSaveSettings,
  type AgingRow,
  type Filter,
  type Preset,
  type Report,
} from "./api";

const day = (iso: string) => iso.split("-").reverse().join("/");
const monthName = (ym: string) =>
  new Date(`${ym}-15T00:00:00`).toLocaleDateString("fr-FR", { month: "long", year: "numeric" }).replace(/^./, (c) => c.toUpperCase());

/**
 * Reporting (maquette « Reporting ») : chiffre d'affaires, encaissé, TGC et départs de la période ; balance âgée à ce jour ;
 * revenu mensuel (formule, options, remises) et vente additionnelle ; risque de départ ; rentabilité par crèche ; détail par mois et TGC
 * par taux. Exports pour le comptable : journal des ventes, encaissements, classeur Excel.
 */
export function ReportingPage() {
  const [preset, setPreset] = useState<Preset>("exercice");
  const [filter, setFilter] = useState<Filter>(() => ({ ...presetRange("exercice", new Date()), clientId: "" }));
  const report = useReport(filter);
  const data = report.data;

  const choose = (next: Preset) => {
    setPreset(next);
    if (next !== "autre") {
      setFilter((f) => ({ ...f, ...presetRange(next, new Date()) }));
    }
  };

  /** Date saisie à la main : la période devient « autre » ; un champ vidé est ignoré. */
  const custom = (change: { from?: string; to?: string }) => {
    if (Object.values(change).some((v) => !v)) {
      return;
    }
    setPreset("autre");
    setFilter((f) => ({ ...f, ...change }));
  };

  return (
    <>
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="m-0 font-display text-display">Reporting</h1>
          <p className="mt-1 text-muted-foreground">
            Du {day(filter.from)} au {day(filter.to)} · montants en francs CFP
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <a href={exportHref("journal-ventes.csv", filter)} download className={buttonVariants({ variant: "outline" })}>
            <Download aria-hidden />
            Journal des ventes
          </a>
          <a href={exportHref("encaissements.csv", filter)} download className={buttonVariants({ variant: "outline" })}>
            <Download aria-hidden />
            Encaissements
          </a>
          <a href={exportHref("reporting.xlsx", filter)} download className={buttonVariants({ variant: "outline" })}>
            <Download aria-hidden />
            Excel
          </a>
        </div>
      </div>

      <section aria-label="Filtres" className="flex flex-wrap items-end gap-3 rounded-lg border border-border-subtle bg-card p-4 shadow-1">
        <Field label="Période" htmlFor="periode">
          <select id="periode" className={selectClass} value={preset} onChange={(e) => choose(e.target.value as Preset)}>
            <option value="exercice">Exercice {filter.to.slice(0, 4)}</option>
            <option value="douze">12 derniers mois</option>
            <option value="trimestre">Trimestre en cours</option>
            <option value="autre">Autre période</option>
          </select>
        </Field>
        <Field label="Du" htmlFor="du">
          <Input id="du" type="date" value={filter.from} max={filter.to} onChange={(e) => custom({ from: e.target.value })} />
        </Field>
        <Field label="Au" htmlFor="au">
          <Input id="au" type="date" value={filter.to} min={filter.from} onChange={(e) => custom({ to: e.target.value })} />
        </Field>
        <Field label="Client" htmlFor="client">
          <select id="client" className={selectClass} value={filter.clientId} onChange={(e) => setFilter({ ...filter, clientId: e.target.value })}>
            <option value="">Tous</option>
            {data?.clients.map((c) => (
              <option key={c.id} value={c.id}>{c.name}</option>
            ))}
          </select>
        </Field>
      </section>

      {report.isError && <p role="alert" className="text-danger-700">{errorMessage(report.error)}</p>}
      {!data && !report.isError && <p className="text-muted-foreground">Calcul du reporting…</p>}
      {data && <Body report={data} />}
    </>
  );
}

const selectClass = "h-9 rounded-md border border-input bg-card px-3 text-body";

function Field({ label, htmlFor, children }: { label: string; htmlFor: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={htmlFor}>{label}</Label>
      {children}
    </div>
  );
}

function Body({ report }: { report: Report }) {
  const k = report.kpis;
  const share = (part: number) => (k.revenueHt > 0 ? Math.round((part * 100) / k.revenueHt) : 0);
  return (
    <>
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Kpi label="Chiffre d’affaires HT" value={xpf(k.revenueHt)} hint={k.revenueHt ? `abonnements ${share(k.subscriptionsHt)} % · prestations ${share(k.servicesHt)} %` : "aucune facture validée"} />
        <Kpi label="Encaissé" value={xpf(k.collected)} hint="TTC, sur la période" />
        <Kpi label="TGC collectée" value={xpf(k.tgc)} hint={`à déclarer${k.rates.length ? ` · ${k.rates.map(rateLabel).join(", ")}` : ""}`} />
        <Kpi
          label="Taux de départ"
          value={k.churnTenths == null ? "—" : `${(k.churnTenths / 10).toLocaleString("fr-FR", { maximumFractionDigits: 1 })} %`}
          hint={`${k.departures} résiliation${k.departures > 1 ? "s" : ""} sur 12 mois`}
        />
      </div>

      <div className="grid gap-4 xl:grid-cols-2">
        <Card title="Balance âgée" aside={<span className="text-small text-muted-foreground">à ce jour, TTC</span>}>
          <Aging rows={report.aging} total={report.agingTotal} />
        </Card>

        <Card title="Revenu mensuel" aside={<span className="text-small text-muted-foreground">{xpf(k.mrr)} HT par mois</span>}>
          {report.mrrByPlan.length === 0 ? (
            <Empty>Aucun contrat en cours.</Empty>
          ) : (
            <table className="w-full border-collapse">
              <tbody>
                {report.mrrByPlan.map((p) => (
                  <tr key={p.key} className="border-b border-border-subtle last:border-0">
                    <td className="py-2 pr-3">{p.label}</td>
                    <td className="w-1/2 py-2 pr-3">
                      <div className="h-2 overflow-hidden rounded-full bg-muted" aria-hidden>
                        <span className="block h-full rounded-full bg-lagon-600" style={{ width: `${k.mrr > 0 ? Math.max(0, Math.round((p.monthly * 100) / k.mrr)) : 0}%` }} />
                      </div>
                    </td>
                    <td className="py-2 text-right tabular-nums">{xpf(p.monthly)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {report.upsell.map((u) => (
            <p key={u.slug + u.text} className="m-0 mt-2.5 text-small text-muted-foreground">
              Vente additionnelle possible : <Link to={`/parc/${u.slug}`} className="text-foreground hover:underline">{u.name}</Link> — {u.text}
              {u.monthly > 0 && ` (+ ${xpf(u.monthly)}/mois)`}.
            </p>
          ))}
        </Card>

        <Card title="Risque de départ">
          {report.risks.length === 0 ? (
            <Empty>Aucun signal : connexions, usage et paiements en ordre.</Empty>
          ) : (
            <ul className="m-0 flex list-none flex-col gap-2 p-0">
              {report.risks.map((r) => (
                <li key={r.slug + r.kind} className="flex items-start gap-3">
                  <span className={cn("grid size-8 shrink-0 place-items-center rounded-md", r.kind === "PAIEMENT" ? "bg-danger-100 text-danger-700" : "bg-warning-100 text-warning-700")}>
                    {r.kind === "PAIEMENT" ? <Receipt aria-hidden className="size-4" /> : r.kind === "USAGE" ? <TrendingDown aria-hidden className="size-4" /> : <Clock aria-hidden className="size-4" />}
                  </span>
                  <span className="pt-1">
                    <Link to={`/parc/${r.slug}`} className="font-semibold hover:underline">{r.name}</Link> : {r.text}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Profitability report={report} />
      </div>

      <div className="grid gap-4 xl:grid-cols-[2fr_1fr]">
        <Card title="Par mois">
          <div className="overflow-x-auto">
            <table className="w-full border-collapse">
              <thead className="border-b border-border-subtle text-left text-label text-muted-foreground">
                <tr>
                  <th className="py-2 pr-3">Mois</th>
                  <th className="py-2 pr-3 text-right">Facturé HT</th>
                  <th className="py-2 pr-3 text-right">TGC</th>
                  <th className="py-2 pr-3 text-right">Facturé TTC</th>
                  <th className="py-2 text-right">Encaissé</th>
                </tr>
              </thead>
              <tbody>
                {report.months.map((m) => (
                  <tr key={m.month} className="border-b border-border-subtle tabular-nums">
                    <td className="py-2 pr-3">{monthName(m.month)}</td>
                    <td className="py-2 pr-3 text-right">{xpf(m.ht)}</td>
                    <td className="py-2 pr-3 text-right">{xpf(m.tgc)}</td>
                    <td className="py-2 pr-3 text-right">{xpf(m.ttc)}</td>
                    <td className="py-2 text-right">{xpf(m.collected)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
        <Card title="TGC par taux" aside={<span className="text-small text-muted-foreground">pour la déclaration</span>}>
          {report.tgc.length === 0 ? (
            <Empty>Aucune TGC sur la période.</Empty>
          ) : (
            <table className="w-full border-collapse">
              <thead className="border-b border-border-subtle text-left text-label text-muted-foreground">
                <tr>
                  <th className="py-2 pr-3">Taux</th>
                  <th className="py-2 pr-3 text-right">Base HT</th>
                  <th className="py-2 text-right">TGC</th>
                </tr>
              </thead>
              <tbody>
                {report.tgc.map((r) => (
                  <tr key={r.rate} className="border-b border-border-subtle tabular-nums">
                    <td className="py-2 pr-3">{rateLabel(r.rate)}</td>
                    <td className="py-2 pr-3 text-right">{xpf(r.base)}</td>
                    <td className="py-2 text-right">{xpf(r.tgc)}</td>
                  </tr>
                ))}
                <tr className="font-semibold tabular-nums">
                  <td className="py-2 pr-3">Total</td>
                  <td className="py-2 pr-3 text-right">{xpf(report.tgc.reduce((s, r) => s + r.base, 0))}</td>
                  <td className="py-2 text-right">{xpf(k.tgc)}</td>
                </tr>
              </tbody>
            </table>
          )}
        </Card>
      </div>
    </>
  );
}

function Aging({ rows, total }: { rows: AgingRow[]; total: AgingRow }) {
  if (rows.length === 0) {
    return <Empty>Rien n’est dû.</Empty>;
  }
  const cell = (n: number, late = false) => (
    <td className={cn("py-2 pl-3 text-right tabular-nums", late && n > 0 && "font-semibold text-danger-700")}>{n ? n.toLocaleString("fr-FR") : "—"}</td>
  );
  return (
    <div className="overflow-x-auto">
      <table className="w-full border-collapse">
        <thead className="border-b border-border-subtle text-left text-label text-muted-foreground">
          <tr>
            <th className="py-2">Client</th>
            <th className="py-2 pl-3 text-right">Non échu</th>
            <th className="py-2 pl-3 text-right">0–30 j</th>
            <th className="py-2 pl-3 text-right">31–60 j</th>
            <th className="py-2 pl-3 text-right">61–90 j</th>
            <th className="py-2 pl-3 text-right">+ 90 j</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.clientId ?? r.client} className="border-b border-border-subtle">
              <td className="py-2">{r.client}</td>
              {cell(r.notDue)}
              {cell(r.d30, true)}
              {cell(r.d60, true)}
              {cell(r.d90, true)}
              {cell(r.over, true)}
            </tr>
          ))}
          <tr className="font-semibold">
            <td className="py-2">Total</td>
            {[total.notDue, total.d30, total.d60, total.d90, total.over].map((n, i) => (
              <td key={i} className="py-2 pl-3 text-right tabular-nums">{n.toLocaleString("fr-FR")}</td>
            ))}
          </tr>
        </tbody>
      </table>
    </div>
  );
}

function Profitability({ report }: { report: Report }) {
  const [editing, setEditing] = useState(false);
  const [rate, setRate] = useState(String(report.settings.supportHourlyRate));
  const [hosting, setHosting] = useState(String(report.settings.hostingPerCreche));
  const save = useSaveSettings();
  const hours = (minutes: number) => `${(minutes / 60).toLocaleString("fr-FR", { maximumFractionDigits: 1 })} h`;
  return (
    <Card title="Rentabilité par crèche" aside={<span className="text-small text-muted-foreground">par mois</span>}>
      {report.profitability.length === 0 ? (
        <Empty>Aucune crèche sous contrat.</Empty>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full border-collapse">
            <thead className="border-b border-border-subtle text-left text-label text-muted-foreground">
              <tr>
                <th className="py-2">Crèche</th>
                <th className="py-2 pl-3 text-right">Revenu / mois</th>
                <th className="py-2 pl-3 text-right">Support saisi</th>
                <th className="py-2 pl-3 text-right">Marge estimée</th>
              </tr>
            </thead>
            <tbody>
              {report.profitability.map((p) => (
                <tr key={p.slug} className="border-b border-border-subtle tabular-nums">
                  <td className="py-2"><Link to={`/parc/${p.slug}`} className="hover:underline">{p.name}</Link></td>
                  <td className="py-2 pl-3 text-right">{xpf(p.revenue)}</td>
                  <td className="py-2 pl-3 text-right">{hours(p.supportMinutes)}</td>
                  <td className={cn("py-2 pl-3 text-right", p.margin < 0 && "font-semibold text-danger-700")}>{p.margin < 0 ? `− ${xpf(-p.margin)}` : xpf(p.margin)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {!editing ? (
        <p className="m-0 mt-2.5 text-small text-muted-foreground">
          Support à {xpf(report.settings.supportHourlyRate)} de l’heure (temps saisi sur les appels, ramené à {report.profitMonths} mois), hébergement
          réparti : {xpf(report.settings.hostingPerCreche)} par crèche.{" "}
          <Button size="sm" variant="ghost" onClick={() => setEditing(true)}>Modifier</Button>
        </p>
      ) : (
        <form
          className="mt-3 flex flex-wrap items-end gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            save.mutate({ supportHourlyRate: Number(rate), hostingPerCreche: Number(hosting) }, { onSuccess: () => setEditing(false) });
          }}
        >
          <Field label="Heure de support (F)" htmlFor="taux-horaire">
            <Input id="taux-horaire" inputMode="numeric" className="w-32" value={rate} onChange={(e) => setRate(e.target.value.replace(/\D/g, ""))} />
          </Field>
          <Field label="Hébergement par crèche (F/mois)" htmlFor="hebergement">
            <Input id="hebergement" inputMode="numeric" className="w-32" value={hosting} onChange={(e) => setHosting(e.target.value.replace(/\D/g, ""))} />
          </Field>
          <Button type="submit" size="sm" disabled={save.isPending || rate === "" || hosting === ""}>Enregistrer</Button>
          <Button type="button" size="sm" variant="ghost" onClick={() => setEditing(false)}>Annuler</Button>
          {save.isError && <span role="alert" className="text-small text-danger-700">{errorMessage(save.error)}</span>}
        </form>
      )}
    </Card>
  );
}

function Kpi({ label, value, hint }: { label: string; value: string; hint: string }) {
  return (
    <section className="rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <div className="text-label text-muted-foreground">{label}</div>
      <div className="mt-1 font-display text-display tabular-nums">{value}</div>
      <div className="mt-1 text-small text-muted-foreground">{hint}</div>
    </section>
  );
}

function Card({ title, aside, children }: { title: string; aside?: ReactNode; children: ReactNode }) {
  return (
    <section className="rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <div className="mb-3 flex items-baseline justify-between gap-3">
        <h2 className="m-0 font-display text-title-3">{title}</h2>
        {aside}
      </div>
      {children}
    </section>
  );
}

function Empty({ children }: { children: ReactNode }) {
  return <p className="m-0 text-muted-foreground">{children}</p>;
}
