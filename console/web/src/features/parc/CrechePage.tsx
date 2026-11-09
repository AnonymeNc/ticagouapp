import { useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { Link, useParams } from "react-router";
import { Button, buttonVariants } from "@/components/ui/button";
import { ContractCard, CrecheInvoices } from "@/features/billing/ContractCard";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { HealthDot, Pill } from "@/components/ui/pill";
import { errorMessage } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { cn } from "@/lib/utils";
import { useAddNote, useCreche, useUpdateFiche, type ClientKind, type Detail, type FicheForm, type Lifecycle, type Point } from "./api";
import {
  ago,
  backupPill,
  containerLabels,
  diskTone,
  formatDay,
  gigabytes,
  kindLabels,
  lifecycleLabels,
  moduleLabels,
  subscriptionPill,
} from "./labels";

const tabs = [
  { id: "sante", label: "Santé" },
  { id: "abonnement", label: "Abonnement" },
  { id: "usage", label: "Usage" },
  { id: "factures", label: "Factures" },
  { id: "contacts", label: "Contacts et notes" },
  { id: "journal", label: "Journal" },
] as const;
type Tab = (typeof tabs)[number]["id"];

/** Fiche crèche (maquette « Fiche crèche ») : santé, abonnement, usage, contacts et notes, journal. */
export function CrechePage() {
  const { slug = "" } = useParams();
  // Une autre crèche (recherche) : la fiche repart de l'onglet Santé.
  return <Fiche key={slug} slug={slug} />;
}

function Fiche({ slug }: { slug: string }) {
  const query = useCreche(slug);
  const [tab, setTab] = useState<Tab>("sante");
  const [noteKind, setNoteKind] = useState<"NOTE" | "APPEL">("NOTE");

  if (query.isPending) {
    return <p className="text-muted-foreground">Chargement de la fiche…</p>;
  }
  if (query.isError) {
    return (
      <>
        <Breadcrumb name={slug} />
        <p role="alert" className="text-danger-700">
          {errorMessage(query.error)}
        </p>
      </>
    );
  }
  const detail = query.data;
  const { row } = detail;
  const subscription = subscriptionPill(row);

  return (
    <>
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <Breadcrumb name={row.name} />
          <h1 className="m-0 mt-1 flex items-center gap-3 font-display text-display">
            <HealthDot level={row.health.level} className="size-3.5" />
            {row.name}
          </h1>
          <p className="mt-1 text-muted-foreground">
            {row.city && `${row.city} · `}
            <code className="rounded bg-muted px-1.5 font-mono text-small">{row.slug}</code>
            {detail.domain && ` · ${detail.domain}`}
            {detail.clientSince && ` · client depuis le ${formatDay(detail.clientSince)}`}
          </p>
          <div className="mt-2.5 flex flex-wrap gap-2">
            {row.alerts.map((a) => (
              <Pill key={a.id} tone="bad">
                {a.label}
              </Pill>
            ))}
            {row.lifecycle === "CLIENT" && subscription.tone !== "ok" && subscription.label !== "—" && (
              <Pill tone={subscription.tone}>
                {subscription.label}
                {subscription.sub ? ` · ${subscription.sub}` : ""}
              </Pill>
            )}
            {row.lifecycle !== "CLIENT" && <Pill tone="neutral">{lifecycleLabels[row.lifecycle]}</Pill>}
            {row.health.level === "WARN" &&
              row.health.reasons.map((reason) => (
                <Pill key={reason} tone="warn">
                  {reason}
                </Pill>
              ))}
            {row.health.level === "OFF" && row.lifecycle === "CLIENT" && <Pill tone="neutral">{row.health.reasons[0]}</Pill>}
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          <Link to={`/factures?nouvelle=${row.slug}`} className={buttonVariants({ variant: "outline" })}>
            Facture
          </Link>
          <Button
            variant="outline"
            onClick={() => {
              setNoteKind("APPEL");
              setTab("contacts");
            }}
          >
            Noter un appel
          </Button>
          <Button variant="destructive" disabled title="Avec les ordres aux instances (phase 5)">
            Suspendre…
          </Button>
        </div>
      </div>

      <div role="tablist" aria-label="Fiche de la crèche" className="flex gap-1 overflow-x-auto border-b border-border">
        {tabs.map((t) => (
          <button
            key={t.id}
            role="tab"
            type="button"
            aria-selected={t.id === tab}
            onClick={() => setTab(t.id)}
            className={cn(
              "border-b-2 border-transparent px-3.5 py-2.5 font-semibold whitespace-nowrap text-muted-foreground",
              t.id === tab && "border-lagon-600 text-lagon-600",
            )}
          >
            {t.label}
          </button>
        ))}
      </div>

      <div role="tabpanel">
        {tab === "sante" && <HealthTab detail={detail} />}
        {tab === "abonnement" && (
          <div className="flex flex-col gap-4">
            <ContractCard slug={row.slug} places={row.places} />
            <SubscriptionTab detail={detail} />
          </div>
        )}
        {tab === "usage" && <UsageTab detail={detail} />}
        {tab === "factures" && <CrecheInvoices slug={row.slug} />}
        {tab === "contacts" && <ContactsTab key={detail.row.slug} detail={detail} noteKind={noteKind} onNoteKind={setNoteKind} />}
        {tab === "journal" && <JournalTab detail={detail} />}
      </div>
    </>
  );
}

function Breadcrumb({ name }: { name: string }) {
  return (
    <p className="m-0 text-small text-muted-foreground">
      <Link to="/parc" className="text-lagon-600 hover:underline">
        Parc des crèches
      </Link>{" "}
      › {name}
    </p>
  );
}

function Card({ title, aside, children, className }: { title?: string; aside?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={cn("rounded-lg border border-border-subtle bg-card p-5 shadow-1", className)}>
      {title && (
        <div className="mb-3 flex items-baseline justify-between gap-3">
          <h2 className="m-0 font-display text-title-3">{title}</h2>
          {aside && <span className="text-small text-muted-foreground">{aside}</span>}
        </div>
      )}
      {children}
    </section>
  );
}

function Facts({ items }: { items: [string, ReactNode][] }) {
  return (
    <dl className="m-0 grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1.5">
      {items.map(([label, value]) => (
        <div key={label} className="contents">
          <dt className="text-muted-foreground">{label}</dt>
          <dd className="m-0 font-semibold">{value ?? "—"}</dd>
        </div>
      ))}
    </dl>
  );
}

function Meter({ label, percent, hint }: { label: string; percent: number | null; hint: string }) {
  const tone = diskTone(percent);
  return (
    <div className="mt-3.5">
      <div className="flex items-center">
        <span>{label}</span>
        <span className="ml-auto font-semibold">{percent != null ? `${percent} %` : "—"}</span>
      </div>
      <div className="mt-1.5 h-2 overflow-hidden rounded-full bg-muted" role="meter" aria-label={label} aria-valuenow={percent ?? undefined} aria-valuemin={0} aria-valuemax={100}>
        <span
          className={cn("block h-full rounded-full", tone === "bad" ? "bg-danger-600" : tone === "warn" ? "bg-warning-500" : "bg-lagon-600")}
          style={{ width: `${percent ?? 0}%` }}
        />
      </div>
      <p className="m-0 mt-1 text-small text-muted-foreground">{hint}</p>
    </div>
  );
}

function percent(free?: number | null, total?: number | null): number | null {
  return free == null || total == null || total <= 0 ? null : Math.round((100 * (total - free)) / total);
}

function HealthTab({ detail }: { detail: Detail }) {
  const { row, status } = detail;
  const backup = backupPill(row);
  const disk = status?.disk;
  const lateHours = row.backupAt ? Math.floor((Date.now() - new Date(row.backupAt).getTime()) / 3_600_000) : null;
  return (
    <div className="grid gap-4 lg:grid-cols-3">
      <Card title="Serveur">
        <Facts
          items={[
            ["Dernier contact", row.lastContactAt ? `${formatDateTime(new Date(row.lastContactAt))} (${ago(row.lastContactAt)})` : "jamais"],
            ["Version", row.version],
            ["Conteneur", row.container ? <Pill tone={row.container === "healthy" ? "ok" : row.container === "unhealthy" ? "bad" : "neutral"}>{containerLabels[row.container] ?? row.container}</Pill> : "—"],
            ["Base", status?.database === "UP" ? "en ligne" : (status?.database ?? "—")],
            ["Rapport de l’hôte", detail.hostSeenAt ? ago(detail.hostSeenAt) : "jamais"],
          ]}
        />
        <Meter label="Disque des données" percent={percent(disk?.dataFreeBytes, disk?.dataTotalBytes)} hint={`${gigabytes(disk?.dataFreeBytes)} libres sur ${gigabytes(disk?.dataTotalBytes)}`} />
        {disk?.backupTotalBytes != null && (
          <Meter label="Disque des sauvegardes" percent={percent(disk.backupFreeBytes, disk.backupTotalBytes)} hint={`${gigabytes(disk.backupFreeBytes)} libres sur ${gigabytes(disk.backupTotalBytes)}`} />
        )}
      </Card>
      <Card title="Sauvegardes">
        <Facts
          items={[
            ["Dernière réussite", row.backupAt ? <span className={cn(row.backupStale && "text-danger-700")}>{formatDateTime(new Date(row.backupAt))}</span> : "aucune"],
            ["État", <Pill tone={backup.tone}>{row.backupStale ? `Retard${lateHours != null ? ` ${lateHours} h` : ""}` : row.backupAt ? "À jour" : "—"}</Pill>],
            ["Automatiques", status?.backup ? (status.backup.enabled === false ? "désactivées" : status.backup.toolsAvailable === false ? "outils absents" : "actives") : "—"],
            ["Test de restauration", status?.backup?.lastTestOn ? `${formatDay(status.backup.lastTestOn)}, ${status.backup.lastTestOk ? "réussi" : "échoué"}` : "jamais"],
            ["Copie hors site", <span className="font-normal text-muted-foreground">non prévue (un seul hébergeur)</span>],
          ]}
        />
      </Card>
      <Card title="Abonnement de l’instance">
        <Facts
          items={[
            ["Formule", row.plan],
            ["Modules", row.modules.length ? row.modules.map((m) => moduleLabels[m] ?? m).join(", ") : "base seule"],
            ["Échéance", row.licenseState === "EVALUATION" ? `évaluation jusqu’au ${formatDay(row.deadline)}` : formatDay(row.validUntil)],
            ...(row.deadline && row.licenseState !== "EVALUATION" ? ([["Lecture seule le", formatDay(row.deadline)]] as [string, ReactNode][]) : []),
            ["PC / tablettes", `${row.desktops ?? "?"}/${row.desktopLimit ?? "?"} · ${row.tablets ?? "?"}/${row.tabletLimit ?? "?"}`],
            ["Portail parents", status?.portal?.state === "ACTIF" ? (status.portal.failures ? `actif, ${status.portal.failures} échec(s)` : "actif") : "désactivé"],
          ]}
        />
      </Card>
      <Card title="Usage (sans donnée personnelle)">
        <UsageFacts detail={detail} />
      </Card>
      <Card title="Historique des alertes" aside="90 jours" className="lg:col-span-2">
        {detail.history.length === 0 ? (
          <p className="m-0 text-muted-foreground">Aucune alerte depuis 90 jours.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full border-collapse">
              <thead className="text-left text-label text-muted-foreground">
                <tr>
                  <th className="px-2 py-2">Alerte</th>
                  <th className="px-2 py-2">Ouverte</th>
                  <th className="px-2 py-2">Fermée</th>
                  <th className="px-2 py-2">Détail</th>
                </tr>
              </thead>
              <tbody>
                {detail.history.map((a) => (
                  <tr key={a.id} className="border-t border-border-subtle align-top">
                    <td className="px-2 py-2">
                      <Pill tone={a.closedAt ? "neutral" : "bad"}>{a.label}</Pill>
                    </td>
                    <td className="px-2 py-2 whitespace-nowrap tabular-nums">{formatDateTime(new Date(a.openedAt))}</td>
                    <td className="px-2 py-2 whitespace-nowrap tabular-nums">{a.closedAt ? formatDateTime(new Date(a.closedAt)) : "en cours"}</td>
                    <td className="px-2 py-2">{a.detail}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      <Card title="Disque des données" aside="90 jours, un point par jour" className="lg:col-span-3">
        <Bars points={detail.points} value={(p) => p.diskUsedPercent} max={100} unit=" %" label="Occupation du disque des données" />
      </Card>
    </div>
  );
}

function UsageFacts({ detail }: { detail: Detail }) {
  const usage = detail.status?.usage;
  if (!usage) {
    return <p className="m-0 text-muted-foreground">Pas encore reçu : l’instance l’envoie à partir de la version qui le connaît.</p>;
  }
  return (
    <Facts
      items={[
        ["Enfants actifs", `${usage.activeChildren ?? "?"}${detail.row.places != null ? ` / ${detail.row.places} places` : ""}`],
        ["Pointages sur 7 jours", usage.attendances7d],
        ["Utilisateurs actifs (30 j)", usage.activeUsers30d],
        ["Dernière connexion direction", formatDay(usage.lastDirectionLoginOn)],
        ["Factures familles (mois dernier)", usage.familyInvoicesLastMonth],
      ]}
    />
  );
}

/** Courbe en barres, un point par jour (maquette : barres « Facturé et encaissé »). */
function Bars({ points, value, max, unit, label }: { points: Point[]; value: (p: Point) => number | null; max?: number; unit: string; label: string }) {
  const values = points.map(value);
  const top = max ?? Math.max(1, ...values.map((v) => v ?? 0));
  if (values.every((v) => v == null)) {
    return <p className="m-0 text-muted-foreground">Pas encore de mesure.</p>;
  }
  return (
    <div>
      <div role="img" aria-label={`${label} : ${values.filter((v) => v != null).length} jours`} className="flex h-32 items-end gap-[2px]">
        {points.map((p, i) => (
          <span
            key={p.day}
            title={`${formatDay(p.day)} : ${values[i] ?? "—"}${values[i] != null ? unit : ""}`}
            className="min-w-[3px] flex-1 rounded-t-[2px] bg-lagon-600"
            style={{ height: `${Math.max(2, ((values[i] ?? 0) / top) * 100)}%`, opacity: values[i] == null ? 0.15 : 1 }}
          />
        ))}
      </div>
      <div className="mt-1.5 flex justify-between text-small text-muted-foreground">
        <span>{formatDay(points[0]?.day)}</span>
        <span>{formatDay(points[points.length - 1]?.day)}</span>
      </div>
    </div>
  );
}

function SubscriptionTab({ detail }: { detail: Detail }) {
  const { row, subscription } = detail;
  const modules = row.modules.map((m) => moduleLabels[m] ?? m);
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Card title="Contrat enregistré dans l’instance">
        {subscription ? (
          <Facts
            items={[
              ["Numéro", subscription.number],
              ["Formule", subscription.plan],
              ["Places", subscription.places],
              ["PC / tablettes permis", `${subscription.desktops ?? "?"} / ${subscription.tablets ?? "?"}`],
              ["Échéance", formatDay(subscription.validUntil)],
              ["Modules", modules.length ? modules.join(", ") : "base seule"],
            ]}
          />
        ) : (
          <p className="m-0 text-muted-foreground">
            {row.licenseState === "EVALUATION" ? "En évaluation : pas encore de contrat." : "Pas de contrat lu par l’hôte (rapport absent ou instance sans abonnement)."}
          </p>
        )}
      </Card>
      <Card title="Licence vue par l’instance">
        <Facts
          items={[
            ["État", <Pill tone={subscriptionPill(row).tone}>{subscriptionPill(row).label}</Pill>],
            ["Valable jusqu’au", formatDay(row.validUntil)],
            ["Lecture seule le", formatDay(row.deadline)],
            ["PC utilisés", `${row.desktops ?? "?"} sur ${row.desktopLimit ?? "?"}`],
            ["Tablettes appairées", `${row.tablets ?? "?"} sur ${row.tabletLimit ?? "?"}`],
          ]}
        />
        <p className="m-0 mt-4 text-small text-muted-foreground">
          Pour renouveler ou changer la formule : <span className="font-mono">./creche.sh abonnement {row.slug} …</span>. La console
          l’enverra elle-même avec les ordres (phase 5).
        </p>
      </Card>
    </div>
  );
}

function UsageTab({ detail }: { detail: Detail }) {
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Card title="Usage (sans donnée personnelle)">
        <UsageFacts detail={detail} />
      </Card>
      <Card title="Enfants actifs" aside="90 jours">
        <Bars points={detail.points} value={(p) => p.activeChildren} unit="" label="Enfants actifs par jour" />
      </Card>
      <Card title="Pointages sur 7 jours glissants" aside="90 jours">
        <Bars points={detail.points} value={(p) => p.attendances7d} unit="" label="Pointages sur 7 jours" />
      </Card>
      <Card title="Utilisateurs actifs sur 30 jours" aside="90 jours">
        <Bars points={detail.points} value={(p) => p.activeUsers30d} unit="" label="Utilisateurs actifs" />
      </Card>
    </div>
  );
}

function ContactsTab({ detail, noteKind, onNoteKind }: { detail: Detail; noteKind: "NOTE" | "APPEL"; onNoteKind: (k: "NOTE" | "APPEL") => void }) {
  return (
    <div className="grid gap-4 lg:grid-cols-[3fr_2fr]">
      <FicheEditor detail={detail} />
      <Notes detail={detail} kind={noteKind} onKind={onNoteKind} />
    </div>
  );
}

function toForm(detail: Detail): FicheForm {
  const { id: _id, ...client } = detail.client;
  return { name: detail.row.name, city: detail.row.city, lifecycle: detail.row.lifecycle, clientSince: detail.clientSince, client };
}

function FicheEditor({ detail }: { detail: Detail }) {
  const update = useUpdateFiche(detail.row.slug);
  const [form, setForm] = useState<FicheForm>(() => toForm(detail));
  const [saved, setSaved] = useState(false);
  const set = <K extends keyof FicheForm>(key: K, value: FicheForm[K]) => {
    setSaved(false);
    setForm((f) => ({ ...f, [key]: value }));
  };
  const setClient = <K extends keyof FicheForm["client"]>(key: K, value: FicheForm["client"][K]) => {
    setSaved(false);
    setForm((f) => ({ ...f, client: { ...f.client, [key]: value } }));
  };
  const text = (value: string) => (value.trim() === "" ? null : value);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    update.mutate(form, { onSuccess: (d) => {
      setForm(toForm(d));
      setSaved(true);
    } });
  };

  const field = (id: string, label: string, input: ReactNode, hint?: string) => (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={id}>{label}</Label>
      {input}
      {hint && <span className="text-small text-muted-foreground">{hint}</span>}
    </div>
  );
  const select = "h-9 rounded-md border border-input bg-card px-3";

  return (
    <Card title="Client et contacts">
      <form onSubmit={submit} className="flex flex-col gap-4">
        <div className="grid gap-4 md:grid-cols-2">
          {field("f-nom", "Nom de la crèche", <Input id="f-nom" value={form.name} required onChange={(e) => set("name", e.target.value)} />)}
          {field("f-commune", "Commune", <Input id="f-commune" value={form.city ?? ""} onChange={(e) => set("city", text(e.target.value))} />)}
          {field(
            "f-statut",
            "Statut",
            <select id="f-statut" className={select} value={form.lifecycle} onChange={(e) => set("lifecycle", e.target.value as Lifecycle)}>
              {(Object.keys(lifecycleLabels) as Lifecycle[]).map((k) => (
                <option key={k} value={k}>
                  {lifecycleLabels[k]}
                </option>
              ))}
            </select>,
          )}
          {field("f-depuis", "Client depuis le", <Input id="f-depuis" type="date" value={form.clientSince ?? ""} onChange={(e) => set("clientSince", text(e.target.value))} />)}
        </div>
        <hr className="m-0 border-border-subtle" />
        <div className="grid gap-4 md:grid-cols-2">
          {field("f-raison", "Raison sociale", <Input id="f-raison" value={form.client.name} required onChange={(e) => setClient("name", e.target.value)} />)}
          {field(
            "f-type",
            "Type",
            <select
              id="f-type"
              className={select}
              value={form.client.kind}
              onChange={(e) => {
                const kind = e.target.value as ClientKind;
                setClient("kind", kind);
                if (kind === "COLLECTIVITE" && form.client.paymentDays === 30) {
                  setClient("paymentDays", 45);
                }
              }}
            >
              {(Object.keys(kindLabels) as ClientKind[]).map((k) => (
                <option key={k} value={k}>
                  {kindLabels[k]}
                </option>
              ))}
            </select>,
          )}
          {field("f-ridet", "RIDET", <Input id="f-ridet" value={form.client.ridet ?? ""} onChange={(e) => setClient("ridet", text(e.target.value))} />)}
          {field(
            "f-delai",
            "Délai de paiement (jours)",
            <Input id="f-delai" type="number" min={0} max={120} value={form.client.paymentDays} onChange={(e) => setClient("paymentDays", Number(e.target.value))} />,
            "30 jours ; collectivité : 45 jours (mandat administratif).",
          )}
          <div className="md:col-span-2">
            {field("f-adresse", "Adresse", <Input id="f-adresse" value={form.client.address ?? ""} onChange={(e) => setClient("address", text(e.target.value))} />)}
          </div>
          {field("f-direction", "Direction", <Input id="f-direction" value={form.client.directorName ?? ""} onChange={(e) => setClient("directorName", text(e.target.value))} />)}
          {field("f-tel", "Téléphone", <Input id="f-tel" value={form.client.directorPhone ?? ""} onChange={(e) => setClient("directorPhone", text(e.target.value))} />)}
          {field("f-email-dir", "E-mail de la direction", <Input id="f-email-dir" type="email" value={form.client.directorEmail ?? ""} onChange={(e) => setClient("directorEmail", text(e.target.value))} />)}
          {field("f-email-fact", "E-mail de facturation", <Input id="f-email-fact" type="email" value={form.client.billingEmail ?? ""} onChange={(e) => setClient("billingEmail", text(e.target.value))} />)}
          {field(
            "f-email-rel",
            "E-mail de relance",
            <Input id="f-email-rel" type="email" value={form.client.reminderEmail ?? ""} onChange={(e) => setClient("reminderEmail", text(e.target.value))} />,
            "Vide : l’e-mail de facturation.",
          )}
        </div>
        {update.isError && (
          <p role="alert" className="m-0 text-danger-700">
            {errorMessage(update.error)}
          </p>
        )}
        <div className="flex items-center gap-3">
          <Button type="submit" disabled={update.isPending}>
            Enregistrer
          </Button>
          {saved && <span role="status" className="text-success-700">Fiche enregistrée.</span>}
        </div>
      </form>
    </Card>
  );
}

function Notes({ detail, kind, onKind }: { detail: Detail; kind: "NOTE" | "APPEL"; onKind: (k: "NOTE" | "APPEL") => void }) {
  const add = useAddNote(detail.row.slug);
  const [text, setText] = useState("");
  const area = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    if (kind === "APPEL") {
      area.current?.focus();
    }
  }, [kind]);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    add.mutate({ kind, text }, { onSuccess: () => setText("") });
  };

  return (
    <Card title="Notes et appels">
      <form onSubmit={submit} className="flex flex-col gap-3">
        <div role="radiogroup" aria-label="Type de note" className="flex gap-2">
          {(["NOTE", "APPEL"] as const).map((k) => (
            <button
              key={k}
              type="button"
              role="radio"
              aria-checked={kind === k}
              onClick={() => onKind(k)}
              className={cn("rounded-full border border-border px-3 py-1 text-label", kind === k && "border-lagon-700 bg-lagon-700 text-white")}
            >
              {k === "NOTE" ? "Note" : "Appel"}
            </button>
          ))}
        </div>
        <Label htmlFor="note-texte" className="sr-only">
          Texte
        </Label>
        <textarea
          id="note-texte"
          ref={area}
          value={text}
          required
          maxLength={2000}
          rows={4}
          placeholder={kind === "APPEL" ? "Qui, quoi, suite à donner…" : "Note sur la crèche…"}
          onChange={(e) => setText(e.target.value)}
          className="w-full rounded-md border border-input bg-card px-3 py-2"
        />
        {add.isError && (
          <p role="alert" className="m-0 text-danger-700">
            {errorMessage(add.error)}
          </p>
        )}
        <Button type="submit" disabled={add.isPending || text.trim() === ""} className="self-start">
          {kind === "APPEL" ? "Noter l’appel" : "Ajouter la note"}
        </Button>
      </form>
      <ul className="m-0 mt-4 flex list-none flex-col p-0">
        {detail.notes.length === 0 && <li className="text-muted-foreground">Aucune note.</li>}
        {detail.notes.map((note) => (
          <li key={note.id} className="border-t border-border-subtle py-3">
            <div className="flex items-center gap-2 text-small text-muted-foreground">
              <Pill tone={note.kind === "APPEL" ? "lagon" : "neutral"}>{note.kind === "APPEL" ? "Appel" : "Note"}</Pill>
              {formatDateTime(new Date(note.at))}
              {note.operatorLogin && ` · ${note.operatorLogin}`}
            </div>
            <p className="m-0 mt-1 whitespace-pre-line">{note.text}</p>
          </li>
        ))}
      </ul>
    </Card>
  );
}

