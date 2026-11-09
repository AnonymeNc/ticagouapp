import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import type { Row } from "@/features/parc/api";
import { errorMessage } from "@/lib/api";
import { ordersApi, useCrecheOrders, useOrderAction, type OrderType } from "./api";
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
