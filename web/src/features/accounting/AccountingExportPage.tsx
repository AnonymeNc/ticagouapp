import { useState } from "react";
import { Link } from "react-router";
import { useQuery } from "@tanstack/react-query";
import { CircleCheck, Download, TriangleAlert } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { BillingHeader, billableMonths, previousMonth } from "@/features/billing/components";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { errorMessage } from "@/lib/api";
import { isoToDateFr } from "@/lib/datetime";
import { formatMonthLong, formatXpf } from "@/lib/format";
import { selectClass } from "@/lib/formFields";
import { cn } from "@/lib/utils";
import { accountingApi, accountingKeys, periodBounds } from "./api";

/**
 * Facturation › Export comptable : écritures d'un mois ou d'une année pour le cabinet comptable, contrôlées avant
 * l'export (équilibre, comptes renseignés). Les pièces sont datées de leur émission : les factures d'octobre générées
 * début novembre sont dans l'export de novembre.
 */
export function AccountingExportPage() {
  const user = useCurrentUser();
  const [period, setPeriod] = useState(previousMonth());
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const { from, to } = periodBounds(period);
  const check = useQuery({ queryKey: accountingKeys.check(from, to), queryFn: ({ signal }) => accountingApi.check(from, to, signal) });
  const years = Array.from(new Set(billableMonths().map((m) => m.slice(0, 4))));
  const data = check.data;
  const blocked = !data || data.anomalies.length > 0 || data.journals.length === 0;

  const download = async (format: "CSV" | "FEC") => {
    setError(null);
    setBusy(true);
    try {
      await accountingApi.download(from, to, format);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <BillingHeader title="Export comptable" subtitle="Écritures des factures, avoirs et encaissements, pour le logiciel du cabinet comptable." />
      <FormField label="Période" hint="Les pièces sont datées de leur émission ou de leur encaissement.">
        <select className={cn(selectClass, "w-auto self-start")} value={period} onChange={(e) => setPeriod(e.target.value)}>
          <optgroup label="Mois">
            {billableMonths().map((m) => <option key={m} value={m}>{formatMonthLong(m)}</option>)}
          </optgroup>
          <optgroup label="Année civile">
            {years.map((y) => <option key={y} value={y}>Année {y}</option>)}
          </optgroup>
        </select>
      </FormField>
      {check.isError && <Alert>{errorMessage(check.error)}</Alert>}
      {error && <Alert>{error}</Alert>}

      {data && (
        <section className="flex flex-col gap-4 rounded-lg border border-border-subtle bg-card p-5 shadow-1" aria-label="Contrôle avant export">
          <h2 className="m-0 text-title-3">Du {isoToDateFr(data.from)} au {isoToDateFr(data.to)}</h2>
          {data.journals.length === 0 ? (
            <p className="m-0 text-muted-foreground">Aucune facture, aucun avoir ni encaissement sur cette période.</p>
          ) : (
            <table className="w-full max-w-2xl border-collapse text-left">
              <thead className="text-small text-muted-foreground">
                <tr><th className="py-1 font-normal">Journal</th><th className="py-1 font-normal">Pièces</th><th className="py-1 text-right font-normal">Débit</th><th className="py-1 text-right font-normal">Crédit</th></tr>
              </thead>
              <tbody className="tabular-nums">
                {data.journals.map((j) => (
                  <tr key={j.journal} className="border-t border-border-subtle">
                    <td className="py-1.5">{j.label} <span className="text-muted-foreground">({j.journal})</span></td>
                    <td className="py-1.5">{j.pieces}</td>
                    <td className="py-1.5 text-right">{formatXpf(j.debit)}</td>
                    <td className="py-1.5 text-right">{formatXpf(j.credit)}</td>
                  </tr>
                ))}
                <tr className="border-t border-border font-semibold">
                  <td className="py-1.5" colSpan={2}>Total</td>
                  <td className="py-1.5 text-right">{formatXpf(data.debit)}</td>
                  <td className="py-1.5 text-right">{formatXpf(data.credit)}</td>
                </tr>
              </tbody>
            </table>
          )}
          {data.anomalies.length > 0 ? (
            <div role="alert" className="flex flex-col gap-1 rounded-md bg-danger-100 p-3 text-danger-700">
              <strong className="flex items-center gap-2"><TriangleAlert aria-hidden className="size-5" />À corriger avant l’export</strong>
              <ul className="m-0 pl-5">{data.anomalies.map((a) => <li key={a}>{a}</li>)}</ul>
              {user.role === "DIRECTION" || user.role === "COMPTABILITE" ? (
                <Link to="/parametres?onglet=comptabilite" className="underline underline-offset-2">Ouvrir les paramètres de comptabilité</Link>
              ) : null}
            </div>
          ) : data.journals.length > 0 && (
            <p className="m-0 flex items-center gap-2 text-success-700"><CircleCheck aria-hidden className="size-5" />Écritures équilibrées, tous les comptes sont renseignés.</p>
          )}
          <div className="flex flex-wrap gap-2">
            <Button disabled={blocked || busy} onClick={() => void download("CSV")}><Download aria-hidden />Exporter en CSV</Button>
            <Button variant="outline" disabled={blocked || busy} onClick={() => void download("FEC")}><Download aria-hidden />Fichier des écritures (FEC)</Button>
          </div>
          <p className="m-0 text-small text-muted-foreground">
            Le format du CSV (colonnes, séparateur, dates) et les comptes se règlent dans Paramètres › Comptabilité. Faites
            valider les comptes par votre expert-comptable avant le premier export.
          </p>
        </section>
      )}
    </>
  );
}
