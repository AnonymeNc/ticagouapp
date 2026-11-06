import { ChildPortalTab } from "@/features/portal/ChildPortalTab";
import { useModules } from "@/features/license/useModules";
import { useRef, useState, type KeyboardEvent } from "react";
import { Link, useParams, useSearchParams } from "react-router";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Pencil, Printer } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { ContractTab } from "@/features/contracts/ContractTab";
import { useSections } from "@/features/structure/useSections";
import { ageInMonths, formatAge, todayIso } from "@/lib/age";
import { errorMessage } from "@/lib/api";
import { isoToDateFr } from "@/lib/datetime";
import { cn } from "@/lib/utils";
import { childKeys, childrenApi, type ChildRecord } from "./api";
import { ChildAvatar, CriticalHealth, SectionBadge } from "./components";
import { AbsencesTab } from "./tabs/AbsencesTab";
import { AuthorizedPersonsTab } from "./tabs/AuthorizedPersonsTab";
import { BillingTab } from "./tabs/BillingTab";
import { ConsentsTab } from "./tabs/ConsentsTab";
import { DocumentsTab } from "./tabs/DocumentsTab";
import { FamilyTab } from "./tabs/FamilyTab";
import { HealthTab } from "./tabs/HealthTab";
import { IdentityTab } from "./tabs/IdentityTab";

type TabKey = "identite" | "famille" | "personnes" | "sante" | "autorisations" | "contrat" | "presences" | "journee" | "facturation" | "documents";
const TAB_KEYS: TabKey[] = ["identite", "famille", "personnes", "sante", "autorisations", "contrat", "presences", "journee", "facturation", "documents"];

/** Rafraîchit la fiche et la liste après une modification. */
export function useRefreshChild(id: string) {
  const queryClient = useQueryClient();
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: childKeys.record(id) }),
      queryClient.invalidateQueries({ queryKey: ["children"] }),
    ]);
}

