import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Info, Plus, Trash2 } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { DateInput } from "@/components/DateInput";
import { Input } from "@/components/ui/input";
import {
  billingApi,
  billingKeys,
  formatRate,
  parseRate,
  serviceLabels,
  type AbsenceReason,
  type BillingRule,
  type BillingRuleInput,
  type TgcRate,
} from "@/features/billing/api";
import { todayIso } from "@/lib/age";
import { errorMessage } from "@/lib/api";
import { isoToDateFr, parseDateFr } from "@/lib/datetime";
import { formatXpf } from "@/lib/format";
import { isoToField, selectClass } from "@/lib/formFields";

const ROUNDING = [
  { value: 1, label: "À la minute" },
  { value: 5, label: "Aux 5 minutes" },
  { value: 15, label: "Au quart d’heure" },
  { value: 30, label: "À la demi-heure" },
  { value: 60, label: "À l’heure" },
];
const TOTAL_STEPS = [
  { value: 1, label: "Au franc (1 F)" },
  { value: 10, label: "À la dizaine (10 F)" },
  { value: 100, label: "À la centaine (100 F)" },
];
const MODES = { PROCHE: "au plus proche", INFERIEUR: "à l’inférieur", SUPERIEUR: "au supérieur" } as const;

const roundMinutes = (minutes: number, step: number) => (step <= 1 ? minutes : Math.round(minutes / step) * step);

/** Exemple de la maquette : départ prévu 16:45, réel 17:02, tolérance et arrondi de la règle. */
function extraExample(r: Pick<BillingRule, "extraToleranceMinutes" | "extraRoundingMinutes">) {
  const late = 17;
  if (late <= r.extraToleranceMinutes) return `Départ prévu 16:45, départ réel 17:02 : 17 min, dans la tolérance de ${r.extraToleranceMinutes} min, non facturées.`;
  return `Départ prévu 16:45, départ réel 17:02 → 17 min au-delà de la tolérance de ${r.extraToleranceMinutes} min, facturées ${roundMinutes(late, r.extraRoundingMinutes)} min.`;
}

/**
 * Paramètres › Barème et facturation (maquette) : règles de facturation datées, absences déductibles, taux de TGC.
 * Les factures déjà émises ne changent jamais.
 */
