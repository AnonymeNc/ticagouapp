import { useState } from "react";
import { Link } from "react-router";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { FileDown, FilePlus2, Pencil, PenLine, Plus, Trash2, TriangleAlert } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { DateInput } from "@/components/DateInput";
import { Input } from "@/components/ui/input";
import { childKeys, type ChildRecord } from "@/features/children/api";
import { TonePill, type Tone } from "@/features/enrollment/components";
import { useStructure } from "@/features/structure/useStructure";
import { todayIso } from "@/lib/age";
import { ApiError, errorMessage } from "@/lib/api";
import { isoToDateFr, parseDateFr } from "@/lib/datetime";
import { DAY_NAMES, formatMinutes, formatMinutesDelta, formatXpf, hhmm, span } from "@/lib/format";
import { selectClass } from "@/lib/formFields";
import { cn } from "@/lib/utils";
import { contractKeys, contractsApi, stateLabels, type BillingMode, type Contract, type ContractState, type ContractVersion } from "./api";
import { BillingModeChoice, FormulaChoice, PlanningEditor, initialDays, toDayInputs, weeklyMinutes } from "./PlanningEditor";

const stateTones: Record<ContractState, Tone> = { BROUILLON: "neutral", A_VENIR: "info", EN_COURS: "success", TERMINE: "neutral" };

/** Onglet Contrat de la fiche enfant (maquette Contrat d'accueil › avenants). */
export function ContractTab({ child, canEdit }: { child: ChildRecord; canEdit: boolean }) {
  const contracts = useQuery({ queryKey: contractKeys.forChild(child.id), queryFn: ({ signal }) => contractsApi.forChild(child.id, signal) });
  if (contracts.isPending) return <p className="text-muted-foreground">Chargement des contrats…</p>;
  if (contracts.isError) return <p className="text-danger-700">{errorMessage(contracts.error)}</p>;
  const list = contracts.data;
  return (
    <div className="flex flex-col gap-4">
      {list.length === 0 && (
        <section className="rounded-lg border border-border-subtle bg-card p-5 text-muted-foreground shadow-1">
          Aucun contrat d’accueil pour {child.firstName}.
        </section>
      )}
      {canEdit && list.every((c) => c.state === "TERMINE") && (
        <Button asChild className="self-start">
          <Link to={`/enfants/${child.id}/contrat/nouveau`}>
            <Plus aria-hidden />
            Nouveau contrat
          </Link>
        </Button>
      )}
      {list.map((c) => <ContractCard key={c.id} contract={c} canEdit={canEdit} />)}
    </div>
  );
}

