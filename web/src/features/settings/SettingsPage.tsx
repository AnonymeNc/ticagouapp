import { useState } from "react";
import { useSearchParams } from "react-router";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Copy, Pencil, Plus, Trash2 } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { useModules, type LicenseModule } from "@/features/license/useModules";
import { useStructure } from "@/features/structure/useStructure";
import { bandLabel, type TariffCriterion } from "@/features/contracts/api";
import { enrollmentApi, enrollmentKeys, type Criterion } from "@/features/enrollment/api";
import { TonePill } from "@/features/enrollment/components";
import { todayIso } from "@/lib/age";
import { errorMessage } from "@/lib/api";
import { isoToDateFr, parseDateFr } from "@/lib/datetime";
import { formatXpf } from "@/lib/format";
import { isoToField, selectClass } from "@/lib/formFields";
import { cn } from "@/lib/utils";
import { criterionLabels, settingsApi, type Band, type TariffSchedule } from "./api";
import { BackupsTab } from "./BackupsTab";
import { BillingRulesTab } from "./BillingRulesTab";
import { DevicesTab } from "./DevicesTab";
import { FundersTab } from "./FundersTab";
import { LicenseSection } from "./LicenseSection";
import { MealsTab } from "./MealsTab";
import { RatioRulesTab } from "./RatioRulesTab";
import { RightsMatrix } from "./RightsMatrix";
import { SectionsTab } from "./SectionsTab";
import { StaffSettingsSection } from "./StaffSettingsSection";
import { StructureTab } from "./StructureTab";
import { UsersTab } from "./UsersTab";
import { PortalTab } from "@/features/portal/PortalTab";
import { AccountingSettingsTab } from "@/features/accounting/AccountingSettingsTab";
import { DataImportTab } from "@/features/dataimport/DataImportTab";
import { OnlineEnrollmentSettings } from "@/features/enrollment/OnlineEnrollmentSettings";

type Tab = "structure" | "sections" | "encadrement" | "criteres" | "preinscription" | "repas" | "bareme" | "financeurs" | "utilisateurs"
  | "comptabilite" | "sauvegardes" | "reprise" | "appareils" | "portail";

/**
 * Rubriques de la maquette, par groupe ; la comptabilité ne voit que « Barème et financeurs ». Une rubrique d'un module
 * que la formule ne comprend pas n'apparaît pas (ADR 0015).
 */
const GROUPS: { label: string; items: { key: Tab; label: string; meta: string; accounting?: boolean; module?: LicenseModule }[] }[] = [
  { label: "Établissement", items: [
    { key: "structure", label: "Structure", meta: "Identité, agrément, banque" },
    { key: "sections", label: "Sections et tranches d’âge", meta: "Places, passages" },
    { key: "encadrement", label: "Règles d’encadrement", meta: "Ratios de l’agrément" },
    { key: "criteres", label: "Critères d’inscription", meta: "Priorité des demandes", module: "INSCRIPTIONS" },
    { key: "preinscription", label: "Pré-inscription en ligne", meta: "Formulaire public, questions", module: "INSCRIPTIONS" },
    { key: "repas", label: "Repas et régimes", meta: "Heures, régimes", module: "REPAS" },
  ] },
  { label: "Facturation", items: [
    { key: "bareme", label: "Barème et facturation", meta: "Tarifs, règles, TGC", accounting: true, module: "INSCRIPTIONS" },
    { key: "financeurs", label: "Financeurs et aides", meta: "Aides, subventions", accounting: true, module: "FACTURATION" },
    { key: "comptabilite", label: "Comptabilité", meta: "Comptes, export CSV et FEC", accounting: true, module: "COMPTABILITE" },
  ] },
  { label: "Accès", items: [{ key: "utilisateurs", label: "Utilisateurs et rôles", meta: "Comptes, droits, personnel" }] },
  { label: "Système", items: [
    { key: "sauvegardes", label: "Sauvegardes", meta: "Chiffrées, chaque nuit" },
    { key: "reprise", label: "Reprise des données", meta: "Depuis Excel, enfants et familles" },
    { key: "appareils", label: "Tablettes et abonnement", meta: "Appairage, abonnement" },
    { key: "portail", label: "Portail parents", meta: "Partage avec les familles", module: "PORTAIL_PARENTS" },
  ] },
];

