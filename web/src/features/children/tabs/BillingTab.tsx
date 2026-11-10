import { useState } from "react";
import { Link } from "react-router";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { FileText, Plus, Trash2 } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { DateInput } from "@/components/DateInput";
import { Input } from "@/components/ui/input";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { billingApi, billingKeys, formatRate, parseRate, type ChildFunding } from "@/features/billing/api";
import { InvoiceStatusPill } from "@/features/billing/components";
import { todayIso } from "@/lib/age";
import { errorMessage } from "@/lib/api";
import { isoToDateFr, parseDateFr } from "@/lib/datetime";
import { formatMonthLong, formatXpf } from "@/lib/format";
import { isoToField, selectClass, textOrNull } from "@/lib/formFields";
import type { ChildTabProps } from "../ChildRecordPage";
import { Panel } from "./common";

/** Fiche enfant › Facturation : aides accordées à l'enfant, factures de la famille, attestation annuelle. */
export function BillingTab({ child }: ChildTabProps) {
  const user = useCurrentUser();
  const allowed = user.role === "DIRECTION" || user.role === "COMPTABILITE";
  const queryClient = useQueryClient();
  const fundings = useQuery({ queryKey: billingKeys.fundings(child.id), queryFn: ({ signal }) => billingApi.fundings(child.id, signal), enabled: allowed });
  const invoices = useQuery({
    queryKey: billingKeys.invoices(`family-${child.family.id}`),
    queryFn: ({ signal }) => billingApi.invoices({ familyId: child.family.id }, signal),
    enabled: allowed,
  });
  const [editing, setEditing] = useState<ChildFunding | "new" | null>(null);
  const [error, setError] = useState<string | null>(null);
  if (!allowed) return <Panel><p className="m-0 text-muted-foreground">La facturation est réservée à la direction et à la comptabilité.</p></Panel>;
  const years = [...new Set((invoices.data ?? []).map((i) => Number(i.month.slice(0, 4))))];
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["billing"] });

  return (
    <div className="flex flex-col gap-4">
      <Panel title="Aides accordées" actions={<Button variant="outline" onClick={() => setEditing("new")}><Plus aria-hidden />Accorder une aide</Button>}>
        {error && <Alert>{error}</Alert>}
        {fundings.data?.length === 0 && <p className="m-0 text-muted-foreground">Aucune aide accordée à {child.firstName}.</p>}
        {fundings.data && fundings.data.length > 0 && (
          <table className="w-full border-collapse text-left tabular-nums">
            <thead>
              <tr className="border-b border-border-subtle text-label text-muted-foreground">
                <th scope="col" className="py-2 pr-3">Financeur</th>
                <th scope="col" className="py-2 pr-3">Période</th>
                <th scope="col" className="py-2 pr-3">Parent bénéficiaire</th>
                <th scope="col" className="py-2 pr-3">N° de dossier</th>
                <th scope="col" className="py-2"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {fundings.data.map((f) => (
                <tr key={f.id} className="border-b border-border-subtle last:border-0">
                  <td className="py-2 pr-3">
                    {f.funderName}
                    {(f.amountOverride !== null || f.rateOverride !== null) && (
                      <span className="block text-small text-muted-foreground">
                        Montant propre : {f.amountOverride !== null ? `${formatXpf(f.amountOverride)} / mois` : formatRate(f.rateOverride!)}
                      </span>
                    )}
                  </td>
                  <td className="py-2 pr-3">Du {isoToDateFr(f.fromDay)}{f.toDay ? ` au ${isoToDateFr(f.toDay)}` : ""}</td>
                  <td className="py-2 pr-3">{f.beneficiary ?? "—"}</td>
                  <td className="py-2 pr-3">{f.fileNumber ?? "—"}</td>
                  <td className="py-2 text-right whitespace-nowrap">
                    <Button variant="ghost" size="sm" onClick={() => setEditing(f)}>Modifier</Button>
                    <Button variant="ghost" size="sm" className="text-danger-700" aria-label={`Retirer l’aide ${f.funderName}`}
                      onClick={async () => {
                        if (!window.confirm(`Retirer l’aide « ${f.funderName} » ? Les factures déjà émises ne changent pas.`)) return;
                        try {
                          await billingApi.deleteFunding(child.id, f.id);
                          await refresh();
                        } catch (e) {
                          setError(errorMessage(e));
                        }
                      }}>
                      <Trash2 aria-hidden />
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Panel>

      <Panel title={`Factures de la famille ${child.family.label.replace(/^Famille /, "")}`}>
        {invoices.data?.length === 0 && <p className="m-0 text-muted-foreground">Aucune facture émise.</p>}
        {invoices.data && invoices.data.length > 0 && (
          <table className="w-full border-collapse text-left tabular-nums">
            <thead>
              <tr className="border-b border-border-subtle text-label text-muted-foreground">
                <th scope="col" className="py-2 pr-3">Facture</th>
                <th scope="col" className="py-2 pr-3">Mois</th>
                <th scope="col" className="py-2 pr-3 text-right">Montant</th>
                <th scope="col" className="py-2 pr-3 text-right">Reste dû</th>
                <th scope="col" className="py-2">État</th>
              </tr>
            </thead>
            <tbody>
              {invoices.data.map((i) => (
                <tr key={i.id} className="border-b border-border-subtle last:border-0">
                  <td className="py-2 pr-3"><Link to={`/facturation/factures/${i.id}`} className="text-lagon-700">{i.number}</Link></td>
                  <td className="py-2 pr-3">{formatMonthLong(i.month)}</td>
                  <td className="py-2 pr-3 text-right">{formatXpf(i.familyTotal)}</td>
                  <td className="py-2 pr-3 text-right">{formatXpf(i.remaining)}</td>
                  <td className="py-2"><InvoiceStatusPill status={i.status} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {years.length > 0 && (
          <div className="flex flex-wrap gap-2">
            {years.map((y) => (
              <Button key={y} variant="outline" size="sm"
                onClick={async () => {
                  try {
                    await billingApi.attestationPdf(child.family.id, y);
                  } catch (e) {
                    setError(errorMessage(e));
                  }
                }}>
                <FileText aria-hidden />Attestation {y}
              </Button>
            ))}
          </div>
        )}
      </Panel>

      {editing && (
        <FundingDialog childId={child.id} funding={editing === "new" ? null : editing} onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); void refresh(); }} />
      )}
    </div>
  );
}

function FundingDialog({ childId, funding, onClose, onSaved }: { childId: string; funding: ChildFunding | null; onClose: () => void; onSaved: () => void }) {
  const funders = useQuery({ queryKey: billingKeys.funders, queryFn: ({ signal }) => billingApi.funders(signal) });
  const options = (funders.data ?? []).filter((f) => f.kind === "AIDE_FAMILLE");
  const [funderId, setFunderId] = useState(funding?.funderId ?? "");
  const [from, setFrom] = useState(isoToField(funding?.fromDay ?? todayIso()));
  const [to, setTo] = useState(isoToField(funding?.toDay));
  const [beneficiary, setBeneficiary] = useState(funding?.beneficiary ?? "");
  const [fileNumber, setFileNumber] = useState(funding?.fileNumber ?? "");
  const [own, setOwn] = useState(funding?.amountOverride !== null && funding?.amountOverride !== undefined
    ? String(funding.amountOverride) : funding?.rateOverride != null ? String(funding.rateOverride / 10).replace(".", ",") : "");
  const [error, setError] = useState<string | null>(null);
  const funder = options.find((f) => f.id === (funderId || options[0]?.id));
  const fixed = funder?.calculation === "MONTANT_FIXE";

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{funding ? "Modifier l’aide" : "Accorder une aide"}</DialogTitle>
          <DialogDescription>L’aide est déduite des factures des mois couverts, puis réclamée au financeur.</DialogDescription>
        </DialogHeader>
        <FormField label="Financeur">
          <select className={selectClass} value={funder?.id ?? ""} onChange={(e) => setFunderId(e.target.value)}>
            {options.map((f) => <option key={f.id} value={f.id}>{f.name}</option>)}
          </select>
        </FormField>
        <div className="grid grid-cols-2 gap-3">
          <FormField label="Du"><DateInput value={from} onChange={(e) => setFrom(e.target.value)} /></FormField>
          <FormField label="Au (facultatif)"><DateInput value={to} onChange={(e) => setTo(e.target.value)} /></FormField>
          <FormField label="Parent bénéficiaire"><Input value={beneficiary} maxLength={170} onChange={(e) => setBeneficiary(e.target.value)} /></FormField>
          <FormField label="N° de dossier"><Input value={fileNumber} maxLength={60} onChange={(e) => setFileNumber(e.target.value)} /></FormField>
        </div>
        <FormField label={fixed ? "Montant propre (F / mois, facultatif)" : "Taux propre (%, facultatif)"} hint="Laissez vide pour appliquer le barème du financeur.">
          <Input value={own} onChange={(e) => setOwn(e.target.value)} inputMode="decimal" />
        </FormField>
        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button
            onClick={async () => {
              const fromDay = parseDateFr(from);
              const toDay = to.trim() ? parseDateFr(to) : null;
              if (!funder) return setError("Ajoutez d’abord le financeur dans Paramètres › Financeurs et aides.");
              if (!fromDay || (to.trim() && !toDay)) return setError("Saisissez les dates au format JJ/MM/AAAA.");
              const value = own.trim();
              const amountOverride = fixed && value ? Number(value.replace(/\s/g, "")) : null;
              const rateOverride = !fixed && value ? parseRate(value) : null;
              if ((amountOverride !== null && !Number.isInteger(amountOverride)) || (!fixed && value && rateOverride === null)) {
                return setError("Montant en francs entiers, taux entre 0 et 100 %.");
              }
              const body = { funderId: funder.id, fromDay, toDay, beneficiary: textOrNull(beneficiary), fileNumber: textOrNull(fileNumber), amountOverride, rateOverride };
              try {
                await (funding ? billingApi.updateFunding(childId, funding.id, body) : billingApi.createFunding(childId, body));
                onSaved();
              } catch (e) {
                setError(errorMessage(e));
              }
            }}
          >
            Enregistrer
          </Button>
          <Button variant="ghost" onClick={onClose}>Annuler</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