function ContractCard({ contract, canEdit }: { contract: Contract; canEdit: boolean }) {
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState<{ kind: "sign"; version: ContractVersion } | { kind: "amend"; version?: ContractVersion } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const refresh = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: contractKeys.forChild(contract.childId) }),
      queryClient.invalidateQueries({ queryKey: childKeys.record(contract.childId) }),
      queryClient.invalidateQueries({ queryKey: ["capacity"] }),
    ]);
  const initial = contract.versions[0];
  const draft = contract.versions.find((v) => v.status === "BROUILLON");
  const inForce = contract.versions.find((v) => v.inForce);
  const lastSigned = [...contract.versions].reverse().find((v) => v.status === "SIGNE");

  const run = async (action: () => Promise<unknown>) => {
    setError(null);
    try {
      await action();
      await refresh();
    } catch (e) {
      setError(errorMessage(e));
    }
  };
  const pdf = (v: ContractVersion) => run(() => contractsApi.downloadPdf(contract, v.number));
  const remove = (v: ContractVersion) => {
    const message = v.number === 0 ? `Supprimer le brouillon du contrat n° ${contract.number} ?` : `Supprimer le brouillon de l’avenant ${v.number} ?`;
    if (window.confirm(message)) void run(() => contractsApi.deleteDraft(contract.id, v.number));
  };

  return (
    <section aria-label={`Contrat n° ${contract.number}`} className="flex flex-col gap-4 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
          <h3 className="m-0 flex flex-wrap items-center gap-2 text-title-3">
            Contrat d’accueil n° {contract.number}
            <TonePill tone={stateTones[contract.state]}>{stateLabels[contract.state]}</TonePill>
          </h3>
          <p className="m-0 text-muted-foreground tabular-nums">
            Du {isoToDateFr(contract.startDate)} au {isoToDateFr(contract.endDate)} · préavis {contract.noticeMonths} mois
            {contract.adaptation.length > 0 && ` · adaptation du ${isoToDateFr(contract.adaptation[0].date)} au ${isoToDateFr(contract.adaptation[contract.adaptation.length - 1].date)}`}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" onClick={() => void pdf(inForce ?? initial)}>
            <FileDown aria-hidden />
            {initial.status === "SIGNE" ? "Télécharger le contrat en vigueur" : "Télécharger le PDF à signer"}
          </Button>
          {canEdit && initial.status === "BROUILLON" && (
            <>
              <Button variant="outline" asChild>
                <Link to={`/contrats/${contract.id}/modifier`}>
                  <Pencil aria-hidden />
                  Modifier le brouillon
                </Link>
              </Button>
              <Button onClick={() => setDialog({ kind: "sign", version: initial })}>
                <PenLine aria-hidden />
                Enregistrer la signature
              </Button>
            </>
          )}
          {canEdit && initial.status === "SIGNE" && !draft && contract.state !== "TERMINE" && (
            <Button onClick={() => setDialog({ kind: "amend" })}>
              <FilePlus2 aria-hidden />
              Nouvel avenant
            </Button>
          )}
        </div>
      </div>

      {initial.status === "BROUILLON" && (
        <p className="m-0 flex items-center gap-2 rounded-md bg-warning-100 px-4 py-2 text-warning-700">
          <TriangleAlert aria-hidden className="size-4" />
          Brouillon : imprimez le PDF en deux exemplaires, faites-le signer, puis enregistrez la signature.
        </p>
      )}

      <div className="overflow-x-auto">
        <table className="w-full border-collapse text-left">
          <caption className="sr-only">Versions du contrat n° {contract.number}</caption>
          <thead>
            <tr className="border-b border-border-subtle text-label text-muted-foreground">
              <th scope="col" className="py-2 pr-3">Version</th>
              <th scope="col" className="py-2 pr-3">Date d’effet</th>
              <th scope="col" className="py-2 pr-3">Planning</th>
              <th scope="col" className="py-2 pr-3 text-right">Heures / sem.</th>
              <th scope="col" className="py-2 pr-3 text-right">Forfait</th>
              <th scope="col" className="py-2 pr-3">Statut</th>
              <th scope="col" className="py-2"><span className="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            {contract.versions.map((v) => (
              <tr key={v.number} className={cn("border-b border-border-subtle last:border-0", v.inForce && "bg-lagon-50")}>
                <th scope="row" className="py-2 pr-3 font-semibold">{v.number === 0 ? "Contrat initial" : `Avenant ${v.number}`}</th>
                <td className="py-2 pr-3 tabular-nums">{isoToDateFr(v.effectiveFrom)}</td>
                <td className="py-2 pr-3">
                  {v.planningMode === "FIXE" ? v.days.map((d) => `${DAY_NAMES[d.dayOfWeek - 1].slice(0, 3)}. ${hhmm(d.arrivesAt)}–${hhmm(d.leavesAt)}`).join(" · ") : `Variable, ${formatMinutes(v.monthlyMinutes)} par mois`}
                  {v.reason && <span className="block text-small text-muted-foreground">{v.reason}</span>}
                </td>
                <td className="py-2 pr-3 text-right tabular-nums">{formatMinutes(v.weeklyMinutes)}</td>
                <td className="py-2 pr-3 text-right tabular-nums">
                  {v.billingMode === "REEL" && v.planningMode === "FIXE" ? `${formatXpf(v.hourlyRate)} / h` : formatXpf(v.monthlyFee)}
                  <span className="block text-small text-muted-foreground">
                    {v.formula ? `formule « ${v.formula} »` : v.billingMode === "REEL" && v.planningMode === "FIXE" ? "au réel, selon le mois" : "mensualisé"}
                  </span>
                </td>
                <td className="py-2 pr-3">
                  {v.status === "BROUILLON" ? <TonePill tone="neutral">Brouillon</TonePill>
                    : v.replaced ? <TonePill tone="neutral">Remplacé</TonePill>
                    : <TonePill tone="success">{`Signé le ${isoToDateFr(v.signedOn!)}${v.inForce ? " · en vigueur" : ""}`}</TonePill>}
                </td>
                <td className="py-2 text-right whitespace-nowrap">
                  <Button variant="ghost" size="sm" onClick={() => void pdf(v)} aria-label={`PDF de la version ${v.number}`}>
                    <FileDown aria-hidden />
                    PDF
                  </Button>
                  {canEdit && v.status === "BROUILLON" && v.number > 0 && (
                    <>
                      <Button variant="ghost" size="sm" onClick={() => setDialog({ kind: "amend", version: v })}>Modifier</Button>
                      <Button variant="ghost" size="sm" onClick={() => setDialog({ kind: "sign", version: v })}>Signature</Button>
                    </>
                  )}
                  {canEdit && v.status === "BROUILLON" && (
                    <Button variant="ghost" size="sm" className="text-danger-700" onClick={() => remove(v)} aria-label={`Supprimer le brouillon ${v.number === 0 ? "du contrat" : `de l’avenant ${v.number}`}`}>
                      <Trash2 aria-hidden />
                    </Button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="m-0 text-small text-muted-foreground">
        Montants avant aides. Chaque version s’applique jusqu’à la date d’effet de la suivante ; une version signée ne se modifie plus.
      </p>
      {error && <Alert>{error}</Alert>}

      {dialog?.kind === "sign" && (
        <SignDialog contract={contract} version={dialog.version} onClose={() => setDialog(null)} onDone={refresh} />
      )}
      {dialog?.kind === "amend" && lastSigned && (
        <AmendmentDialog contract={contract} base={lastSigned} draft={dialog.version} onClose={() => setDialog(null)} onDone={refresh} />
      )}
    </section>
  );
}

function SignDialog({ contract, version, onClose, onDone }: {
  contract: Contract;
  version: ContractVersion;
  onClose: () => void;
  onDone: () => Promise<unknown>;
}) {
  const [date, setDate] = useState(isoToDateFr(todayIso()));
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const submit = async () => {
    const signedOn = parseDateFr(date);
    if (!signedOn) {
      setError("Date attendue au format JJ/MM/AAAA.");
      return;
    }
    setPending(true);
    try {
      await contractsApi.sign(contract.id, version.number, signedOn, file);
      await onDone();
      onClose();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setPending(false);
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Enregistrer la signature</DialogTitle>
          <DialogDescription>
            {version.number === 0 ? `Contrat n° ${contract.number}` : `Avenant ${version.number} au contrat n° ${contract.number}`}, signé sur papier.
            Une fois signée, cette version ne pourra plus être modifiée.
          </DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-4">
          <FormField label="Date de signature">
            <DateInput placeholder="JJ/MM/AAAA" inputMode="numeric" value={date} onChange={(e) => setDate(e.target.value)} />
          </FormField>
          <FormField label="Exemplaire signé numérisé" hint="Facultatif : PDF, JPEG ou PNG, 10 Mo maximum. Joint au dossier de l’enfant, chiffré.">
            <Input type="file" accept="application/pdf,image/jpeg,image/png" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
          </FormField>
          {error && <Alert>{error}</Alert>}
          <div className="flex gap-3">
            <Button onClick={() => void submit()} disabled={pending}>{pending ? "Enregistrement…" : "Enregistrer la signature"}</Button>
            <Button variant="ghost" onClick={onClose}>Annuler</Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

const REASONS = ["Changement d’horaires de travail", "Organisation familiale", "Demande de la structure", "Autre motif"];

/** Avenant : nouveaux horaires à partir d'une date d'effet ; le forfait est recalculé, les mois précédents ne changent pas. */
function AmendmentDialog({ contract, base, draft, onClose, onDone }: {
  contract: Contract;
  base: ContractVersion;
  draft?: ContractVersion;
  onClose: () => void;
  onDone: () => Promise<unknown>;
}) {
  const structure = useStructure();
  const openDays = (structure.data?.openingHours ?? []).map((o) => o.dayOfWeek).sort();
  const [effective, setEffective] = useState(draft ? isoToDateFr(draft.effectiveFrom) : "");
  const [reason, setReason] = useState(draft?.reason && REASONS.includes(draft.reason) ? draft.reason : draft?.reason ? "Autre motif" : REASONS[0]);
  const [otherReason, setOtherReason] = useState(draft?.reason && !REASONS.includes(draft.reason) ? draft.reason : "");
  const [days, setDays] = useState(() => initialDays(openDays.length ? openDays : [1, 2, 3, 4, 5], (draft ?? base).days));
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const effectiveIso = parseDateFr(effective);
  const inputs = toDayInputs(days);
  const valid = inputs.length > 0 && inputs.every((d) => span(d.arrivesAt, d.leavesAt) > 0);
  const [billingMode, setBillingMode] = useState<BillingMode>((draft ?? base).billingMode);
  // La formule de la version en vigueur est reprise ; l'avenant peut en changer (passage aux matins seulement…).
  const [formula, setFormula] = useState<string | null>((draft ?? base).formula);
  const planning = { mode: "FIXE" as const, days: inputs, monthlyReservedMinutes: null, planningNoticeDays: null, familyQuotient: base.familyQuotient, billingMode, formula };
  const quote = useQuery({
    queryKey: ["contract-quote", "avenant", effectiveIso, JSON.stringify(inputs), billingMode, formula],
    queryFn: ({ signal }) => contractsApi.quote({ effectiveFrom: effectiveIso!, planning }, signal),
    enabled: Boolean(effectiveIso) && valid,
    placeholderData: (previous) => previous,
    retry: false,
  });
  const before = new Map(base.days.map((d) => [d.dayOfWeek, d]));
  const after = weeklyMinutes(days);
  const reasonText = reason === "Autre motif" ? otherReason.trim() : reason;

  const submit = async () => {
    setError(null);
    if (!effectiveIso) return setError("Indiquez la date d’effet au format JJ/MM/AAAA.");
    if (!valid) return setError("Vérifiez les horaires : au moins un jour, chaque départ après l’arrivée.");
    if (!reasonText) return setError("Précisez le motif de l’avenant.");
    setPending(true);
    try {
      const body = { effectiveFrom: effectiveIso, reason: reasonText, planning };
      if (draft) await contractsApi.updateAmendment(contract.id, draft.number, body);
      else await contractsApi.amend(contract.id, body);
      await onDone();
      onClose();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setPending(false);
    }
  };

  const q = quote.data;
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>{draft ? `Avenant ${draft.number}` : "Nouvel avenant"} · changement d’horaires</DialogTitle>
          <DialogDescription>Modifiez les horaires concernés. Le forfait est recalculé à partir de la date d’effet ; les mois précédents ne changent pas.</DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-4">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <FormField label="Date d’effet" hint={`Après le ${isoToDateFr(base.effectiveFrom)}, avant le ${isoToDateFr(contract.endDate)}.`}>
              <DateInput placeholder="JJ/MM/AAAA" inputMode="numeric" value={effective} onChange={(e) => setEffective(e.target.value)} />
            </FormField>
            <FormField label="Motif">
              <select className={selectClass} value={reason} onChange={(e) => setReason(e.target.value)}>
                {REASONS.map((r) => <option key={r} value={r}>{r}</option>)}
              </select>
            </FormField>
            {reason === "Autre motif" && (
              <FormField label="Précisez le motif" className="sm:col-span-2">
                <Input value={otherReason} maxLength={200} onChange={(e) => setOtherReason(e.target.value)} />
              </FormField>
            )}
          </div>
          <PlanningEditor days={days} onChange={setDays} opening={structure.data?.openingHours ?? []} />
          <table className="w-full border-collapse text-left text-small">
            <caption className="mb-1 text-left text-label">Horaires avant et après l’avenant</caption>
            <thead>
              <tr className="border-b border-border-subtle text-muted-foreground">
                <th scope="col" className="py-1">Jour</th>
                <th scope="col" className="py-1">Horaires actuels</th>
                <th scope="col" className="py-1">Nouveaux horaires</th>
                <th scope="col" className="py-1 text-right">Écart</th>
              </tr>
            </thead>
            <tbody>
              {Object.entries(days).map(([key, d]) => {
                const day = Number(key);
                const old = before.get(day);
                const oldMinutes = old ? span(hhmm(old.arrivesAt), hhmm(old.leavesAt)) : 0;
                const newMinutes = d.on ? Math.max(0, span(d.arrivesAt, d.leavesAt)) : 0;
                const changed = (old ? `${hhmm(old.arrivesAt)}-${hhmm(old.leavesAt)}` : "") !== (d.on ? `${d.arrivesAt}-${d.leavesAt}` : "");
                return (
                  <tr key={day} className={cn("border-b border-border-subtle last:border-0", changed && "bg-lagon-50")}>
                    <th scope="row" className="py-1 font-normal">{DAY_NAMES[day - 1]}</th>
                    <td className="py-1 tabular-nums">{old ? `${hhmm(old.arrivesAt)} – ${hhmm(old.leavesAt)}` : "Pas d’accueil"}</td>
                    <td className="py-1 tabular-nums">{d.on ? `${d.arrivesAt} – ${d.leavesAt}` : "Pas d’accueil"}</td>
                    <td className="py-1 text-right tabular-nums">
                      {!changed ? "Inchangé" : newMinutes === oldMinutes ? "Horaires décalés" : formatMinutesDelta(newMinutes - oldMinutes)}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {quote.error instanceof ApiError && <Alert>{quote.error.message}</Alert>}
          {quote.error instanceof ApiError && quote.error.code === "formula_unknown" && (
            <Button variant="outline" className="self-start" onClick={() => setFormula(null)}>Revenir au calcul à l’heure</Button>
          )}
          {q && <FormulaChoice value={formula} quote={q} onChange={setFormula} />}
          {q && (
            <table className="w-full border-collapse text-left">
              <caption className="mb-1 text-left text-label">Effet sur la facture</caption>
              <thead>
                <tr className="border-b border-border-subtle text-label text-muted-foreground">
                  <th scope="col" className="py-1">Élément</th>
                  <th scope="col" className="py-1 text-right">Actuel</th>
                  <th scope="col" className="py-1 text-right">Au {effectiveIso ? isoToDateFr(effectiveIso).slice(0, 5) : "…"}</th>
                </tr>
              </thead>
              <tbody className="tabular-nums">
                <tr><th scope="row" className="py-1 font-normal">Heures par semaine</th><td className="py-1 text-right">{formatMinutes(base.weeklyMinutes)}</td><td className="py-1 text-right">{formatMinutes(after)}</td></tr>
                <tr><th scope="row" className="py-1 font-normal">Tarif horaire</th><td className="py-1 text-right">{formatXpf(base.hourlyRate)}</td><td className="py-1 text-right">{formatXpf(q.hourlyRate)}</td></tr>
                <tr className="font-semibold"><th scope="row" className="py-1">Forfait mensuel</th><td className="py-1 text-right">{formatXpf(base.monthlyFee)}</td><td className="py-1 text-right">{formatXpf(q.monthlyFee)}</td></tr>
              </tbody>
            </table>
          )}
          {q && <p className="m-0">Écart pour la famille : <strong className="tabular-nums">{q.monthlyFee - base.monthlyFee >= 0 ? "+ " : "− "}{formatXpf(Math.abs(q.monthlyFee - base.monthlyFee))}</strong> par mois, avant aides.</p>}
          {q && !formula && <BillingModeChoice value={billingMode} quote={q} onChange={setBillingMode} />}
          {q && q.hourlyRate !== base.hourlyRate && (
            <p role="status" className="m-0 flex items-center gap-2 rounded-md bg-warning-100 px-4 py-2 text-warning-700">
              <TriangleAlert aria-hidden className="size-4 shrink-0" />
              Le tarif horaire passe de {formatXpf(base.hourlyRate)} à {formatXpf(q.hourlyRate)} (changement de tranche du barème). Prévenez la famille avant la signature.
            </p>
          )}
          {q && q.problems.length > 0 && <Alert>{q.problems.join(" ")}</Alert>}
          {error && <Alert>{error}</Alert>}
          <div className="flex gap-3">
            <Button onClick={() => void submit()} disabled={pending}>{pending ? "Enregistrement…" : draft ? "Enregistrer l’avenant" : "Créer l’avenant (brouillon)"}</Button>
            <Button variant="ghost" onClick={onClose}>Annuler</Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