export function BillingRulesTab() {
  const queryClient = useQueryClient();
  const rules = useQuery({ queryKey: billingKeys.rules, queryFn: ({ signal }) => billingApi.rules(signal) });
  const [editing, setEditing] = useState<BillingRule | "new" | null>(null);
  const today = todayIso();
  const current = rules.data?.find((r) => r.validFrom <= today && (r.validTo === null || r.validTo >= today)) ?? rules.data?.[0];

  return (
    <>
      <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 className="m-0 text-title-3">Règles de facturation</h2>
            <p className="m-0 text-muted-foreground">Heures complémentaires, départs après la fermeture, absences, échéance et arrondi du total.</p>
          </div>
          <Button variant="outline" onClick={() => setEditing("new")}><Plus aria-hidden />Nouvelle période</Button>
        </div>
        {rules.isError && <Alert>{errorMessage(rules.error)}</Alert>}
        {current && (
          <dl className="m-0 grid gap-4 md:grid-cols-3">
            <div className="flex flex-col gap-1">
              <dt className="text-label">Heures complémentaires</dt>
              <dd className="m-0">
                {current.extraRateMode === "CONTRAT" ? "Au tarif horaire du contrat" : `${formatXpf(current.extraHourlyRate)} de l’heure`},
                au-delà de {current.extraToleranceMinutes} min, {ROUNDING.find((o) => o.value === current.extraRoundingMinutes)?.label.toLowerCase()}.
              </dd>
              <dd className="m-0 text-small text-muted-foreground">{extraExample(current)}</dd>
            </div>
            <div className="flex flex-col gap-1">
              <dt className="text-label">Départs après la fermeture</dt>
              <dd className="m-0">{formatXpf(current.overtimeFee)} par départ, au-delà de {current.overtimeToleranceMinutes} min après la fermeture.</dd>
              <dt className="mt-2 text-label">Absences</dt>
              <dd className="m-0">
                {current.sickWaitingDays} jour{current.sickWaitingDays > 1 ? "s" : ""} de carence en cas de maladie,
                congés à prévenir {current.leaveNoticeDays} jours à l’avance.
              </dd>
            </div>
            <div className="flex flex-col gap-1">
              <dt className="text-label">Échéance et arrondis</dt>
              <dd className="m-0">Paiement à {current.paymentDays} jours. Total arrondi {TOTAL_STEPS.find((o) => o.value === current.totalRoundingStep)?.label.toLowerCase()}, {MODES[current.totalRoundingMode]}.</dd>
              <dd className="m-0 text-small text-muted-foreground">Le franc CFP n’a pas de décimales : chaque ligne est arrondie au franc, puis le total selon ce réglage.</dd>
              <dd className="m-0">Contrats : {current.defaultBillingMode === "REEL" ? "facturés au réel des jours du mois" : "mensualisés, le même montant chaque mois"} (modifiable par contrat).</dd>
            </div>
          </dl>
        )}
        <table className="w-full border-collapse text-left tabular-nums">
          <caption className="sr-only">Périodes des règles de facturation</caption>
          <thead>
            <tr className="border-b border-border-subtle text-label text-muted-foreground">
              <th scope="col" className="py-2">Valide du</th>
              <th scope="col" className="py-2">Jusqu’au</th>
              <th scope="col" className="py-2">Heures complémentaires</th>
              <th scope="col" className="py-2">Départ après fermeture</th>
              <th scope="col" className="py-2"><span className="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            {rules.data?.map((r) => (
              <tr key={r.id} className="border-b border-border-subtle last:border-0">
                <td className="py-2">{isoToDateFr(r.validFrom)}</td>
                <td className="py-2">{r.validTo ? isoToDateFr(r.validTo) : "—"}</td>
                <td className="py-2">{r.extraRateMode === "CONTRAT" ? "Tarif du contrat" : formatXpf(r.extraHourlyRate)} · tolérance {r.extraToleranceMinutes} min</td>
                <td className="py-2">{formatXpf(r.overtimeFee)}</td>
                <td className="py-2 text-right"><Button variant="ghost" size="sm" onClick={() => setEditing(r)}>Modifier</Button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
      <AbsenceReasons />
      <TgcRates />
      {editing && (
        <RuleDialog
          rule={editing === "new" ? null : editing}
          base={current}
          onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); void queryClient.invalidateQueries({ queryKey: billingKeys.rules }); }}
        />
      )}
    </>
  );
}

