import { useState, type FormEvent, type ReactNode } from "react";
import { Link } from "react-router";
import { AlertTriangle, Copy, Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Pill } from "@/components/ui/pill";
import { useParc } from "@/features/parc/api";
import { slugify } from "@/features/parc/ParcPage";
import { ago } from "@/features/parc/labels";
import { errorMessage } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { cn } from "@/lib/utils";
import { describe, formatSize, ordersApi, statusPill, typeLabels, useDownloadExport, useOrderAction, useOrders, type Order } from "./api";

type Panel = "creer" | "demo" | "maj" | null;

/**
 * Ordres aux instances (maquette « Ordres ») : exécutés sur le serveur par `creche.sh appliquer`, chaque minute ; la
 * console ne touche jamais Docker. Créer une crèche, lancer une démonstration, mettre à jour, aligner un abonnement.
 */
export function OrdersPage() {
  const query = useOrders();
  const [panel, setPanel] = useState<Panel>(null);
  const align = useOrderAction((slug: string) => ordersApi.give({ type: "ABONNEMENT", slug, reason: "Abonnement aligné sur le contrat" }));
  const board = query.data;

  return (
    <>
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="m-0 font-display text-display">Ordres aux instances</h1>
          <p className="mt-1 text-muted-foreground">
            Exécutés sur le serveur par <code className="rounded bg-muted px-1 font-mono text-small">creche.sh appliquer</code>, chaque minute. La console ne touche
            jamais Docker.{board?.hostAt ? ` Dernier rapport de l’hôte ${ago(board.hostAt)}.` : ""}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" onClick={() => setPanel(panel === "maj" ? null : "maj")}>Mise à jour…</Button>
          <Button variant="outline" onClick={() => setPanel(panel === "demo" ? null : "demo")}>Démonstration…</Button>
          <Button onClick={() => setPanel(panel === "creer" ? null : "creer")}>
            <Plus aria-hidden />
            Nouvelle crèche
          </Button>
        </div>
      </div>

      {panel === "creer" && <CreateForm onDone={() => setPanel(null)} />}
      {panel === "demo" && <DemoForm onDone={() => setPanel(null)} />}
      {panel === "maj" && <UpdateForm images={board?.images ?? []} orders={board?.orders ?? []} onDone={() => setPanel(null)} />}

      {board && board.misaligned.length > 0 && (
        <section role="status" className="flex flex-col gap-2 rounded-md bg-warning-100 px-4 py-3 text-warning-700">
          <b className="flex items-center gap-2"><AlertTriangle aria-hidden className="size-5" />Abonnement non aligné sur le contrat</b>
          {board.misaligned.map((m) => (
            <div key={m.slug} className="flex flex-wrap items-center gap-3">
              <Link to={`/parc/${m.slug}`} className="font-semibold underline">{m.name}</Link>
              <span className="flex-1 text-small">{m.differences.join(" · ")}</span>
              <Button size="sm" variant="outline" disabled={align.isPending} onClick={() => align.mutate(m.slug)}>Reporter le contrat dans l’instance</Button>
            </div>
          ))}
          {align.isError && <span role="alert" className="text-danger-700">{errorMessage(align.error)}</span>}
        </section>
      )}

      <section className="rounded-lg border border-border-subtle bg-card p-2 shadow-1">
        {query.isPending ? (
          <p className="m-0 p-4 text-muted-foreground">Chargement…</p>
        ) : query.isError ? (
          <p role="alert" className="m-0 p-4 text-danger-700">{errorMessage(query.error)}</p>
        ) : board!.orders.length === 0 ? (
          <p className="m-0 p-4 text-muted-foreground">Aucun ordre pour l’instant.</p>
        ) : (
          <OrdersTable orders={board!.orders} />
        )}
      </section>
      <p className="m-0 text-small text-muted-foreground">
        Les ordres que vous donnez demandent votre code. Ceux que la console propose (abonnement payé) attendent « Confirmer ». Une crèche se
        supprime depuis sa fiche (onglet Abonnement) : son export complet se télécharge d’abord sur cet ordinateur.
      </p>
    </>
  );
}