/**
 * Paramètres (maquette Paramètres) : navigation par groupes, comme la maquette. Seule la direction
 * modifie ; la comptabilité règle le barème et les financeurs.
 */
export function SettingsPage() {
  const user = useCurrentUser();
  const isDirection = user.role === "DIRECTION";
  const modules = useModules();
  const groups = GROUPS.map((g) => ({ ...g, items: g.items.filter((i) => (isDirection || i.accounting) && (!i.module || modules.has(i.module))) }))
    .filter((g) => g.items.length > 0);
  // « ?onglet=appareils » : lien du bandeau d'abonnement.
  const [params] = useSearchParams();
  const requested = groups.flatMap((g) => g.items).find((i) => i.key === params.get("onglet"))?.key;
  const [chosen, setTab] = useState<Tab>(requested ?? (isDirection ? "structure" : "bareme"));
  // Rubrique d'un module absent (lien ancien, modules reçus après coup) : on revient à la première disponible.
  const available = groups.flatMap((g) => g.items);
  const tab = available.some((i) => i.key === chosen) ? chosen : available[0]?.key;
  const structure = useStructure();
  return (
    <>
      <div>
        <h1 className="m-0 font-display text-display">Paramètres</h1>
        <p className="mt-1 mb-0 text-muted-foreground">
          Réglages de {structure.data?.name ?? "la structure"}. Seule la direction peut les modifier ; chaque changement est journalisé.
        </p>
      </div>
      <div className="grid gap-6 lg:grid-cols-[240px_minmax(0,1fr)]">
        <nav aria-label="Rubriques des paramètres" className="flex flex-col gap-4 self-start">
          {groups.map((g) => (
            <div key={g.label} className="flex flex-col gap-1">
              <p className="m-0 px-3 text-caption font-semibold tracking-wide text-muted-foreground uppercase">{g.label}</p>
              {g.items.map((i) => (
                <button key={i.key} type="button" aria-current={tab === i.key ? "page" : undefined} onClick={() => setTab(i.key)}
                  className={cn("flex flex-col rounded-md px-3 py-2 text-left", tab === i.key ? "bg-lagon-50 text-lagon-700" : "hover:bg-muted")}>
                  <span className="text-label">{i.label}</span>
                  <span className="text-small text-muted-foreground">{i.meta}</span>
                </button>
              ))}
            </div>
          ))}
        </nav>
        <div className="flex min-w-0 flex-col gap-4">
          {tab === "structure" && <StructureTab />}
          {tab === "sections" && <SectionsTab />}
          {tab === "encadrement" && <RatioRulesTab />}
          {tab === "bareme" && <><Tariffs />{modules.has("FACTURATION") && <BillingRulesTab />}</>}
          {tab === "financeurs" && <FundersTab />}
          {tab === "comptabilite" && <AccountingSettingsTab />}
          {tab === "repas" && <MealsTab />}
          {tab === "criteres" && <Criteria />}
          {tab === "preinscription" && <OnlineEnrollmentSettings />}
          {tab === "utilisateurs" && <><UsersTab /><RightsMatrix />{modules.has("PERSONNEL") && <StaffSettingsSection />}</>}
          {tab === "sauvegardes" && <BackupsTab />}
          {tab === "reprise" && <DataImportTab />}
          {tab === "appareils" && <><DevicesTab /><LicenseSection /></>}
          {tab === "portail" && <PortalTab />}
        </div>
      </div>
    </>
  );
}

// --- Barèmes ----------------------------------------------------------------------------------------

const status = (s: TariffSchedule, today: string) =>
  s.validFrom > today ? { tone: "info" as const, label: "À venir" }
    : s.validTo && s.validTo < today ? { tone: "neutral" as const, label: "Expiré" }
    : { tone: "success" as const, label: "En vigueur" };

