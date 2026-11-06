import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Trash2 } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { TonePill } from "@/features/enrollment/components";
import {
  billingApi,
  billingKeys,
  categoryLabels,
  funderValue,
  parseRate,
  type Funder,
  type FunderCategory,
  type FunderInput,
} from "@/features/billing/api";
import { todayIso } from "@/lib/age";
import { errorMessage } from "@/lib/api";
import { isoToDateFr, parseDateFr } from "@/lib/datetime";
import { formatXpf } from "@/lib/format";
import { isoToField, selectClass, textOrNull } from "@/lib/formFields";

/**
 * Paramètres › Financeurs et aides (maquette) : aides déduites des factures (une ligne négative, réclamée ensuite au
 * financeur) ou subventions à la structure (hors factures).
 */
export function FundersTab() {
  const queryClient = useQueryClient();
  const funders = useQuery({ queryKey: billingKeys.funders, queryFn: ({ signal }) => billingApi.funders(signal) });
  const [editing, setEditing] = useState<Funder | "new" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const today = todayIso();
  const refresh = () => queryClient.invalidateQueries({ queryKey: billingKeys.funders });

  return (
    <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="m-0 text-title-3">Financeurs et aides</h2>
          <p className="m-0 text-muted-foreground">Subventions versées à la structure et aides déduites des factures des familles.</p>
        </div>
        <Button onClick={() => setEditing("new")}><Plus aria-hidden />Ajouter un financeur</Button>
      </div>
      {error && <Alert>{error}</Alert>}
      {funders.isError && <Alert>{errorMessage(funders.error)}</Alert>}
      <table className="w-full border-collapse text-left tabular-nums">
        <caption className="sr-only">Financeurs</caption>
        <thead>
          <tr className="border-b border-border-subtle text-label text-muted-foreground">
            <th scope="col" className="py-2">Financeur</th>
            <th scope="col" className="py-2">Type</th>
            <th scope="col" className="py-2">Montant ou taux</th>
            <th scope="col" className="py-2">Du</th>
            <th scope="col" className="py-2">Au</th>
            <th scope="col" className="py-2">Statut</th>
            <th scope="col" className="py-2"><span className="sr-only">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          {funders.data?.map((f) => {
            const expired = f.validTo !== null && f.validTo < today;
            const upcoming = f.validFrom > today;
            return (
              <tr key={f.id} className="border-b border-border-subtle last:border-0">
                <td className="py-2">
                  {f.name}
                  <span className="block text-small text-muted-foreground">
                    {categoryLabels[f.category]}{f.kind === "AIDE_FAMILLE" && ` · ${f.beneficiaries} bénéficiaire${f.beneficiaries > 1 ? "s" : ""}`}
                  </span>
                </td>
                <td className="py-2">{f.kind === "AIDE_FAMILLE" ? "Déduite de la facture" : "Subvention à la structure"}</td>
                <td className="py-2">{f.kind === "AIDE_FAMILLE" ? funderValue(f, formatXpf) : f.amount > 0 ? formatXpf(f.amount) : "—"}</td>
                <td className="py-2">{isoToDateFr(f.validFrom)}</td>
                <td className="py-2">{f.validTo ? isoToDateFr(f.validTo) : "—"}</td>
                <td className="py-2">
                  <TonePill tone={expired ? "neutral" : upcoming ? "info" : "success"}>{expired ? "Expiré" : upcoming ? "À venir" : "En vigueur"}</TonePill>
                </td>
                <td className="py-2 text-right whitespace-nowrap">
                  <Button variant="ghost" size="sm" onClick={() => setEditing(f)}>Modifier</Button>
                  {f.beneficiaries === 0 && (
                    <Button variant="ghost" size="sm" className="text-danger-700" aria-label={`Supprimer ${f.name}`}
                      onClick={async () => {
                        if (!window.confirm(`Supprimer le financeur « ${f.name} » ?`)) return;
                        try {
                          await billingApi.deleteFunder(f.id);
                          await refresh();
                        } catch (e) {
                          setError(errorMessage(e));
                        }
                      }}>
                      <Trash2 aria-hidden />
                    </Button>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <dl className="m-0 grid gap-3 text-small md:grid-cols-2">
        <div>
          <dt className="font-semibold">Aide déduite de la facture</dt>
          <dd className="m-0 text-muted-foreground">Apparaît comme une ligne négative sur la facture de la famille. Le reste à charge est calculé après déduction, puis l’aide est réclamée au financeur.</dd>
        </div>
        <div>
          <dt className="font-semibold">Subvention à la structure</dt>
          <dd className="m-0 text-muted-foreground">N’apparaît pas sur les factures. Son suivi (recettes, bilan) arrivera avec la comptabilité.</dd>
        </div>
      </dl>
      {editing && (
        <FunderDialog funder={editing === "new" ? null : editing} onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); void refresh(); }} />
      )}
    </section>
  );
}

function FunderDialog({ funder, onClose, onSaved }: { funder: Funder | null; onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState({
    name: funder?.name ?? "",
    kind: funder?.kind ?? "AIDE_FAMILLE",
    category: funder?.category ?? "ORGANISME",
    calculation: funder?.calculation ?? "MONTANT_FIXE",
    amount: String(funder?.amount ?? ""),
    rate: funder ? String(funder.rate / 10).replace(".", ",") : "",
    rateBase: funder?.rateBase ?? "FRAIS_GARDE",
    monthlyCap: funder?.monthlyCap === null || funder?.monthlyCap === undefined ? "" : String(funder.monthlyCap),
    priority: String(funder?.priority ?? 0),
    validFrom: isoToField(funder?.validFrom) || isoToField(todayIso()),
    validTo: isoToField(funder?.validTo),
    conventionRef: funder?.conventionRef ?? "",
    contact: funder?.contact ?? "",
  });
  const [error, setError] = useState<string | null>(null);
  const set = (patch: Partial<typeof form>) => setForm((f) => ({ ...f, ...patch }));
  const aid = form.kind === "AIDE_FAMILLE";
  const fixed = form.calculation === "MONTANT_FIXE";

  const submit = async () => {
    const validFrom = parseDateFr(form.validFrom);
    const validTo = form.validTo.trim() ? parseDateFr(form.validTo) : null;
    if (!form.name.trim()) return setError("Indiquez le nom du financeur.");
    if (!validFrom || (form.validTo.trim() && !validTo)) return setError("Saisissez les dates au format JJ/MM/AAAA.");
    const amount = form.amount.trim() ? Number(form.amount.replace(/\s/g, "")) : 0;
    const rate = form.rate.trim() ? parseRate(form.rate) : 0;
    const cap = form.monthlyCap.trim() ? Number(form.monthlyCap.replace(/\s/g, "")) : null;
    if (!Number.isInteger(amount) || amount < 0 || rate === null || (cap !== null && !Number.isInteger(cap))) {
      return setError("Montants en francs entiers, taux entre 0 et 100 %.");
    }
    const body: FunderInput = {
      name: form.name.trim(), kind: form.kind, category: form.category, calculation: form.calculation, amount, rate,
      rateBase: form.rateBase, monthlyCap: cap, priority: Number(form.priority) || 0, validFrom, validTo,
      conventionRef: textOrNull(form.conventionRef), contact: textOrNull(form.contact),
    };
    try {
      await (funder ? billingApi.updateFunder(funder.id, body) : billingApi.createFunder(body));
      onSaved();
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>{funder ? `Modifier ${funder.name}` : "Ajouter un financeur"}</DialogTitle>
          <DialogDescription>Les factures déjà émises gardent l’aide calculée ; la modification vaut pour les mois à venir.</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-2 gap-3">
          <FormField label="Nom" className="col-span-2"><Input value={form.name} maxLength={120} onChange={(e) => set({ name: e.target.value })} /></FormField>
          <FormField label="Type">
            <select className={selectClass} value={form.kind} onChange={(e) => set({ kind: e.target.value as Funder["kind"] })}>
              <option value="AIDE_FAMILLE">Aide déduite de la facture</option>
              <option value="SUBVENTION_STRUCTURE">Subvention à la structure</option>
            </select>
          </FormField>
          <FormField label="Catégorie">
            <select className={selectClass} value={form.category} onChange={(e) => set({ category: e.target.value as FunderCategory })}>
              {Object.entries(categoryLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select>
          </FormField>
          {aid && (
            <>
              <FormField label="Calcul">
                <select className={selectClass} value={form.calculation} onChange={(e) => set({ calculation: e.target.value as Funder["calculation"] })}>
                  <option value="MONTANT_FIXE">Montant fixe par enfant et par mois</option>
                  <option value="POURCENTAGE">Pourcentage</option>
                </select>
              </FormField>
              {fixed ? (
                <FormField label="Montant mensuel (F)"><Input value={form.amount} inputMode="numeric" onChange={(e) => set({ amount: e.target.value })} /></FormField>
              ) : (
                <div className="grid grid-cols-2 gap-3">
                  <FormField label="Taux (%)"><Input value={form.rate} inputMode="decimal" onChange={(e) => set({ rate: e.target.value })} /></FormField>
                  <FormField label="De">
                    <select className={selectClass} value={form.rateBase} onChange={(e) => set({ rateBase: e.target.value as Funder["rateBase"] })}>
                      <option value="FRAIS_GARDE">Frais de garde</option>
                      <option value="FORFAIT">Forfait seul</option>
                    </select>
                  </FormField>
                </div>
              )}
              <FormField label="Plafond mensuel par enfant (F)" hint="Facultatif"><Input value={form.monthlyCap} inputMode="numeric" onChange={(e) => set({ monthlyCap: e.target.value })} /></FormField>
              <FormField label="Priorité" hint="0 s’applique en premier"><Input value={form.priority} inputMode="numeric" onChange={(e) => set({ priority: e.target.value })} /></FormField>
            </>
          )}
          {!aid && <FormField label="Montant (F, facultatif)"><Input value={form.amount} inputMode="numeric" onChange={(e) => set({ amount: e.target.value })} /></FormField>}
          <FormField label="Valide du"><Input value={form.validFrom} onChange={(e) => set({ validFrom: e.target.value })} /></FormField>
          <FormField label="Jusqu’au (facultatif)"><Input value={form.validTo} onChange={(e) => set({ validTo: e.target.value })} /></FormField>
          <FormField label="N° de convention"><Input value={form.conventionRef} maxLength={80} onChange={(e) => set({ conventionRef: e.target.value })} /></FormField>
          <FormField label="Contact"><Input value={form.contact} maxLength={200} onChange={(e) => set({ contact: e.target.value })} /></FormField>
        </div>
        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button onClick={() => void submit()}>Enregistrer</Button>
          <Button variant="ghost" onClick={onClose}>Annuler</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
