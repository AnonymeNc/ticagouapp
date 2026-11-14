import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Monitor, Smartphone } from "lucide-react";
import { useState } from "react";
import { Alert } from "@/components/FocusLayout";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import type { Compat } from "@/lib/native/annuaire";
import { api } from "@/lib/api";
import { qrDataUrl } from "./DevicesTab";
import { TonePill, type Tone } from "@/features/enrollment/components";
import { errorMessage } from "@/lib/api";
import { isoToDateFr } from "@/lib/datetime";
import { useRubriques } from "@/features/structure/useRubriques";
import { systemApi, systemKeys, type DesktopClient, type LicenseState } from "./systemApi";

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
            <dd className="m-0">{data.desktops} PC sur {data.desktopLimit}{data.mobileLimit !== undefined && ` · ${data.mobiles ?? 0} téléphone${(data.mobiles ?? 0) > 1 ? "s" : ""} sur ${data.mobileLimit}`} · {data.pairedTablets} tablette{data.pairedTablets > 1 ? "s" : ""} sur {l.tablets}</dd>
            <dt className="text-muted-foreground">Places</dt><dd className="m-0">{data.capacity} sur {l.places} autorisées</dd>
            <dt className="text-muted-foreground">Valide jusqu’au</dt><dd className="m-0">{isoToDateFr(l.validUntil)}</dd>
          </dl>
        )}
        {l && data.renewalFrom && <p className="m-0 text-small text-muted-foreground">
          Le renouvellement vous sera proposé à partir du {isoToDateFr(data.renewalFrom)}. Après l’échéance, Ti’Cagou fonctionne encore
          30 jours, puis passe en lecture seule : les données restent consultables, les sauvegardes continuent.
        </p>}
      </section>
      <DesktopsSection limit={data.desktopLimit} mobileLimit={data.mobileLimit} />
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

/**
 * Postes de la crèche : PC comptés par l’abonnement, et téléphones de la direction (application), hors du compte des PC
 * (ADR 0028). La direction retire un poste remplacé ou perdu : il ne peut plus se connecter.
 */
function DesktopsSection({ limit, mobileLimit }: { limit: number; mobileLimit?: number }) {
  const queryClient = useQueryClient();
  const desktops = useQuery({ queryKey: systemKeys.desktops, queryFn: ({ signal }) => systemApi.desktops(signal) });
  const revoke = useMutation({
    mutationFn: systemApi.revokeDesktop,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: systemKeys.license }),
  });
  const all = desktops.data ?? [];
  const pcs = all.filter((d) => d.kind !== "MOBILE");
  const phones = all.filter((d) => d.kind === "MOBILE");
  const onRevoke = (d: DesktopClient) => {
    const what = d.kind === "MOBILE" ? "Ce téléphone" : "Ce PC";
    if (window.confirm(`Retirer « ${d.label} » ? ${what} ne pourra plus se connecter à Ti’Cagou.`)) {
      revoke.mutate(d.id, { onSuccess: () => queryClient.invalidateQueries({ queryKey: systemKeys.desktops }) });
    }
  };
  return (
    <>
    <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <h2 className="m-0 flex items-center gap-2 text-title-3"><Monitor aria-hidden className="size-5" />PC du bureau</h2>
      <p className="m-0 text-small text-muted-foreground">
        L’abonnement permet {limit} PC. Un PC remplacé se retire ici ; un PC qui ne s’est pas connecté depuis 90 jours libère
        sa place tout seul.
      </p>
      {revoke.isError && <Alert>{errorMessage(revoke.error)}</Alert>}
      <PosteList list={pcs} empty="Aucun PC enregistré." pending={revoke.isPending} onRevoke={onRevoke} />
    </section>
    <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <h2 className="m-0 flex items-center gap-2 text-title-3"><Smartphone aria-hidden className="size-5" />Téléphones de la direction</h2>
      <p className="m-0 text-small text-muted-foreground">
        L’application Ti’Cagou Direction est comprise dans l’abonnement : un téléphone ne prend pas de place de PC
        {mobileLimit !== undefined && ` (${mobileLimit} téléphones au plus)`}. Téléphone perdu ou changé : retirez-le ici, il
        est déconnecté dans les 15 minutes.
      </p>
      <PosteList list={phones} empty="Aucun téléphone n’a encore ouvert l’application Ti’Cagou Direction." pending={revoke.isPending} onRevoke={onRevoke} />
      <OpenOnPhone />
    </section>
    </>
  );
}

function PosteList({ list, empty, pending, onRevoke }: { list: DesktopClient[]; empty: string; pending: boolean; onRevoke: (d: DesktopClient) => void }) {
  return (
    <>
      {list.length === 0 ? <p className="m-0 text-muted-foreground">{empty}</p> : (
        <ul className="m-0 flex list-none flex-col divide-y divide-border-subtle p-0">
          {list.map((d) => (
            <li key={d.id} className="flex flex-wrap items-center justify-between gap-3 py-2">
              <span>
                <strong>{d.label}</strong>
                <span className="block text-small text-muted-foreground">
                  {d.app && <>{d.app} · </>}Dernière connexion le {isoToDateFr(d.lastSeenAt.slice(0, 10))}{d.lastUserName && ` par ${d.lastUserName}`}
                  {!d.active && " · ne compte plus (absent depuis 90 jours)"}
                </span>
              </span>
              <Button variant="outline" size="sm" disabled={pending} onClick={() => onRevoke(d)}>
                Retirer
              </Button>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}

/** « Ouvrir sur mon téléphone » (ADR 0025, maquette D3) : QR de l’adresse de la crèche et code de l’annuaire. */
function OpenOnPhone() {
  const [open, setOpen] = useState(false);
  const compat = useQuery({
    queryKey: ["app-compat", "instance"],
    queryFn: ({ signal }) => api<Compat>("/app/compat", { signal, auth: false }),
    enabled: open,
  });
  const link = `${window.location.origin}/app`;
  return (
    <>
      <Button variant="outline" className="self-start" onClick={() => setOpen(true)}>
        <Smartphone aria-hidden />
        Ouvrir sur mon téléphone
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Ouvrir sur mon téléphone</DialogTitle>
            <DialogDescription>
              1. Installez Ti’Cagou Direction (App Store ou Google Play). 2. Ouvrez-la et touchez « Scanner le QR de la crèche ».
            </DialogDescription>
          </DialogHeader>
          <img src={qrDataUrl(link)} alt={`QR code de la crèche : ${link}`} className="mx-auto size-48 rounded bg-white p-2" />
          {compat.data?.code && (
            <p className="m-0 text-center">
              Ou tapez le code de la crèche : <strong className="font-display text-title-2 tracking-[0.15em]">{compat.data.code}</strong>
            </p>
          )}
          <p className="m-0 text-center text-small text-muted-foreground">
            Le QR ne contient que l’adresse de la crèche, aucun accès : il faudra se connecter avec son identifiant.
          </p>
        </DialogContent>
      </Dialog>
    </>
  );
}