function Tariffs() {
  const queryClient = useQueryClient();
  const tariffs = useQuery({ queryKey: ["tariff-schedules"], queryFn: ({ signal }) => settingsApi.tariffs(signal) });
  const [editing, setEditing] = useState<TariffSchedule | "new" | null>(null);
  const [duplicating, setDuplicating] = useState<TariffSchedule | null>(null);
  const [error, setError] = useState<string | null>(null);
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["tariff-schedules"] });
  const today = todayIso();

  const remove = async (s: TariffSchedule) => {
    if (!window.confirm(`Supprimer le barème « ${s.label} » ?`)) return;
    try {
      await settingsApi.deleteTariff(s.id);
      await refresh();
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="m-0 max-w-3xl text-muted-foreground">
          Le barème s’applique aux contrats et avenants qui prennent effet pendant sa période de validité. Les contrats signés gardent le tarif
          calculé à leur signature : modifier un barème ne les change pas.
        </p>
        <Button onClick={() => setEditing("new")}>
          <Plus aria-hidden />
          Nouveau barème
        </Button>
      </div>
      {error && <Alert>{error}</Alert>}
      {tariffs.isError && <p className="text-danger-700">{errorMessage(tariffs.error)}</p>}
      {tariffs.data?.length === 0 && (
        <p className="m-0 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
          Aucun barème : ajoutez-en un avant d’établir le premier contrat.
        </p>
      )}
      {tariffs.data?.map((s) => {
        const st = status(s, today);
        return (
          <section key={s.id} aria-label={s.label} className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <h2 className="m-0 flex items-center gap-2 text-title-3">{s.label} <TonePill tone={st.tone}>{st.label}</TonePill></h2>
                <p className="m-0 text-muted-foreground tabular-nums">
                  {criterionLabels[s.criterion]} · du {isoToDateFr(s.validFrom)}{s.validTo ? ` au ${isoToDateFr(s.validTo)}` : ", sans date de fin"}
                  {" "}· {s.weeksPerYear} semaines d’accueil par an · frais d’inscription {formatXpf(s.registrationFee)}
                </p>
              </div>
              <div className="flex flex-wrap gap-2">
                <Button variant="outline" size="sm" onClick={() => setDuplicating(s)}><Copy aria-hidden />Dupliquer</Button>
                <Button variant="outline" size="sm" onClick={() => setEditing(s)}><Pencil aria-hidden />Modifier</Button>
                {!s.used && (
                  <Button variant="ghost" size="sm" className="text-danger-700" onClick={() => void remove(s)} aria-label={`Supprimer le barème ${s.label}`}>
                    <Trash2 aria-hidden />
                  </Button>
                )}
              </div>
            </div>
            <table className="w-full border-collapse text-left">
              <caption className="sr-only">Tranches du barème {s.label}</caption>
              <thead>
                <tr className="border-b border-border-subtle text-label text-muted-foreground">
                  <th scope="col" className="py-2">{s.criterion === "HEURES_SEMAINE" ? "Volume hebdomadaire" : "Quotient familial mensuel"}</th>
                  <th scope="col" className="py-2 text-right">Tarif horaire</th>
                  <th scope="col" className="py-2 text-right">Exemple : 160 h par mois</th>
                </tr>
              </thead>
              <tbody className="tabular-nums">
                {s.bands.map((b) => (
                  <tr key={b.lowerBound} className="border-b border-border-subtle last:border-0">
                    <td className="py-2">{bandLabel(s.criterion, s.bands, b.lowerBound)}</td>
                    <td className="py-2 text-right">{formatXpf(b.hourlyRate)}</td>
                    <td className="py-2 text-right text-muted-foreground">{formatXpf(b.hourlyRate * 160)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        );
      })}
      {editing && <TariffDialog tariff={editing === "new" ? null : editing} onClose={() => setEditing(null)} onDone={refresh} />}
      {duplicating && <DuplicateDialog tariff={duplicating} onClose={() => setDuplicating(null)} onDone={refresh} />}
    </>
  );
}

type BandRow = { lowerBound: string; hourlyRate: string };

/** Borne saisie : heures par semaine (« 35,25 » = 35 h 15) ou francs de quotient familial. */
const boundToField = (criterion: TariffCriterion, v: number) =>
  criterion === "HEURES_SEMAINE" ? String(v / 60).replace(".", ",") : String(v);
const fieldToBound = (criterion: TariffCriterion, v: string) => {
  const n = Number(v.replace(",", ".").replace(/\s/g, ""));
  return criterion === "HEURES_SEMAINE" ? Math.round(n * 60) : Math.round(n);
};

function TariffDialog({ tariff, onClose, onDone }: { tariff: TariffSchedule | null; onClose: () => void; onDone: () => Promise<unknown> }) {
  const [label, setLabel] = useState(tariff?.label ?? `Barème ${new Date().getFullYear() + 1}`);
  const [criterion, setCriterion] = useState<TariffCriterion>(tariff?.criterion ?? "HEURES_SEMAINE");
  const [weeks, setWeeks] = useState(String(tariff?.weeksPerYear ?? 47));
  const [fee, setFee] = useState(String(tariff?.registrationFee ?? 0));
  const [from, setFrom] = useState(isoToField(tariff?.validFrom));
  const [to, setTo] = useState(isoToField(tariff?.validTo));
  const [bands, setBands] = useState<BandRow[]>(
    tariff ? tariff.bands.map((b) => ({ lowerBound: boundToField(tariff.criterion, b.lowerBound), hourlyRate: String(b.hourlyRate) }))
      : [{ lowerBound: "0", hourlyRate: "" }],
  );
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    setError(null);
    const validFrom = parseDateFr(from);
    const validTo = to.trim() ? parseDateFr(to) : null;
    if (!validFrom || (to.trim() && !validTo)) return setError("Dates attendues au format JJ/MM/AAAA.");
    const parsed: Band[] = bands.map((b) => ({ lowerBound: fieldToBound(criterion, b.lowerBound), hourlyRate: Number(b.hourlyRate) }));
    if (parsed.some((b) => !Number.isFinite(b.lowerBound) || !(b.hourlyRate > 0))) return setError("Chaque tranche a une borne et un tarif horaire positif.");
    try {
      const body = { label: label.trim(), criterion, weeksPerYear: Number(weeks), registrationFee: Number(fee) || 0, validFrom, validTo, bands: parsed };
      if (tariff) await settingsApi.updateTariff(tariff.id, body);
      else await settingsApi.createTariff(body);
      await onDone();
      onClose();
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{tariff ? `Modifier « ${tariff.label} »` : "Nouveau barème"}</DialogTitle>
          <DialogDescription>Le tarif horaire dépend de la tranche ; le franc CFP n’a pas de décimales.</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <FormField label="Libellé"><Input value={label} maxLength={80} onChange={(e) => setLabel(e.target.value)} /></FormField>
          <FormField label="Tranches">
            <select className={selectClass} value={criterion} onChange={(e) => setCriterion(e.target.value as TariffCriterion)}>
              {(Object.keys(criterionLabels) as TariffCriterion[]).map((c) => <option key={c} value={c}>{criterionLabels[c]}</option>)}
            </select>
          </FormField>
          <FormField label="Valide à partir du"><Input placeholder="JJ/MM/AAAA" value={from} onChange={(e) => setFrom(e.target.value)} /></FormField>
          <FormField label="Jusqu’au (facultatif)"><Input placeholder="JJ/MM/AAAA" value={to} onChange={(e) => setTo(e.target.value)} /></FormField>
          <FormField label="Semaines d’accueil par an" hint="Sert à mensualiser le forfait : heures par semaine × semaines ÷ 12.">
            <Input inputMode="numeric" value={weeks} onChange={(e) => setWeeks(e.target.value)} />
          </FormField>
          <FormField label="Frais d’inscription (F)"><Input inputMode="numeric" value={fee} onChange={(e) => setFee(e.target.value)} /></FormField>
        </div>
        <table className="w-full border-collapse text-left">
          <caption className="mb-1 text-left text-label">Tranches</caption>
          <thead>
            <tr className="text-label text-muted-foreground">
              <th scope="col" className="py-1">{criterion === "HEURES_SEMAINE" ? "À partir de (heures par semaine)" : "À partir d’un quotient de (F)"}</th>
              <th scope="col" className="py-1">Tarif horaire (F)</th>
              <th scope="col"><span className="sr-only">Retirer</span></th>
            </tr>
          </thead>
          <tbody>
            {bands.map((b, i) => (
              <tr key={i}>
                <td className="py-1 pr-2">
                  <Input aria-label={`Borne de la tranche ${i + 1}`} inputMode="decimal" value={b.lowerBound} disabled={i === 0}
                    onChange={(e) => setBands(bands.map((x, j) => (j === i ? { ...x, lowerBound: e.target.value } : x)))} />
                </td>
                <td className="py-1 pr-2">
                  <Input aria-label={`Tarif horaire de la tranche ${i + 1}`} inputMode="numeric" value={b.hourlyRate}
                    onChange={(e) => setBands(bands.map((x, j) => (j === i ? { ...x, hourlyRate: e.target.value } : x)))} />
                </td>
                <td className="py-1 text-right">
                  {i > 0 && (
                    <Button variant="ghost" size="sm" aria-label={`Retirer la tranche ${i + 1}`} onClick={() => setBands(bands.filter((_, j) => j !== i))}>
                      <Trash2 aria-hidden />
                    </Button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <Button variant="outline" size="sm" className="self-start" onClick={() => setBands([...bands, { lowerBound: "", hourlyRate: "" }])}>
          <Plus aria-hidden />
          Ajouter une tranche
        </Button>
        {criterion === "HEURES_SEMAINE" && <p className="m-0 text-small text-muted-foreground">Exemple : « 35,25 » pour une tranche qui commence à 35 h 15.</p>}
        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button onClick={() => void submit()}>Enregistrer</Button>
          <Button variant="ghost" onClick={onClose}>Annuler</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function DuplicateDialog({ tariff, onClose, onDone }: { tariff: TariffSchedule; onClose: () => void; onDone: () => Promise<unknown> }) {
  const nextYear = Number(tariff.validFrom.slice(0, 4)) + 1;
  const [label, setLabel] = useState(`Barème ${nextYear}`);
  const [from, setFrom] = useState(`01/01/${nextYear}`);
  const [error, setError] = useState<string | null>(null);
  const submit = async () => {
    const validFrom = parseDateFr(from);
    if (!validFrom) return setError("Date attendue au format JJ/MM/AAAA.");
    try {
      await settingsApi.duplicateTariff(tariff.id, { label: label.trim(), validFrom });
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
          <DialogTitle>Dupliquer « {tariff.label} »</DialogTitle>
          <DialogDescription>Le nouveau barème reprend les tranches ; « {tariff.label} » se termine la veille de son début.</DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-4">
          <FormField label="Libellé"><Input value={label} onChange={(e) => setLabel(e.target.value)} /></FormField>
          <FormField label="Valide à partir du"><Input placeholder="JJ/MM/AAAA" value={from} onChange={(e) => setFrom(e.target.value)} /></FormField>
          {error && <Alert>{error}</Alert>}
          <div className="flex gap-3">
            <Button onClick={() => void submit()}>Dupliquer</Button>
            <Button variant="ghost" onClick={onClose}>Annuler</Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

// --- Critères d'inscription ------------------------------------------------------------------------

function Criteria() {
  const queryClient = useQueryClient();
  const criteria = useQuery({ queryKey: enrollmentKeys.criteria, queryFn: ({ signal }) => enrollmentApi.criteria(signal) });
  const [editing, setEditing] = useState<Criterion | "new" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const refresh = () => queryClient.invalidateQueries({ queryKey: enrollmentKeys.criteria });
  const today = todayIso();
  const remove = async (c: Criterion) => {
    if (!window.confirm(`Supprimer le critère « ${c.label} » ? Il disparaîtra des demandes qui l’avaient coché.`)) return;
    try {
      await settingsApi.deleteCriterion(c.id);
      await refresh();
    } catch (e) {
      setError(errorMessage(e));
    }
  };
  return (
    <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="m-0 max-w-3xl text-muted-foreground">
          Les points des critères cochés forment le score d’une demande et la classent parmi les demandes de la même section et du même mois d’entrée.
          Pour changer un barème de points sans réécrire l’historique, fixez une date de fin et créez le critère suivant.
        </p>
        <Button onClick={() => setEditing("new")}><Plus aria-hidden />Ajouter un critère</Button>
      </div>
      {error && <Alert>{error}</Alert>}
      <table className="w-full border-collapse text-left">
        <caption className="sr-only">Critères de priorité</caption>
        <thead>
          <tr className="border-b border-border-subtle text-label text-muted-foreground">
            <th scope="col" className="py-2">Critère</th>
            <th scope="col" className="py-2 text-right">Points</th>
            <th scope="col" className="py-2">Validité</th>
            <th scope="col" className="py-2"><span className="sr-only">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          {criteria.data?.map((c) => (
            <tr key={c.id} className={cn("border-b border-border-subtle last:border-0", c.validTo && c.validTo < today && "text-muted-foreground")}>
              <td className="py-2">{c.label}</td>
              <td className="py-2 text-right tabular-nums">+{c.points}</td>
              <td className="py-2 tabular-nums">depuis le {isoToDateFr(c.validFrom)}{c.validTo && `, jusqu’au ${isoToDateFr(c.validTo)}`}</td>
              <td className="py-2 text-right whitespace-nowrap">
                <Button variant="ghost" size="sm" onClick={() => setEditing(c)}>Modifier</Button>
                <Button variant="ghost" size="sm" className="text-danger-700" onClick={() => void remove(c)} aria-label={`Supprimer le critère ${c.label}`}>
                  <Trash2 aria-hidden />
                </Button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {editing && (
        <CriterionDialog
          criterion={editing === "new" ? null : editing}
          nextOrder={criteria.data?.length ?? 0}
          onClose={() => setEditing(null)}
          onDone={refresh}
        />
      )}
    </section>
  );
}

function CriterionDialog({ criterion, nextOrder, onClose, onDone }: {
  criterion: Criterion | null;
  nextOrder: number;
  onClose: () => void;
  onDone: () => Promise<unknown>;
}) {
  const [label, setLabel] = useState(criterion?.label ?? "");
  const [points, setPoints] = useState(String(criterion?.points ?? 1));
  const [from, setFrom] = useState(isoToField(criterion?.validFrom ?? todayIso()));
  const [to, setTo] = useState(isoToField(criterion?.validTo));
  const [error, setError] = useState<string | null>(null);
  const submit = async () => {
    const validFrom = parseDateFr(from);
    const validTo = to.trim() ? parseDateFr(to) : null;
    if (!label.trim()) return setError("Indiquez le libellé du critère.");
    if (!validFrom || (to.trim() && !validTo)) return setError("Dates attendues au format JJ/MM/AAAA.");
    try {
      const body = { label: label.trim(), points: Number(points), validFrom, validTo, displayOrder: criterion?.displayOrder ?? nextOrder };
      if (criterion) await settingsApi.updateCriterion(criterion.id, body);
      else await settingsApi.createCriterion(body);
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
          <DialogTitle>{criterion ? "Modifier le critère" : "Ajouter un critère"}</DialogTitle>
          <DialogDescription>Critère de priorité des demandes d’inscription.</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <FormField label="Libellé" className="sm:col-span-2"><Input value={label} maxLength={120} onChange={(e) => setLabel(e.target.value)} /></FormField>
          <FormField label="Points"><Input type="number" min={0} max={20} value={points} onChange={(e) => setPoints(e.target.value)} /></FormField>
          <span />
          <FormField label="Valide à partir du"><Input placeholder="JJ/MM/AAAA" value={from} onChange={(e) => setFrom(e.target.value)} /></FormField>
          <FormField label="Jusqu’au (facultatif)"><Input placeholder="JJ/MM/AAAA" value={to} onChange={(e) => setTo(e.target.value)} /></FormField>
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
