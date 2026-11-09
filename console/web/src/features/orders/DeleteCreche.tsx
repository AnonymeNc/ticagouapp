import { useEffect, useRef, useState, type ReactNode } from "react";
import { AlertTriangle, Download } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { xpf } from "@/features/billing/labels";
import type { Row } from "@/features/parc/api";
import { errorMessage } from "@/lib/api";
import { formatSize, ordersApi, useCrecheOrders, useDownloadExport, useOrderAction } from "./api";

type Step = "closed" | "warn" | "retype" | "running";

/**
 * Supprimer une crèche depuis la console : deux mises en garde (ce qui est effacé, puis l'identifiant retapé et le
 * code), un export complet fait par l'hôte et téléchargé tout seul sur cet ordinateur ; la suppression ne part qu'une
 * fois ce téléchargement terminé (la console crée alors l'ordre, l'hôte revérifie l'export).
 */
export function DeleteCreche({ row }: { row: Row }) {
  const [step, setStep] = useState<Step>("closed");
  const [typed, setTyped] = useState("");
  const [orderId, setOrderId] = useState<string | null>(null);
  const give = useOrderAction((confirmation: string) =>
    ordersApi.give({ type: "EXPORTER", slug: row.slug, params: { suite: "supprimer", confirmation }, reason: "Suppression de la crèche" }),
  );
  const orders = useCrecheOrders(row.slug);
  const download = useDownloadExport();
  const started = useRef(false);
  const order = orders.data?.find((o) => o.id === orderId);
  const { mutate } = download;

  // Export prêt : téléchargé sans attendre de clic, une seule fois.
  useEffect(() => {
    if (order?.status === "FAIT" && order.result?.exportFile && !order.result.deletionOrder && !started.current) {
      started.current = true;
      mutate(order);
    }
  }, [order, mutate]);

  const close = () => {
    setStep("closed");
    setTyped("");
    give.reset();
  };

  if (step === "closed") {
    return (
      <Button variant="destructive" onClick={() => setStep("warn")}>
        Supprimer la crèche…
      </Button>
    );
  }

  return (
    <div className="fixed inset-0 z-40 grid place-items-center bg-overlay p-4" onKeyDown={(e) => e.key === "Escape" && step !== "running" && close()}>
      <div role="dialog" aria-modal="true" aria-labelledby="supprimer-titre" className="flex w-full max-w-lg flex-col gap-4 rounded-lg bg-card p-6 shadow-3">
        {step === "warn" && (
          <>
            <Title icon>Supprimer {row.name} ?</Title>
            <div className="flex flex-col gap-2 text-body">
              <p className="m-0">Efface définitivement, sur le serveur :</p>
              <ul className="m-0 flex flex-col gap-1 pl-5">
                <li>la base de la crèche : enfants, familles, santé, contrats, présences, factures aux familles, journal ;</li>
                <li>ses documents et ses sauvegardes ;</li>
                <li>son adresse : l’équipe, les tablettes et les parents perdent l’accès aussitôt.</li>
              </ul>
              <p className="m-0">
                Restent dans la console : la fiche, le contrat et vos factures à la crèche, qui passe en « résiliée ».
              </p>
              {row.balanceDue > 0 && (
                <Warning>Solde dû : {xpf(row.balanceDue)}. Vos factures restent à encaisser après la suppression.</Warning>
              )}
              {!!row.activeChildren && row.activeChildren > 0 && (
                <Warning>
                  {row.activeChildren} enfant{row.activeChildren > 1 ? "s" : ""} encore inscrit{row.activeChildren > 1 ? "s" : ""} dans l’instance.
                </Warning>
              )}
              <p className="m-0 rounded-md bg-muted p-3 text-small">
                Avant d’effacer quoi que ce soit, un export complet et chiffré (base, documents, clés) est téléchargé sur cet ordinateur. La
                suppression ne part qu’une fois ce téléchargement terminé.
              </p>
            </div>
            <div className="flex justify-end gap-2">
              <Button variant="ghost" onClick={close}>Annuler</Button>
              <Button variant="destructive" onClick={() => setStep("retype")}>Continuer</Button>
            </div>
          </>
        )}

        {step === "retype" && (
          <form
            className="flex flex-col gap-4"
            onSubmit={(e) => {
              e.preventDefault();
              give.mutate(typed, {
                onSuccess: (o) => {
                  setOrderId(o.id);
                  started.current = false;
                  setStep("running");
                },
              });
            }}
          >
            <Title icon>Dernière vérification</Title>
            <p className="m-0">
              Cette suppression est irréversible. Retapez l’identifiant <code className="rounded bg-muted px-1.5 font-mono">{row.slug}</code> ; le
              code de votre application vous sera ensuite demandé.
            </p>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="supprimer-identifiant">Identifiant de la crèche</Label>
              <Input id="supprimer-identifiant" autoComplete="off" autoFocus value={typed} onChange={(e) => setTyped(e.target.value)} />
            </div>
            {give.isError && <p role="alert" className="m-0 text-danger-700">{errorMessage(give.error)}</p>}
            <div className="flex justify-end gap-2">
              <Button type="button" variant="ghost" onClick={close}>Annuler</Button>
              <Button type="submit" variant="destructive" disabled={typed !== row.slug || give.isPending}>
                Exporter puis supprimer
              </Button>
            </div>
          </form>
        )}

        {step === "running" && (
          <>
            <Title>Suppression de {row.name}</Title>
            <Progress
              exportState={
                !order || order.status === "EN_ATTENTE" || order.status === "EN_COURS"
                  ? "pending"
                  : order.status === "FAIT"
                    ? "done"
                    : "failed"
              }
              downloadState={download.isPending ? "pending" : download.isError ? "failed" : download.isSuccess || order?.result?.deletionOrder ? "done" : "waiting"}
            />
            {order && (order.status === "ECHEC" || order.status === "ANNULE") && (
              <>
                <p role="alert" className="m-0 text-danger-700">L’export n’a pas abouti : rien n’a été supprimé.</p>
                {order.output && <pre className="m-0 max-h-48 overflow-auto rounded-md bg-ink p-3 font-mono text-small whitespace-pre-wrap text-white">{order.output}</pre>}
              </>
            )}
            {download.isError && (
              <div className="flex flex-col gap-2">
                <p role="alert" className="m-0 text-danger-700">{errorMessage(download.error)} Rien n’a été supprimé.</p>
                <Button variant="outline" onClick={() => order && mutate(order)}>
                  <Download aria-hidden />
                  Réessayer le téléchargement
                </Button>
              </div>
            )}
            {(download.isSuccess || order?.result?.deletionOrder) && (
              <p role="status" className="m-0 text-success-700">
                Export enregistré sur cet ordinateur{order?.result?.exportFile ? ` : ${order.result.exportFile}` : ""}
                {order?.result?.size != null ? ` (${formatSize(order.result.size)})` : ""}. Suppression transmise : l’hôte l’efface dans la minute ;
                suivez-la dans les ordres de la crèche.
              </p>
            )}
            {!download.isSuccess && !order?.result?.deletionOrder && order?.status !== "ECHEC" && order?.status !== "ANNULE" && (
              <p className="m-0 text-small text-muted-foreground">
                Gardez cette page ouverte : l’export se télécharge tout seul dès qu’il est prêt (1 à 3 minutes). Fermée trop tôt, il reste à
                télécharger depuis l’écran Ordres, et la suppression attend.
              </p>
            )}
            <div className="flex justify-end">
              <Button variant="outline" onClick={close}>Fermer</Button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

function Title({ children, icon = false }: { children: ReactNode; icon?: boolean }) {
  return (
    <h2 id="supprimer-titre" className="m-0 flex items-center gap-2 font-display text-title-2">
      {icon && <AlertTriangle aria-hidden className="size-5 text-danger-700" />}
      {children}
    </h2>
  );
}

function Warning({ children }: { children: ReactNode }) {
  return <p className="m-0 rounded-md border border-warning-500 bg-warning-100 p-3 text-small text-warning-700">{children}</p>;
}

type State = "waiting" | "pending" | "done" | "failed";

function Progress({ exportState, downloadState }: { exportState: State; downloadState: State }) {
  const label: Record<State, string> = { waiting: "à venir", pending: "en cours…", done: "fait", failed: "échec" };
  const tone: Record<State, string> = { waiting: "text-muted-foreground", pending: "text-lagon-600", done: "text-success-700", failed: "text-danger-700" };
  return (
    <ol className="m-0 flex flex-col gap-1 pl-5">
      <li>Export sur le serveur : <span className={tone[exportState]}>{label[exportState]}</span></li>
      <li>Téléchargement sur cet ordinateur : <span className={tone[downloadState]}>{label[downloadState]}</span></li>
      <li>Suppression par l’hôte : <span className={tone[downloadState === "done" ? "pending" : "waiting"]}>{downloadState === "done" ? "transmise" : "à venir"}</span></li>
    </ol>
  );
}
