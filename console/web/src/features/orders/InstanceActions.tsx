import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import type { Row } from "@/features/parc/api";
import { errorMessage } from "@/lib/api";
import { ordersApi, rubriqueLabels, useCrecheOrders, useOrderAction, type OrderType } from "./api";
import { DeleteCreche } from "./DeleteCreche";
import { OrdersTable } from "./OrdersPage";

/**
 * Actions de la fiche sur l'instance, par ordre (code redemandé) : créer l'instance d'un prospect, suspendre ou reprendre,
 * supprimer une démonstration. La console ne suspend jamais seule.
 */
export function InstanceActions({ row }: { row: Row }) {
  const [suspending, setSuspending] = useState(false);
  const [reason, setReason] = useState("");
  const give = useOrderAction((input: { type: OrderType; reason?: string }) => ordersApi.give({ type: input.type, slug: row.slug, reason: input.reason ?? null }));
  const stopped = row.container === "exited" || row.container === "created" || row.container === "dead" || row.container === "paused";
  const hasInstance = !!row.container && row.container !== "absent";
  const error = give.isError ? <span role="alert" className="text-small text-danger-700">{errorMessage(give.error)}</span> : null;
  const sent = give.isSuccess ? <span role="status" className="text-small text-success-700">Ordre transmis : l’hôte l’exécute dans la minute.</span> : null;

  if (row.lifecycle === "RESILIE" && !hasInstance) {
    return null;
  }
  if (!hasInstance && row.lifecycle !== "DEMO") {
    return (
      <>
        <Button disabled={give.isPending} onClick={() => give.mutate({ type: "CREER" })}>Créer l’instance</Button>
        {error}
        {sent}
      </>
    );
  }
  if (row.lifecycle === "DEMO") {
    return (
      <>
        <Button variant="destructive" disabled={give.isPending} onClick={() => give.mutate({ type: "SUPPRIMER_DEMO" })}>Supprimer la démo</Button>
        {error}
        {sent}
      </>
    );
  }
  if (stopped) {
    return (
      <>
        <Button disabled={give.isPending} onClick={() => give.mutate({ type: "REPRENDRE" })}>Reprendre</Button>
        {error}
        {sent}
      </>
    );
  }
  if (!suspending) {
    return (
      <>
        <Button variant="destructive" onClick={() => setSuspending(true)}>Suspendre…</Button>
        {sent}
      </>
    );
  }
  return (
    <form
      className="flex flex-wrap items-center gap-2"
      onSubmit={(e) => {
        e.preventDefault();
        give.mutate({ type: "SUSPENDRE", reason }, { onSuccess: () => setSuspending(false) });
      }}
    >
      <Input aria-label="Motif de la suspension" required placeholder="Motif (impayé après relance 3…)" className="w-64" value={reason} onChange={(e) => setReason(e.target.value)} />
      <Button type="submit" variant="destructive" disabled={give.isPending}>Suspendre (code)</Button>
      <Button type="button" variant="ghost" onClick={() => setSuspending(false)}>Annuler</Button>
      {error}
    </form>
  );
}

/**
 * Rubriques affichées (ADR 0022) : à la demande de la crèche, des menus, onglets et cartes qu'elle n'utilise pas sont
 * masqués. Simple affichage, tout reste compris dans la formule : rien n'est effacé, une rubrique se réaffiche sans perte.
 */
export function CrecheRubriques({ row, hidden }: { row: Row; hidden: string[] }) {
  const [masked, setMasked] = useState<string[]>(hidden);
  const give = useOrderAction((masquer: string[]) =>
    ordersApi.give({ type: "RUBRIQUES", slug: row.slug, params: { masquer: masquer.join(",") } }),
  );
  const hasInstance = !!row.container && row.container !== "absent";
  if (!hasInstance) {
    return null;
  }
  const changed = [...masked].sort().join(",") !== [...hidden].sort().join(",");
  const toggle = (key: string, show: boolean) =>
    setMasked((m) => (show ? m.filter((k) => k !== key) : [...m, key]));
  return (
    <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <div>
        <h2 className="m-0 font-display text-title-3">Rubriques affichées</h2>
        <p className="m-0 mt-1 text-small text-muted-foreground">
          Décochez ce que la crèche ne veut pas voir. Rien n’est effacé ni facturé à part ; sans contrats, ni facturation ni
          comptabilité.
        </p>
      </div>
      <fieldset className="m-0 grid gap-1.5 border-0 p-0 sm:grid-cols-2">
        <legend className="sr-only">Rubriques affichées dans l’instance</legend>
        {Object.entries(rubriqueLabels).map(([key, label]) => (
          <label key={key} className="flex items-center gap-1.5">
            <input type="checkbox" checked={!masked.includes(key)} onChange={(e) => toggle(key, e.target.checked)} /> {label}
          </label>
        ))}
      </fieldset>
      <div className="flex flex-wrap items-center gap-2">
        <Button variant="outline" disabled={!changed || give.isPending} onClick={() => give.mutate(masked)}>
          Appliquer (code)
        </Button>
        {give.isError && <span role="alert" className="text-small text-danger-700">{errorMessage(give.error)}</span>}
        {give.isSuccess && (
          <span role="status" className="text-small text-success-700">
            Ordre transmis : l’hôte l’applique dans la minute, visible ici au prochain rapport (5 min).
          </span>
        )}
      </div>
    </section>
  );
}