/** Fiche enfant (maquette Enfants › Fiche) : résumé, santé critique toujours visible, rubriques en onglets. */
export function ChildRecordPage() {
  const { id = "" } = useParams();
  const user = useCurrentUser();
  const canEdit = user.role === "DIRECTION";
  const modules = useModules();
  const sections = useSections();
  // « ?onglet=contrat » : ouverture directe d'un onglet (retour de l'assistant de contrat).
  const [params] = useSearchParams();
  const requested = params.get("onglet") as TabKey | null;
  const [tab, setTab] = useState<TabKey>(requested && TAB_KEYS.includes(requested) ? requested : "identite");
  // Contrats : direction et comptabilité seulement (ligne « Inscriptions et contrats » des droits par rôle).
  const canSeeContracts = user.role === "DIRECTION" || user.role === "COMPTABILITE";
  const [editingIdentity, setEditingIdentity] = useState(false);
  const record = useQuery({
    queryKey: childKeys.record(id),
    queryFn: ({ signal }) => childrenApi.record(id, signal),
    // Chaque ouverture est journalisée côté serveur : pas de rechargement automatique en arrière-plan.
    refetchOnWindowFocus: false,
  });

  if (record.isPending) return <p className="text-muted-foreground">Chargement de la fiche…</p>;
  if (record.isError) {
    return (
      <>
        <BackLink />
        <p className="text-danger-700">{errorMessage(record.error)}</p>
      </>
    );
  }

  const child = record.data;
  const section = sections.data?.find((s) => s.id === child.sectionId);
  const today = todayIso();
  const months = ageInMonths(child.birthDate, today);
  const exited = child.exitDate !== null && child.exitDate < today;

  const tabs: { key: TabKey; label: string; count?: number; alert?: boolean }[] = [
    { key: "identite", label: "Identité" },
    { key: "famille", label: "Famille" },
    { key: "personnes", label: "Personnes autorisées", count: child.authorizedPersons.length },
    { key: "sante", label: "Santé", alert: child.health.allergies.length > 0 || child.health.paiActive },
    { key: "autorisations", label: "Autorisations" },
    ...(canSeeContracts && modules.has("INSCRIPTIONS") ? [{ key: "contrat" as const, label: "Contrat" }] : []),
    ...(user.role !== "CUISINE" ? [{ key: "presences" as const, label: "Présences" }] : []),
    ...((user.role === "DIRECTION" || user.role === "EDUCATRICE") && modules.has("PORTAIL_PARENTS") ? [{ key: "journee" as const, label: "Journée et portail" }] : []),
    ...(canSeeContracts && modules.has("FACTURATION") ? [{ key: "facturation" as const, label: "Facturation" }] : []),
    { key: "documents", label: "Documents", count: child.documents.length },
  ];

  return (
    <>
      <BackLink />
      <section aria-label="Résumé de la fiche" className="flex flex-wrap items-center gap-5 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <ChildAvatar child={child} section={section} size="lg" photoVersion={child.version} />
        <div className="flex min-w-0 flex-[1_1_320px] flex-col gap-2">
          <h1 className="m-0 font-display text-title-1">
            {child.firstName} <span className="font-normal">{child.lastName}</span>
          </h1>
          <div className="flex flex-wrap gap-x-4 gap-y-2 text-label font-normal text-muted-foreground tabular-nums">
            <span>{formatAge(months)} ({months} mois)</span>
            <span>Naissance le {isoToDateFr(child.birthDate)}</span>
            {child.entryDate && <span>Entrée le {isoToDateFr(child.entryDate)}</span>}
            {child.exitDate && <span>Sortie le {isoToDateFr(child.exitDate)}</span>}
            <span>Dossier {child.fileNumber}</span>
          </div>
          <div className="flex flex-wrap gap-1.5">
            <SectionBadge section={section} />
            {exited && <span className="inline-flex h-6 items-center rounded-sm bg-muted px-2 text-caption text-muted-foreground">Sorti</span>}
          </div>
        </div>
        <div className="flex flex-wrap gap-2 print:hidden">
          <Button variant="outline" onClick={() => window.print()}>
            <Printer aria-hidden />
            Imprimer la fiche
          </Button>
          {canEdit && (
            <Button
              onClick={() => {
                setTab("identite");
                setEditingIdentity(true);
              }}
            >
              <Pencil aria-hidden />
              Modifier la fiche
            </Button>
          )}
        </div>
      </section>

      <CriticalHealth record={child} onShowHealth={() => setTab("sante")} />

      <Tabs tabs={tabs} current={tab} onChange={setTab} />

      <div role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`} className="-mt-4">
        {tab === "identite" && (
          <IdentityTab child={child} canEdit={canEdit} editing={editingIdentity} onEditingChange={setEditingIdentity} />
        )}
        {tab === "famille" && <FamilyTab child={child} canEdit={canEdit} />}
        {tab === "personnes" && <AuthorizedPersonsTab child={child} canEdit={canEdit} />}
        {tab === "sante" && <HealthTab child={child} canEdit={canEdit} />}
        {tab === "autorisations" && <ConsentsTab child={child} canEdit={canEdit} />}
        {tab === "contrat" && canSeeContracts && <ContractTab child={child} canEdit={canEdit} />}
        {tab === "presences" && <AbsencesTab child={child} canEdit={canEdit} />}
        {tab === "journee" && (
          <ChildPortalTab child={{ id: child.id, firstName: child.firstName, familyId: child.family.id, health: child.health }} canEdit={canEdit && modules.has("PORTAIL_PARENTS")} />
        )}
        {tab === "facturation" && canSeeContracts && <BillingTab child={child} canEdit={canEdit} />}
        {tab === "documents" && <DocumentsTab child={child} canEdit={canEdit} />}
      </div>
    </>
  );
}

function BackLink() {
  return (
    <Link to="/enfants" className="inline-flex items-center gap-1.5 text-label text-lagon-700 print:hidden">
      <ArrowLeft aria-hidden className="size-4" />
      Retour à la liste des enfants
    </Link>
  );
}

function Tabs<K extends string>({
  tabs,
  current,
  onChange,
}: {
  tabs: { key: K; label: string; count?: number; alert?: boolean }[];
  current: K;
  onChange: (key: K) => void;
}) {
  const refs = useRef<Record<string, HTMLButtonElement | null>>({});
  // Flèches gauche/droite entre les onglets (motif ARIA « tabs »).
  const onKeyDown = (event: KeyboardEvent, index: number) => {
    const delta = event.key === "ArrowRight" ? 1 : event.key === "ArrowLeft" ? -1 : 0;
    if (!delta) return;
    const next = tabs[(index + delta + tabs.length) % tabs.length];
    onChange(next.key);
    refs.current[next.key]?.focus();
  };
  return (
    <div role="tablist" aria-label="Rubriques de la fiche" className="flex flex-wrap gap-1 rounded-[14px] bg-muted p-1 print:hidden">
      {tabs.map((t, index) => {
        const selected = t.key === current;
        return (
          <button
            key={t.key}
            ref={(el) => {
              refs.current[t.key] = el;
            }}
            id={`tab-${t.key}`}
            type="button"
            role="tab"
            aria-selected={selected}
            aria-controls={`panel-${t.key}`}
            tabIndex={selected ? 0 : -1}
            onClick={() => onChange(t.key)}
            onKeyDown={(e) => onKeyDown(e, index)}
            className={cn(
              "inline-flex h-control items-center gap-1.5 rounded-full px-3.5 text-label",
              selected ? "bg-card text-lagon-700 shadow-1" : "text-foreground",
            )}
          >
            {t.label}
            {t.alert && (
              <>
                <span aria-hidden className="size-2 rounded-full bg-danger-600" />
                <span className="sr-only">(alertes santé)</span>
              </>
            )}
            {t.count !== undefined && t.count > 0 && <span className="text-caption text-muted-foreground tabular-nums">{t.count}</span>}
          </button>
        );
      })}
    </div>
  );
}

export type ChildTabProps = { child: ChildRecord; canEdit: boolean };