function RuleDialog({ rule, base, onClose, onSaved }: { rule: BillingRule | null; base?: BillingRule; onClose: () => void; onSaved: () => void }) {
  const source = rule ?? base;
  const [form, setForm] = useState({
    validFrom: rule ? isoToField(rule.validFrom) : "",
    validTo: isoToField(rule?.validTo),
    extraRateMode: source?.extraRateMode ?? "CONTRAT",
    extraHourlyRate: String(source?.extraHourlyRate ?? 0),
    extraToleranceMinutes: String(source?.extraToleranceMinutes ?? 10),
    extraRoundingMinutes: source?.extraRoundingMinutes ?? 15,
    overtimeFee: String(source?.overtimeFee ?? 1000),
    overtimeToleranceMinutes: String(source?.overtimeToleranceMinutes ?? 10),
    sickWaitingDays: String(source?.sickWaitingDays ?? 1),
    leaveNoticeDays: String(source?.leaveNoticeDays ?? 15),
    paymentDays: String(source?.paymentDays ?? 10),
    totalRoundingStep: source?.totalRoundingStep ?? 1,
    totalRoundingMode: source?.totalRoundingMode ?? "PROCHE",
    defaultBillingMode: source?.defaultBillingMode ?? "MENSUALISE",
  });
  const [error, setError] = useState<string | null>(null);
  const set = (patch: Partial<typeof form>) => setForm((f) => ({ ...f, ...patch }));
  const int = (v: string) => (/^\d+$/.test(v.trim()) ? Number(v.trim()) : NaN);

  const submit = async () => {
    const validFrom = parseDateFr(form.validFrom);
    const validTo = form.validTo.trim() ? parseDateFr(form.validTo) : null;
    if (!validFrom || (form.validTo.trim() && !validTo)) return setError("Saisissez les dates au format JJ/MM/AAAA.");
    const body: BillingRuleInput = {
      validFrom, validTo,
      extraRateMode: form.extraRateMode, extraHourlyRate: int(form.extraHourlyRate),
      extraToleranceMinutes: int(form.extraToleranceMinutes), extraRoundingMinutes: form.extraRoundingMinutes,
      overtimeFee: int(form.overtimeFee), overtimeToleranceMinutes: int(form.overtimeToleranceMinutes),
      sickWaitingDays: int(form.sickWaitingDays), leaveNoticeDays: int(form.leaveNoticeDays), paymentDays: int(form.paymentDays),
      totalRoundingStep: form.totalRoundingStep, totalRoundingMode: form.totalRoundingMode,
      defaultBillingMode: form.defaultBillingMode,
    };
    if (Object.values(body).some((v) => typeof v === "number" && Number.isNaN(v))) return setError("Les montants et durées sont des nombres entiers.");
    try {
      await (rule ? billingApi.updateRule(rule.id, body) : billingApi.createRule(body));
      onSaved();
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>{rule ? "Modifier la règle de facturation" : "Nouvelle période de facturation"}</DialogTitle>
          <DialogDescription>S’applique aux factures générées à partir de maintenant pour les mois de sa période ; les factures déjà émises ne changent pas.</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-2 gap-3">
          <FormField label="Valide du" hint="JJ/MM/AAAA"><DateInput value={form.validFrom} onChange={(e) => set({ validFrom: e.target.value })} /></FormField>
          <FormField label="Jusqu’au (facultatif)"><DateInput value={form.validTo} onChange={(e) => set({ validTo: e.target.value })} /></FormField>
          <FormField label="Tarif des heures complémentaires">
            <select className={selectClass} value={form.extraRateMode} onChange={(e) => set({ extraRateMode: e.target.value as "CONTRAT" | "FIXE" })}>
              <option value="CONTRAT">Tarif horaire du contrat</option>
              <option value="FIXE">Tarif fixe</option>
            </select>
          </FormField>
          {form.extraRateMode === "FIXE" ? (
            <FormField label="Tarif fixe (F de l’heure)"><Input value={form.extraHourlyRate} inputMode="numeric" onChange={(e) => set({ extraHourlyRate: e.target.value })} /></FormField>
          ) : <div />}
          <FormField label="Tolérance (minutes)"><Input value={form.extraToleranceMinutes} inputMode="numeric" onChange={(e) => set({ extraToleranceMinutes: e.target.value })} /></FormField>
          <FormField label="Arrondi du temps">
            <select className={selectClass} value={form.extraRoundingMinutes} onChange={(e) => set({ extraRoundingMinutes: Number(e.target.value) })}>
              {ROUNDING.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
          </FormField>
          <FormField label="Départ après la fermeture (F par départ)"><Input value={form.overtimeFee} inputMode="numeric" onChange={(e) => set({ overtimeFee: e.target.value })} /></FormField>
          <FormField label="Tolérance après la fermeture (minutes)"><Input value={form.overtimeToleranceMinutes} inputMode="numeric" onChange={(e) => set({ overtimeToleranceMinutes: e.target.value })} /></FormField>
          <FormField label="Jours de carence (maladie)"><Input value={form.sickWaitingDays} inputMode="numeric" onChange={(e) => set({ sickWaitingDays: e.target.value })} /></FormField>
          <FormField label="Préavis des congés (jours)"><Input value={form.leaveNoticeDays} inputMode="numeric" onChange={(e) => set({ leaveNoticeDays: e.target.value })} /></FormField>
          <FormField label="Délai de paiement (jours)"><Input value={form.paymentDays} inputMode="numeric" onChange={(e) => set({ paymentDays: e.target.value })} /></FormField>
          <FormField label="Arrondi du total">
            <select className={selectClass} value={form.totalRoundingStep} onChange={(e) => set({ totalRoundingStep: Number(e.target.value) })}>
              {TOTAL_STEPS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
          </FormField>
          <FormField label="Sens de l’arrondi">
            <select className={selectClass} value={form.totalRoundingMode} onChange={(e) => set({ totalRoundingMode: e.target.value as BillingRule["totalRoundingMode"] })}>
              {Object.entries(MODES).map(([value, label]) => <option key={value} value={value}>{label.charAt(0).toUpperCase() + label.slice(1)}</option>)}
            </select>
          </FormField>
          <FormField label="Mode de facturation proposé aux contrats" className="md:col-span-2"
            hint="Chaque contrat peut en changer à la signature. Mensualisé : heures par semaine × semaines d’accueil par an ÷ 12, le même montant chaque mois. Au réel : les jours du planning dans le mois (4 ou 5 mardis…).">
            <select className={selectClass} value={form.defaultBillingMode} onChange={(e) => set({ defaultBillingMode: e.target.value as BillingRule["defaultBillingMode"] })}>
              <option value="MENSUALISE">Mensualisé — le même montant chaque mois</option>
              <option value="REEL">Au réel — selon les jours du mois</option>
            </select>
          </FormField>
        </div>
        <p className="m-0 text-small text-muted-foreground">{extraExample({ extraToleranceMinutes: int(form.extraToleranceMinutes) || 0, extraRoundingMinutes: form.extraRoundingMinutes })}</p>
        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button onClick={() => void submit()}>Enregistrer</Button>
          <Button variant="ghost" onClick={onClose}>Annuler</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

const FLAGS: { key: "deductible" | "proofRequired" | "waitingDaysApply" | "noticeApplies" | "active"; label: string }[] = [
  { key: "deductible", label: "Déduite" },
  { key: "proofRequired", label: "Justificatif exigé" },
  { key: "waitingDaysApply", label: "Carence" },
  { key: "noticeApplies", label: "Préavis" },
  { key: "active", label: "Proposé" },
];

/** Motifs d'absence de la maquette (« Absences déductibles »), modifiables en place. */
function AbsenceReasons() {
  const queryClient = useQueryClient();
  const reasons = useQuery({ queryKey: billingKeys.reasons, queryFn: ({ signal }) => billingApi.reasons(signal) });
  const [label, setLabel] = useState("");
  const [error, setError] = useState<string | null>(null);
  const refresh = () => queryClient.invalidateQueries({ queryKey: billingKeys.reasons });
  const save = async (r: AbsenceReason, patch: Partial<AbsenceReason>) => {
    setError(null);
    try {
      const next = { ...r, ...patch };
      await billingApi.updateReason(r.id, {
        label: next.label, deductible: next.deductible, proofRequired: next.proofRequired, waitingDaysApply: next.waitingDaysApply,
        noticeApplies: next.noticeApplies, displayOrder: next.displayOrder, active: next.active,
      });
      await refresh();
    } catch (e) {
      setError(errorMessage(e));
    }
  };
  return (
    <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <div>
        <h2 className="m-0 text-title-3">Absences déductibles</h2>
        <p className="m-0 text-muted-foreground">Motifs proposés quand une absence est signalée, et leur effet sur la facture (règlement de fonctionnement).</p>
      </div>
      {error && <Alert>{error}</Alert>}
      <table className="w-full border-collapse text-left">
        <caption className="sr-only">Motifs d’absence</caption>
        <thead>
          <tr className="border-b border-border-subtle text-label text-muted-foreground">
            <th scope="col" className="py-2">Motif</th>
            {FLAGS.map((f) => <th key={f.key} scope="col" className="py-2 text-center">{f.label}</th>)}
            <th scope="col" className="py-2"><span className="sr-only">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          {reasons.data?.map((r) => (
            <tr key={r.id} className="border-b border-border-subtle last:border-0">
              <td className="py-2">{r.label}</td>
              {FLAGS.map((f) => (
                <td key={f.key} className="py-2 text-center">
                  <input
                    type="checkbox"
                    aria-label={`${f.label} : ${r.label}`}
                    checked={r[f.key]}
                    disabled={f.key !== "deductible" && f.key !== "active" && !r.deductible}
                    onChange={(e) => void save(r, { [f.key]: e.target.checked })}
                  />
                </td>
              ))}
              <td className="py-2 text-right">
                {!r.used && (
                  <Button variant="ghost" size="sm" className="text-danger-700" aria-label={`Supprimer le motif ${r.label}`}
                    onClick={async () => {
                      try {
                        await billingApi.deleteReason(r.id);
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
          ))}
        </tbody>
      </table>
      <div className="flex flex-wrap items-end gap-2">
        <FormField label="Nouveau motif"><Input value={label} maxLength={80} onChange={(e) => setLabel(e.target.value)} /></FormField>
        <Button variant="outline" disabled={!label.trim()}
          onClick={async () => {
            setError(null);
            try {
              await billingApi.createReason({
                label: label.trim(), deductible: false, proofRequired: false, waitingDaysApply: false, noticeApplies: false,
                displayOrder: (reasons.data?.length ?? 0), active: true,
              });
              setLabel("");
              await refresh();
            } catch (e) {
              setError(errorMessage(e));
            }
          }}>
          <Plus aria-hidden />Ajouter
        </Button>
      </div>
    </section>
  );
}

/** Taux de TGC par prestation (0 % exonéré par défaut, à confirmer par l'expert-comptable). */
function TgcRates() {
  const queryClient = useQueryClient();
  const rates = useQuery({ queryKey: billingKeys.tgc, queryFn: ({ signal }) => billingApi.tgcRates(signal) });
  const [editing, setEditing] = useState<TgcRate | null>(null);
  return (
    <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <div>
        <h2 className="m-0 text-title-3">TGC</h2>
        <p className="m-0 text-muted-foreground">Taux de la taxe générale sur la consommation, par prestation.</p>
      </div>
      <p className="m-0 flex items-start gap-2 rounded-md bg-info-100 px-3 py-2 text-info-700">
        <Info aria-hidden className="mt-0.5 size-4 shrink-0" />
        <span>Les prestations sont réglées « exonérées » par défaut : <strong>faites confirmer ces taux par votre expert-comptable</strong> avant la première facturation.</span>
      </p>
      <table className="w-full border-collapse text-left tabular-nums">
        <caption className="sr-only">Taux de TGC</caption>
        <thead>
          <tr className="border-b border-border-subtle text-label text-muted-foreground">
            <th scope="col" className="py-2">Prestation</th>
            <th scope="col" className="py-2">Taux</th>
            <th scope="col" className="py-2">Valide du</th>
            <th scope="col" className="py-2">Jusqu’au</th>
            <th scope="col" className="py-2"><span className="sr-only">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          {rates.data?.map((r) => (
            <tr key={r.id} className="border-b border-border-subtle last:border-0">
              <td className="py-2">{serviceLabels[r.service]}</td>
              <td className="py-2">{r.exempt ? "Exonéré" : formatRate(r.rate)}</td>
              <td className="py-2">{isoToDateFr(r.validFrom)}</td>
              <td className="py-2">{r.validTo ? isoToDateFr(r.validTo) : "—"}</td>
              <td className="py-2 text-right"><Button variant="ghost" size="sm" onClick={() => setEditing(r)}>Modifier</Button></td>
            </tr>
          ))}
        </tbody>
      </table>
      {editing && (
        <TgcDialog rate={editing} onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); void queryClient.invalidateQueries({ queryKey: billingKeys.tgc }); }} />
      )}
    </section>
  );
}

function TgcDialog({ rate, onClose, onSaved }: { rate: TgcRate; onClose: () => void; onSaved: () => void }) {
  const [exempt, setExempt] = useState(rate.exempt);
  const [value, setValue] = useState(rate.exempt ? "" : String(rate.rate / 10).replace(".", ","));
  const [validFrom, setValidFrom] = useState(isoToField(rate.validFrom));
  const [error, setError] = useState<string | null>(null);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>TGC · {serviceLabels[rate.service]}</DialogTitle>
          <DialogDescription>Le taux s’applique aux factures générées pour les mois de sa période.</DialogDescription>
        </DialogHeader>
        <label className="flex items-center gap-2"><input type="checkbox" checked={exempt} onChange={(e) => setExempt(e.target.checked)} />Prestation exonérée</label>
        {!exempt && <FormField label="Taux (%)" hint="Par exemple 6 ou 3"><Input value={value} inputMode="decimal" onChange={(e) => setValue(e.target.value)} /></FormField>}
        <FormField label="Valide du"><DateInput value={validFrom} onChange={(e) => setValidFrom(e.target.value)} /></FormField>
        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button
            onClick={async () => {
              const from = parseDateFr(validFrom);
              const tenths = exempt ? 0 : parseRate(value);
              if (!from) return setError("Saisissez la date au format JJ/MM/AAAA.");
              if (tenths === null) return setError("Saisissez un taux entre 0 et 100 %.");
              try {
                await billingApi.updateTgcRate(rate.id, { service: rate.service, rate: tenths, exempt, validFrom: from, validTo: rate.validTo });
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