export function OrdersTable({ orders, compact = false }: { orders: Order[]; compact?: boolean }) {
  const [open, setOpen] = useState<string | null>(null);
  const confirm = useOrderAction((id: string) => ordersApi.confirm(id));
  const cancel = useOrderAction((id: string) => ordersApi.cancel(id));
  const download = useDownloadExport();
  const error = confirm.error ?? cancel.error ?? download.error;
  return (
    <div className="overflow-x-auto">
      {error && <p role="alert" className="m-0 p-2 text-danger-700">{errorMessage(error)}</p>}
      <table className="w-full border-collapse">
        <thead className="border-b border-border-subtle text-left text-label text-muted-foreground">
          <tr>
            <th className="px-3 py-2.5">Demandé</th>
            {!compact && <th className="px-3 py-2.5">Crèche</th>}
            <th className="px-3 py-2.5">Ordre</th>
            <th className="px-3 py-2.5">Paramètres</th>
            <th className="px-3 py-2.5">État</th>
            <th className="px-3 py-2.5" />
          </tr>
        </thead>
        <tbody>
          {orders.map((o) => {
            const pill = o.stale ? { tone: "bad" as const, label: "Bloqué ?" } : statusPill[o.status];
            return (
              <FragmentRow key={o.id}>
                <tr className="border-b border-border-subtle align-top">
                  <td className="px-3 py-2.5 whitespace-nowrap tabular-nums">{formatDateTime(new Date(o.createdAt))}</td>
                  {!compact && (
                    <td className="px-3 py-2.5">
                      <Link to={`/parc/${o.slug}`} className="hover:underline">{o.crecheName ?? o.slug}</Link>
                    </td>
                  )}
                  <td className="px-3 py-2.5">{typeLabels[o.type]}</td>
                  <td className="px-3 py-2.5 text-small text-muted-foreground">
                    {describe(o)}
                    {o.reason && <div>{o.reason}</div>}
                    {o.result?.setupLink && <CopyValue label="Lien de démarrage" value={o.result.setupLink} />}
                    {o.result?.resetLink && <CopyValue label="Lien de réinitialisation (24 h, usage unique)" value={o.result.resetLink} />}
                    {o.result?.portalCode && <CopyValue label="Code du portail parents" value={o.result.portalCode} />}
                    {o.result?.exportFile && (
                      <div>
                        Export : <span className="font-mono">{o.result.exportFile}</span>
                        {o.result.size != null && ` (${formatSize(o.result.size)})`}
                        {o.result.receivedAt && ", téléchargé"}
                      </div>
                    )}
                    {o.result?.url && (
                      <div>
                        <a href={o.result.url} target="_blank" rel="noreferrer" className="text-lagon-600 underline">{o.result.url}</a>
                      </div>
                    )}
                  </td>
                  <td className="px-3 py-2.5"><Pill tone={pill.tone}>{pill.label}</Pill></td>
                  <td className="px-3 py-2.5 text-right whitespace-nowrap">
                    {o.status === "A_CONFIRMER" && (
                      <Button size="sm" disabled={confirm.isPending} onClick={() => confirm.mutate(o.id)}>Confirmer (code)</Button>
                    )}
                    {o.status === "FAIT" && o.result?.exportFile && (
                      <Button
                        size="sm"
                        variant={o.params.suite === "supprimer" && !o.result.deletionOrder ? "destructive" : "outline"}
                        disabled={download.isPending}
                        onClick={() => download.mutate(o)}
                      >
                        {o.params.suite === "supprimer" && !o.result.deletionOrder ? "Télécharger puis supprimer" : "Télécharger l’export"}
                      </Button>
                    )}
                    {(o.status === "A_CONFIRMER" || o.status === "EN_ATTENTE" || o.stale) && (
                      <Button size="sm" variant="ghost" disabled={cancel.isPending} onClick={() => cancel.mutate(o.id)}>Annuler</Button>
                    )}
                    {o.output && (
                      <Button size="sm" variant="outline" aria-expanded={open === o.id} onClick={() => setOpen(open === o.id ? null : o.id)}>
                        {open === o.id ? "Masquer la sortie" : "Voir la sortie"}
                      </Button>
                    )}
                  </td>
                </tr>
                {open === o.id && (
                  <tr className="border-b border-border-subtle">
                    <td colSpan={compact ? 5 : 6} className="px-3 py-2">
                      <pre className="m-0 max-h-72 overflow-auto rounded-md bg-ink p-3 font-mono text-small whitespace-pre-wrap text-white">{o.output}</pre>
                    </td>
                  </tr>
                )}
              </FragmentRow>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function FragmentRow({ children }: { children: ReactNode }) {
  return <>{children}</>;
}

/** Lien de démarrage d'une crèche créée : à remettre à la directrice (une seule utilisation). */
/** Lien ou code produit par un ordre, à copier pour le transmettre. */
function CopyValue({ label, value }: { label: string; value: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="mt-1 flex flex-wrap items-center gap-2">
      <span>{label} :</span>
      <code className="rounded bg-muted px-1.5 font-mono break-all text-foreground">{value}</code>
      <Button
        size="sm"
        variant="ghost"
        aria-label={`Copier : ${label.toLowerCase()}`}
        onClick={() => void navigator.clipboard?.writeText(value).then(() => setCopied(true))}
      >
        <Copy aria-hidden />
        {copied ? "Copié" : "Copier"}
      </Button>
    </div>
  );
}

function FormCard({ title, intro, children, onSubmit }: { title: string; intro: ReactNode; children: ReactNode; onSubmit: (e: FormEvent) => void }) {
  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-4 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <div>
        <h2 className="m-0 font-display text-title-2">{title}</h2>
        <p className="m-0 mt-1 text-small text-muted-foreground">{intro}</p>
      </div>
      {children}
    </form>
  );
}

function CreateForm({ onDone }: { onDone: () => void }) {
  const parc = useParc();
  const prospects = (parc.data?.creches ?? []).filter((c) => c.lifecycle === "PROSPECT");
  const [name, setName] = useState("");
  const [slug, setSlug] = useState("");
  const [edited, setEdited] = useState(false);
  const [domain, setDomain] = useState("");
  const give = useOrderAction(() => ordersApi.give({ type: "CREER", slug, params: { nom: name, ...(domain ? { domaine: domain } : {}) } }));
  return (
    <FormCard
      title="Nouvelle crèche"
      intro="Crée l’instance (base, site HTTPS, supervision) : comptez une à deux minutes après la prise en charge. Le lien de démarrage à remettre à la directrice s’affiche ici, dans l’ordre."
      onSubmit={(e) => {
        e.preventDefault();
        give.mutate(undefined, { onSuccess: onDone });
      }}
    >
      {prospects.length > 0 && (
        <div className="flex flex-wrap items-center gap-2 text-small">
          <span className="text-muted-foreground">Prospect déjà noté :</span>
          {prospects.map((p) => (
            <Button key={p.slug} type="button" size="sm" variant="outline" onClick={() => { setName(p.name); setSlug(p.slug); setEdited(true); }}>
              {p.name}
            </Button>
          ))}
        </div>
      )}
      <div className="grid gap-3 md:grid-cols-3">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="o-nom">Nom</Label>
          <Input id="o-nom" required value={name} onChange={(e) => { setName(e.target.value); if (!edited) setSlug(slugify(e.target.value)); }} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="o-slug">Identifiant</Label>
          <Input id="o-slug" required pattern="[a-z0-9][a-z0-9\-]{1,40}" value={slug} onChange={(e) => { setSlug(e.target.value); setEdited(true); }} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="o-domaine">Domaine (facultatif)</Label>
          <Input id="o-domaine" placeholder={`${slug || "identifiant"}.<domaine de base>`} value={domain} onChange={(e) => setDomain(e.target.value.trim())} />
        </div>
      </div>
      {give.isError && <p role="alert" className="m-0 text-danger-700">{errorMessage(give.error)}</p>}
      <div className="flex gap-2">
        <Button type="submit" disabled={give.isPending}>Créer l’instance</Button>
        <Button type="button" variant="ghost" onClick={onDone}>Annuler</Button>
      </div>
    </FormCard>
  );
}

function DemoForm({ onDone }: { onDone: () => void }) {
  // Une seule formule (ADR 0021) : la démonstration montre tout Ti'Cagou.
  const give = useOrderAction(() => ordersApi.give({ type: "DEMO", params: {} }));
  return (
    <FormCard
      title="Démonstration"
      intro="Une instance à part, remplie comme le PC de démonstration : « Les P’tits Niaoulis », 27 enfants, équipe, 18 mois de présences et de factures, repas, inscriptions. Comptez quelques minutes ; l’adresse s’affiche dans l’ordre. Elle ne compte ni comme cliente ni dans le revenu."
      onSubmit={(e) => {
        e.preventDefault();
        give.mutate(undefined, { onSuccess: onDone });
      }}
    >
      <div className="flex flex-wrap items-end gap-3">
        <Button type="submit" disabled={give.isPending}>Lancer la démonstration</Button>
        <Button type="button" variant="ghost" onClick={onDone}>Annuler</Button>
      </div>
      {give.isError && <p role="alert" className="m-0 text-danger-700">{errorMessage(give.error)}</p>}
    </FormCard>
  );
}

function UpdateForm({ images, orders, onDone }: { images: string[]; orders: Order[]; onDone: () => void }) {
  const parc = useParc();
  const instances = (parc.data?.creches ?? []).filter((c) => c.container && c.container !== "absent" && (c.lifecycle === "CLIENT" || c.lifecycle === "DEMO"));
  const [version, setVersion] = useState(images[0] ?? "");
  const [witness, setWitness] = useState("");
  const give = useOrderAction((slugs: string[]) =>
    Promise.all(slugs.map((slug) => ordersApi.give({ type: "METTRE_A_JOUR", slug, params: { version } }))).then((all) => all[0]),
  );
  const witnessDone = orders.find((o) => o.type === "METTRE_A_JOUR" && o.params.version === version && o.status === "FAIT");
  const others = instances.filter((c) => c.version !== version && c.slug !== witnessDone?.slug);
  return (
    <FormCard
      title="Mise à jour"
      intro="Une crèche témoin d’abord ; une fois l’ordre « Fait » et la crèche vérifiée, les autres. L’image doit être construite sur le serveur (creche.sh construire)."
      onSubmit={(e) => {
        e.preventDefault();
        give.mutate([witness], { onSuccess: () => setWitness("") });
      }}
    >
      {images.length === 0 ? (
        <p className="m-0 text-muted-foreground">Aucune version connue : le rapport de l’hôte ne liste pas encore les images construites.</p>
      ) : (
        <div className="flex flex-wrap items-end gap-3">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="o-version">Version</Label>
            <select id="o-version" className="h-9 rounded-md border border-input bg-card px-3" value={version} onChange={(e) => setVersion(e.target.value)}>
              {images.map((i) => <option key={i}>{i}</option>)}
            </select>
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="o-temoin">Crèche témoin</Label>
            <select id="o-temoin" required className="h-9 rounded-md border border-input bg-card px-3" value={witness} onChange={(e) => setWitness(e.target.value)}>
              <option value="">Choisir…</option>
              {instances.filter((c) => c.version !== version).map((c) => <option key={c.slug} value={c.slug}>{c.name} ({c.version ?? "?"})</option>)}
            </select>
          </div>
          <Button type="submit" disabled={give.isPending || !witness}>Mettre à jour la témoin</Button>
        </div>
      )}
      {witnessDone && others.length > 0 && (
        <div className={cn("flex flex-wrap items-center gap-3 rounded-md bg-success-100 px-3 py-2 text-success-700")}>
          <span>Témoin à jour ({witnessDone.crecheName ?? witnessDone.slug}). Vérifiez-la, puis :</span>
          <Button size="sm" disabled={give.isPending} onClick={() => give.mutate(others.map((c) => c.slug), { onSuccess: onDone })}>
            Mettre à jour les {others.length} autres
          </Button>
        </div>
      )}
      {give.isError && <p role="alert" className="m-0 text-danger-700">{errorMessage(give.error)}</p>}
      <Button type="button" variant="ghost" className="self-start" onClick={onDone}>Fermer</Button>
    </FormCard>
  );
}