/**
 * Accès de la crèche (onglet Abonnement) : double authentification à refaire quand la direction a perdu son téléphone,
 * code d'activation du portail parents (affiché dans la sortie de l'ordre).
 */
export function CrecheAccess({ row }: { row: Row }) {
  const [login, setLogin] = useState("");
  const phone = useOrderAction((identifiant: string) =>
    ordersApi.give({ type: "TELEPHONE_PERDU", slug: row.slug, params: { identifiant } }),
  );
  const password = useOrderAction((identifiant: string) =>
    ordersApi.give({ type: "MOT_DE_PASSE", slug: row.slug, params: { identifiant } }),
  );
  const account = login.trim();
  const portal = useOrderAction(() => ordersApi.give({ type: "CODE_PORTAIL", slug: row.slug }));
  const hasInstance = !!row.container && row.container !== "absent";
  if (row.lifecycle === "DEMO" || !hasInstance) {
    return null;
  }
  return (
    <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <div>
        <h2 className="m-0 font-display text-title-3">Accès de la crèche</h2>
        <p className="m-0 mt-1 text-small text-muted-foreground">
          Vérifiez d’abord l’identité de la personne qui appelle. Le lien ou le code s’affiche dans « Ordres de cette
          crèche » d’ici 1 à 2 minutes, avec un bouton Copier : transmettez-le par téléphone ou SMS.
        </p>
      </div>
      <div className="flex flex-col gap-2">
        <Input
          aria-label="Identifiant du compte"
          placeholder="Identifiant (nathalie.kaloi)"
          className="w-64"
          value={login}
          onChange={(e) => setLogin(e.target.value)}
        />
        <div className="flex flex-wrap items-center gap-2">
          <Button variant="outline" disabled={!account || password.isPending} onClick={() => password.mutate(account)}>
            Mot de passe oublié : créer un lien
          </Button>
          <Button variant="outline" disabled={!account || phone.isPending} onClick={() => phone.mutate(account)}>
            Téléphone perdu : refaire la double authentification
          </Button>
        </div>
        {password.isError && <span role="alert" className="text-small text-danger-700">{errorMessage(password.error)}</span>}
        {password.isSuccess && (
          <span role="status" className="text-small text-success-700">
            Ordre transmis : le lien (24 h, usage unique) apparaît dans les ordres de cette crèche.
          </span>
        )}
        {phone.isError && <span role="alert" className="text-small text-danger-700">{errorMessage(phone.error)}</span>}
        {phone.isSuccess && <span role="status" className="text-small text-success-700">Ordre transmis : à refaire à la prochaine connexion.</span>}
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <Button variant="outline" disabled={portal.isPending} onClick={() => portal.mutate(undefined)}>Code du portail parents</Button>
        {portal.isError && <span role="alert" className="text-small text-danger-700">{errorMessage(portal.error)}</span>}
        {portal.isSuccess && <span role="status" className="text-small text-success-700">Ordre transmis : le code apparaît dans sa sortie d’ici 1 à 2 minutes.</span>}
      </div>
    </section>
  );
}

/**
 * Données de la crèche (onglet Abonnement) : export complet à remettre à la crèche, ou suppression, qui exporte et fait
 * télécharger d'abord. Pas pour une démonstration (supprimée sans export) ni sans instance.
 */
export function CrecheData({ row }: { row: Row }) {
  const give = useOrderAction(() => ordersApi.give({ type: "EXPORTER", slug: row.slug }));
  const hasInstance = !!row.container && row.container !== "absent";
  if (row.lifecycle === "DEMO" || !hasInstance) {
    return null;
  }
  return (
    <section className="flex flex-col gap-3 rounded-lg border border-danger-100 bg-card p-5 shadow-1">
      <div>
        <h2 className="m-0 font-display text-title-3">Données de la crèche</h2>
        <p className="m-0 mt-1 text-small text-muted-foreground">
          Export complet et chiffré (base, documents, clés), à télécharger depuis les ordres ; la suppression commence toujours par lui.
        </p>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <Button variant="outline" disabled={give.isPending} onClick={() => give.mutate(undefined)}>Exporter les données</Button>
        <DeleteCreche row={row} />
        {give.isError && <span role="alert" className="text-small text-danger-700">{errorMessage(give.error)}</span>}
        {give.isSuccess && <span role="status" className="text-small text-success-700">Export demandé : il apparaît dans les ordres d’ici 1 à 3 minutes.</span>}
      </div>
    </section>
  );
}

/** Ordres de la crèche (onglet Abonnement). */
export function CrecheOrders({ slug }: { slug: string }) {
  const query = useCrecheOrders(slug);
  if (!query.data || query.data.length === 0) {
    return null;
  }
  return (
    <section className="rounded-lg border border-border-subtle bg-card p-2 shadow-1">
      <h2 className="m-0 px-3 pt-3 font-display text-title-3">Ordres de cette crèche</h2>
      <OrdersTable orders={query.data} compact />
    </section>
  );
}
