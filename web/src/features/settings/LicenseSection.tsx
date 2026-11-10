import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Monitor } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { Button } from "@/components/ui/button";
import { TonePill, type Tone } from "@/features/enrollment/components";
import { errorMessage } from "@/lib/api";
import { isoToDateFr } from "@/lib/datetime";
import { useRubriques } from "@/features/structure/useRubriques";
import { systemApi, systemKeys, type LicenseState } from "./systemApi";

export const licenseStates: Record<LicenseState, { tone: Tone; label: string }> = {
  EVALUATION: { tone: "warning", label: "Évaluation" },
  EVALUATION_TERMINEE: { tone: "danger", label: "Évaluation terminée" },
  ACTIVE: { tone: "success", label: "Actif" },
  A_RENOUVELER: { tone: "warning", label: "À renouveler" },
  EXPIREE_GRACE: { tone: "danger", label: "Échu" },
  LECTURE_SEULE: { tone: "danger", label: "Lecture seule" },
};

/** Abonnement (géré par Ti'Cagou, ADR 0013) et version en service. */
export function LicenseSection() {
  const license = useQuery({ queryKey: systemKeys.license, queryFn: ({ signal }) => systemApi.license(signal) });
  const rubriques = useRubriques();
  const data = license.data;
  if (!data) return license.isError ? <Alert>{errorMessage(license.error)}</Alert> : null;
  const l = data.license;

  return (
    <>
      <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <h2 className="m-0 flex items-center gap-2 text-title-3">Abonnement <TonePill tone={licenseStates[data.state].tone}>{licenseStates[data.state].label}</TonePill></h2>
        {data.message && <p className="m-0 text-muted-foreground">{data.message}</p>}
        {l && (
          <dl className="m-0 grid grid-cols-[max-content_1fr] gap-x-6 gap-y-1">
            <dt className="text-muted-foreground">Formule</dt><dd className="m-0">{l.plan}</dd>
            <dt className="text-muted-foreground">Numéro</dt><dd className="m-0 tabular-nums">{l.number}</dd>
            <dt className="text-muted-foreground">Titulaire</dt><dd className="m-0">{l.holder}</dd>
            <dt className="text-muted-foreground">Compris</dt>
            <dd className="m-0">Tout Ti’Cagou : inscriptions, facturation, comptabilité, personnel, repas, portail parents</dd>
            {rubriques.labels.length > 0 && <>
              <dt className="text-muted-foreground">Masqué à votre demande</dt>
              <dd className="m-0">{rubriques.labels.join(" · ")} — contactez Ti’Cagou pour les réafficher, rien n’a été effacé.</dd>
            </>}
            <dt className="text-muted-foreground">Postes</dt>
            <dd className="m-0">{data.desktops} PC sur {data.desktopLimit} · {data.pairedTablets} tablette{data.pairedTablets > 1 ? "s" : ""} sur {l.tablets}</dd>
            <dt className="text-muted-foreground">Places</dt><dd className="m-0">{data.capacity} sur {l.places} autorisées</dd>
            <dt className="text-muted-foreground">Valide jusqu’au</dt><dd className="m-0">{isoToDateFr(l.validUntil)}</dd>
          </dl>
        )}
        {l && data.renewalFrom && <p className="m-0 text-small text-muted-foreground">
          Le renouvellement vous sera proposé à partir du {isoToDateFr(data.renewalFrom)}. Après l’échéance, Ti’Cagou fonctionne encore
          30 jours, puis passe en lecture seule : les données restent consultables, les sauvegardes continuent.
        </p>}
      </section>
      <DesktopsSection limit={data.desktopLimit} />
      <section className="flex flex-col gap-2 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <h2 className="m-0 text-title-3">Mises à jour</h2>
        <p className="m-0">Version en service : <strong className="tabular-nums">{data.version}</strong></p>
        <p className="m-0 text-small text-muted-foreground">
          Ti’Cagou applique les mises à jour hors des horaires d’ouverture, après une sauvegarde automatique : il n’y a rien à
          installer.
        </p>
      </section>
    </>
  );
}

/** Postes comptés par l'abonnement : la direction retire un poste remplacé pour libérer sa place. */
function DesktopsSection({ limit }: { limit: number }) {
  const queryClient = useQueryClient();
  const desktops = useQuery({ queryKey: systemKeys.desktops, queryFn: ({ signal }) => systemApi.desktops(signal) });
  const revoke = useMutation({
    mutationFn: systemApi.revokeDesktop,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: systemKeys.license }),
  });
  const list = desktops.data ?? [];
  return (
    <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <h2 className="m-0 flex items-center gap-2 text-title-3"><Monitor aria-hidden className="size-5" />PC du bureau</h2>
      <p className="m-0 text-small text-muted-foreground">
        L’abonnement permet {limit} PC. Un PC remplacé se retire ici ; un PC qui ne s’est pas connecté depuis 90 jours libère
        sa place tout seul.
      </p>
      {revoke.isError && <Alert>{errorMessage(revoke.error)}</Alert>}
      {list.length === 0 ? <p className="m-0 text-muted-foreground">Aucun PC enregistré.</p> : (
        <ul className="m-0 flex list-none flex-col divide-y divide-border-subtle p-0">
          {list.map((d) => (
            <li key={d.id} className="flex flex-wrap items-center justify-between gap-3 py-2">
              <span>
                <strong>{d.label}</strong>
                <span className="block text-small text-muted-foreground">
                  Dernière connexion le {isoToDateFr(d.lastSeenAt.slice(0, 10))}{d.lastUserName && ` par ${d.lastUserName}`}
                  {!d.active && " · ne compte plus (absent depuis 90 jours)"}
                </span>
              </span>
              <Button variant="outline" size="sm" disabled={revoke.isPending}
                onClick={() => {
                  if (window.confirm(`Retirer « ${d.label} » ? Ce PC ne pourra plus se connecter à Ti’Cagou.`)) {
                    revoke.mutate(d.id, { onSuccess: () => queryClient.invalidateQueries({ queryKey: systemKeys.desktops }) });
                  }
                }}>
                Retirer
              </Button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