const actionLabels: Record<string, string> = {
  CRECHE_ENREGISTREE: "Crèche apparue dans le parc",
  PROSPECT_CREE: "Prospect enregistré",
  FICHE_MODIFIEE: "Fiche modifiée",
  NOTE_AJOUTEE: "Note ajoutée",
  APPEL_NOTE: "Appel noté",
};

function JournalTab({ detail }: { detail: Detail }) {
  return (
    <Card title="Journal de la crèche">
      {detail.journal.length === 0 ? (
        <p className="m-0 text-muted-foreground">Rien pour l’instant.</p>
      ) : (
        <ul className="m-0 flex list-none flex-col p-0">
          {detail.journal.map((event) => (
            <li key={event.id} className="flex flex-wrap items-baseline gap-x-3 gap-y-1 border-t border-border-subtle py-2.5 first:border-t-0">
              <span className="text-small text-muted-foreground tabular-nums">{formatDateTime(new Date(event.at))}</span>
              <span className="font-semibold">{actionLabels[event.action] ?? event.action}</span>
              <span className="text-small text-muted-foreground">{event.operatorLogin ?? "automatique"}</span>
              {event.detail && <span className="basis-full text-small text-muted-foreground">{changes(event.detail)}</span>}
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

const fieldLabels: Record<string, string> = {
  nom: "Nom",
  commune: "Commune",
  cycle: "Statut",
  clientDepuis: "Client depuis",
  raisonSociale: "Raison sociale",
  type: "Type",
  ridet: "RIDET",
  adresse: "Adresse",
  direction: "Direction",
  emailDirection: "E-mail de la direction",
  telephone: "Téléphone",
  emailFacturation: "E-mail de facturation",
  emailRelance: "E-mail de relance",
  delaiPaiement: "Délai de paiement",
  source: "Source",
};

const sourceLabels: Record<string, string> = { hote: "rapport de l’hôte", supervision: "supervision" };

/** « RIDET : — → 1234567.001 ; Source : rapport de l’hôte ». */
function changes(detail: string): string {
  try {
    const parsed = JSON.parse(detail) as Record<string, unknown>;
    return Object.entries(parsed)
      .map(([field, value]) => {
        const label = fieldLabels[field] ?? field;
        if (value && typeof value === "object" && "avant" in value) {
          const v = value as { avant: unknown; apres: unknown };
          return `${label} : ${v.avant ?? "—"} → ${v.apres ?? "—"}`;
        }
        return `${label} : ${field === "source" ? (sourceLabels[String(value)] ?? String(value)) : String(value)}`;
      })
      .join(" ; ");
  } catch {
    return detail;
  }
}
