import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Info, Plus, Trash2, TriangleAlert } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { TonePill, type Tone } from "@/features/enrollment/components";
import { todayIso } from "@/lib/age";
import { errorMessage } from "@/lib/api";
import { isoToDateFr, parseDateFr } from "@/lib/datetime";
import { isoToField } from "@/lib/formFields";
import { staffingApi, type RatioRule, type RuleState } from "./api";

const states: Record<RuleState, { tone: Tone; label: string }> = {
  EN_VIGUEUR: { tone: "success", label: "En vigueur" },
  A_VENIR: { tone: "info", label: "À venir" },
  EXPIREE: { tone: "neutral", label: "Expirée" },
};

const ages = (r: Pick<RatioRule, "minAgeMonths" | "maxAgeMonths">) =>
  r.maxAgeMonths === null
    ? `${r.minAgeMonths === 0 ? "Tous âges" : `À partir de ${r.minAgeMonths} mois`}`
    : `De ${r.minAgeMonths} à ${r.maxAgeMonths} mois`;

/** Paramètres › Règles d'encadrement (maquette Paramètres) : ratios de l'agrément provincial, datés. */
export function RatioRulesTab() {
  const queryClient = useQueryClient();
  const rules = useQuery({ queryKey: ["ratio-rules"], queryFn: ({ signal }) => staffingApi.rules(signal) });
  const settings = useQuery({ queryKey: ["ratio-settings"], queryFn: ({ signal }) => staffingApi.settings(signal) });
  const [editing, setEditing] = useState<RatioRule | "new" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["ratio-rules"] });

  const remove = async (rule: RatioRule) => {
    if (!window.confirm(`Supprimer la règle « ${rule.label} » ?`)) return;
    try {
      await staffingApi.deleteRule(rule.id);
      await refresh();
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  const toggleAlert = async (alertDesktop: boolean) => {
    try {
      await staffingApi.updateSettings({ alertDesktop });
      await queryClient.invalidateQueries({ queryKey: ["ratio-settings"] });
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <>
      <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 className="m-0 text-title-3">Règles d’encadrement</h2>
            <p className="m-0 text-muted-foreground">
              Nombre maximal d’enfants par adulte présent, par tranche d’âge, sur une période de validité.
            </p>
          </div>
          <Button onClick={() => setEditing("new")}>
            <Plus aria-hidden />
            Ajouter une règle
          </Button>
        </div>
        <p className="m-0 flex items-start gap-2 rounded-md bg-info-100 px-3 py-2 text-info-700">
          <Info aria-hidden className="mt-0.5 size-4 shrink-0" />
          <span>
            <strong>L’indicateur d’encadrement applique la règle en vigueur à la date du jour.</strong> Saisissez les ratios
            fixés par votre agrément provincial. Une règle « À venir » remplace automatiquement l’ancienne à sa date de
            début : fixez une date de fin à l’ancienne.
          </span>
        </p>
        {error && <Alert>{error}</Alert>}
        {rules.isError && <p className="text-danger-700">{errorMessage(rules.error)}</p>}
        <table className="w-full border-collapse text-left">
          <caption className="sr-only">Règles d’encadrement</caption>
          <thead>
            <tr className="border-b border-border-subtle text-label text-muted-foreground">
              <th scope="col" className="py-2">Tranche d’âge</th>
              <th scope="col" className="py-2">Ratio</th>
              <th scope="col" className="py-2">Valide du</th>
              <th scope="col" className="py-2">Jusqu’au</th>
              <th scope="col" className="py-2">Statut</th>
              <th scope="col" className="py-2"><span className="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody className="tabular-nums">
            {rules.data?.map((r) => (
              <tr key={r.id} className="border-b border-border-subtle last:border-0">
                <td className="py-2">
                  {r.label}
                  <span className="block text-small text-muted-foreground">{ages(r)}</span>
                </td>
                <td className="py-2">1 adulte pour {r.childrenPerAdult} enfants</td>
                <td className="py-2">{isoToDateFr(r.validFrom)}</td>
                <td className="py-2">{r.validTo ? isoToDateFr(r.validTo) : "—"}</td>
                <td className="py-2"><TonePill tone={states[r.state].tone}>{states[r.state].label}</TonePill></td>
                <td className="py-2 text-right whitespace-nowrap">
                  <Button variant="ghost" size="sm" onClick={() => setEditing(r)}>Modifier</Button>
                  <Button variant="ghost" size="sm" className="text-danger-700" onClick={() => void remove(r)} aria-label={`Supprimer la règle ${r.label}`}>
                    <Trash2 aria-hidden />
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <h2 className="m-0 flex items-center gap-2 text-title-3 text-warning-700">
          <TriangleAlert aria-hidden className="size-5" />
          <span className="text-foreground">Déclenchement des alertes</span>
        </h2>
        <label className="flex items-center gap-3">
          <input
            type="checkbox"
            className="size-5"
            checked={settings.data?.alertDesktop ?? true}
            disabled={!settings.data}
            onChange={(e) => void toggleAlert(e.target.checked)}
          />
          Prévenir la direction sur le PC du bureau en cas de dépassement
        </label>
        <p className="m-0 text-small text-muted-foreground">
          La tablette d’accueil affiche toujours l’alerte dans son bandeau d’encadrement.
        </p>
      </section>

      {editing && <RuleDialog rule={editing === "new" ? null : editing} onClose={() => setEditing(null)} onDone={refresh} />}
    </>
  );
}

function RuleDialog({ rule, onClose, onDone }: { rule: RatioRule | null; onClose: () => void; onDone: () => Promise<unknown> }) {
  const [label, setLabel] = useState(rule?.label ?? "");
  const [min, setMin] = useState(String(rule?.minAgeMonths ?? 0));
  const [max, setMax] = useState(rule?.maxAgeMonths === null || rule === null ? "" : String(rule.maxAgeMonths));
  const [ratio, setRatio] = useState(String(rule?.childrenPerAdult ?? 8));
  const [from, setFrom] = useState(isoToField(rule?.validFrom ?? todayIso()));
  const [to, setTo] = useState(isoToField(rule?.validTo));
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    const validFrom = parseDateFr(from);
    const validTo = to.trim() ? parseDateFr(to) : null;
    if (!label.trim()) return setError("Indiquez le libellé de la tranche (« Enfants qui marchent »).");
    if (!validFrom || (to.trim() && !validTo)) return setError("Dates attendues au format JJ/MM/AAAA.");
    const body = {
      label: label.trim(),
      minAgeMonths: Number(min),
      maxAgeMonths: max.trim() === "" ? null : Number(max),
      childrenPerAdult: Number(ratio),
      validFrom,
      validTo,
    };
    try {
      if (rule) await staffingApi.updateRule(rule.id, body);
      else await staffingApi.createRule(body);
      await onDone();
      onClose();
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{rule ? "Modifier la règle" : "Ajouter une règle"}</DialogTitle>
          <DialogDescription>Ratio de l’agrément provincial pour une tranche d’âge.</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <FormField label="Tranche d’âge" className="sm:col-span-2">
            <Input value={label} maxLength={80} onChange={(e) => setLabel(e.target.value)} />
          </FormField>
          <FormField label="À partir de (mois)">
            <Input type="number" min={0} max={120} value={min} onChange={(e) => setMin(e.target.value)} />
          </FormField>
          <FormField label="Jusqu’à (mois, exclu)" hint="Vide : sans limite d’âge.">
            <Input type="number" min={1} max={121} value={max} onChange={(e) => setMax(e.target.value)} />
          </FormField>
          <FormField label="Enfants par adulte" className="sm:col-span-2">
            <Input type="number" min={1} max={30} value={ratio} onChange={(e) => setRatio(e.target.value)} />
          </FormField>
          <FormField label="Valide à partir du">
            <Input placeholder="JJ/MM/AAAA" value={from} onChange={(e) => setFrom(e.target.value)} />
          </FormField>
          <FormField label="Jusqu’au (facultatif)">
            <Input placeholder="JJ/MM/AAAA" value={to} onChange={(e) => setTo(e.target.value)} />
          </FormField>
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
