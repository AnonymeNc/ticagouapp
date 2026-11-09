import { useMemo, useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router";
import { AlertTriangle, ArrowDown, ArrowUp, Download, Info, Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { HealthDot, Pill } from "@/components/ui/pill";
import { errorMessage } from "@/lib/api";
import { cn } from "@/lib/utils";
import { useCreateProspect, useParc, type Parc, type Row } from "./api";
import { ago, backupPill, diskTone, formatDay, moduleLabels, shortStamp, subscriptionPill } from "./labels";

type Filter = "toutes" | "alerte" | "surveiller" | "renouveler" | "evaluation" | "impayes" | "arretees";
type SortKey = "nom" | "contact" | "version" | "echeance" | "disque" | "sante";

const levelOrder = { BAD: 0, WARN: 1, OK: 2, OFF: 3 } as const;

/** Vue du parc (maquette « Parc des crèches ») : une ligne par crèche, triable et filtrable. */
export function ParcPage() {
  const query = useParc();
  const [filter, setFilter] = useState<Filter>("toutes");
  const [sort, setSort] = useState<{ key: SortKey; asc: boolean }>({ key: "sante", asc: true });
  const [creating, setCreating] = useState(false);
  const navigate = useNavigate();

  const rows = useMemo(() => {
    if (!query.data) {
      return [];
    }
    const filtered = query.data.creches.filter((row) => matches(row, filter));
    const value = (row: Row): string | number => {
      switch (sort.key) {
        case "nom":
          return row.name.toLowerCase();
        case "contact":
          return row.lastContactAt ?? "";
        case "version":
          return row.version ?? "";
        case "echeance":
          return row.licenseState === "EVALUATION" ? (row.deadline ?? "9") : (row.validUntil ?? "9");
        case "disque":
          return row.diskUsedPercent ?? -1;
        case "sante":
          return `${levelOrder[row.health.level]} ${row.name.toLowerCase()}`;
      }
    };
    return [...filtered].sort((a, b) => {
      const x = value(a);
      const y = value(b);
      const order = typeof x === "number" && typeof y === "number" ? x - y : String(x).localeCompare(String(y), "fr");
      return sort.asc ? order : -order;
    });
  }, [query.data, filter, sort]);

  if (query.isPending) {
    return <p className="text-muted-foreground">Chargement du parc…</p>;
  }
  if (query.isError) {
    return (
      <p role="alert" className="text-danger-700">
        {errorMessage(query.error)}
      </p>
    );
  }
  const { summary } = query.data;
  const counts: Record<Filter, number> = {
    toutes: summary.total,
    alerte: summary.bad,
    surveiller: summary.warn,
    renouveler: summary.renewals,
    evaluation: query.data.creches.filter((r) => r.licenseState === "EVALUATION").length,
    impayes: summary.unpaid,
    arretees: summary.off,
  };
  const chips: { id: Filter; label: string }[] = [
    { id: "toutes", label: "Toutes" },
    { id: "alerte", label: "En alerte" },
    { id: "surveiller", label: "À surveiller" },
    { id: "renouveler", label: "À renouveler" },
    { id: "evaluation", label: "Évaluation" },
    { id: "impayes", label: "Impayés" },
    { id: "arretees", label: "Arrêtées ou hors parc" },
  ];

  const header = (key: SortKey, label: string, numeric = false) => (
    <th scope="col" className={cn("px-3 py-2.5 text-left", numeric && "text-right")} aria-sort={sort.key === key ? (sort.asc ? "ascending" : "descending") : undefined}>
      <button
        type="button"
        className="inline-flex items-center gap-1 font-semibold whitespace-nowrap text-muted-foreground hover:text-foreground"
        onClick={() => setSort((s) => ({ key, asc: s.key === key ? !s.asc : true }))}
      >
        {label}
        {sort.key === key && (sort.asc ? <ArrowUp aria-hidden className="size-3.5" /> : <ArrowDown aria-hidden className="size-3.5" />)}
      </button>
    </th>
  );

  return (
    <>
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="m-0 font-display text-display">Parc des crèches</h1>
          <p className="mt-1 text-muted-foreground">
            État reçu toutes les 15 minutes par la supervision, rapport de l’hôte toutes les 5 minutes.
          </p>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" onClick={() => exportCsv(query.data.creches)}>
            <Download aria-hidden />
            Exporter
          </Button>
          <Button onClick={() => setCreating((c) => !c)} aria-expanded={creating}>
            <Plus aria-hidden />
            Nouvelle crèche
          </Button>
        </div>
      </div>

      {creating && <ProspectForm onDone={(slug) => (slug ? navigate(`/parc/${slug}`) : setCreating(false))} />}
      <SourceBanners parc={query.data} />

      <div role="group" aria-label="Filtrer" className="flex flex-wrap gap-2">
        {chips.map((chip) => (
          <button
            key={chip.id}
            type="button"
            aria-pressed={filter === chip.id}
            onClick={() => setFilter(chip.id)}
            className={cn(
              "rounded-full border border-border bg-card px-3 py-1 text-label",
              filter === chip.id && "border-lagon-700 bg-lagon-700 text-white",
            )}
          >
            {chip.label} · {counts[chip.id]}
          </button>
        ))}
      </div>

      <section className="rounded-lg border border-border-subtle bg-card p-2 shadow-1">
        {rows.length === 0 ? (
          <p className="m-0 p-4 text-muted-foreground">
            {summary.total === 0
              ? "Aucune crèche pour l’instant : elles apparaissent à la première lecture de la supervision ou du rapport de l’hôte."
              : "Aucune crèche dans ce filtre."}
          </p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full border-collapse text-body">
              <thead className="border-b border-border-subtle text-label">
                <tr>
                  {header("sante", "Crèche")}
                  {header("contact", "Dernier contact")}
                  {header("version", "Version")}
                  <th scope="col" className="px-3 py-2.5 text-left font-semibold text-muted-foreground">Formule</th>
                  {header("echeance", "Abonnement")}
                  <th scope="col" className="px-3 py-2.5 text-right font-semibold text-muted-foreground">PC</th>
                  <th scope="col" className="px-3 py-2.5 text-right font-semibold text-muted-foreground">Tablettes</th>
                  <th scope="col" className="px-3 py-2.5 text-left font-semibold text-muted-foreground">Sauvegarde</th>
                  {header("disque", "Disque")}
                  <th scope="col" className="px-3 py-2.5 text-right font-semibold text-muted-foreground">Solde dû</th>
                  <th scope="col" className="px-3 py-2.5 text-left font-semibold text-muted-foreground">Alertes</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <ParcRow key={row.slug} row={row} latest={summary.latestVersion} onOpen={() => navigate(`/parc/${row.slug}`)} />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
      <p className="m-0 text-small text-muted-foreground">
        Pastille : rouge = alerte ouverte ; orange = à surveiller (PC ou tablettes au-delà du contrat, version en retard, état
        manquant, facture en retard) ; vert = en ordre ; gris = arrêtée, prospect ou résiliée.
      </p>
    </>
  );
}

function matches(row: Row, filter: Filter): boolean {
  switch (filter) {
    case "toutes":
      return true;
    case "alerte":
      return row.health.level === "BAD";
    case "surveiller":
      return row.health.level === "WARN";
    case "evaluation":
      return row.licenseState === "EVALUATION";
    case "impayes":
      return row.daysLate > 0;
    case "arretees":
      return row.health.level === "OFF";
    case "renouveler": {
      if (row.lifecycle !== "CLIENT") {
        return false;
      }
      if (row.licenseState === "A_RENOUVELER" || row.licenseState === "EXPIREE_GRACE") {
        return true;
      }
      if (row.licenseState === "EVALUATION" || !row.validUntil) {
        return false;
      }
      // Même règle que le bandeau (serveur) : échéance entre aujourd'hui et dans 30 jours, heure de Nouméa.
      const today = noumeaDay(new Date());
      const limit = noumeaDay(new Date(Date.now() + 30 * 86_400_000));
      return row.validUntil >= today && row.validUntil <= limit;
    }
  }
}

/** « AAAA-MM-JJ » du jour à Nouméa. */
function noumeaDay(date: Date): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Pacific/Noumea" }).format(date);
}

function ParcRow({ row, latest, onOpen }: { row: Row; latest: string | null; onOpen: () => void }) {
  const subscription = subscriptionPill(row);
  const backup = backupPill(row);
  const over = (used: number | null, limit: number | null) => used != null && limit != null && limit > 0 && used > limit;
  const cell = "border-b border-border-subtle px-3 py-2.5 align-top";
  return (
    <tr className="cursor-pointer hover:bg-lagon-50" onClick={onOpen}>
      <td className={cell}>
        <div className="flex items-center gap-2">
          <HealthDot level={row.health.level} />
          <Link to={`/parc/${row.slug}`} className="font-semibold text-foreground hover:underline" onClick={(e) => e.stopPropagation()}>
            {row.name}
          </Link>
        </div>
        <div className="ml-[18px] text-small text-muted-foreground">
          {[row.city, row.places != null ? `${row.places} places` : null].filter(Boolean).join(" · ") || row.slug}
        </div>
      </td>
      <td className={cn(cell, "whitespace-nowrap tabular-nums")}>
        {row.lastContactAt ? shortStamp(row.lastContactAt) : "—"}
        {row.lastContactAt && <div className="text-small text-muted-foreground">{ago(row.lastContactAt)}</div>}
      </td>
      <td className={cn(cell, "tabular-nums", latest && row.version && row.version !== latest && "font-semibold text-warning-700")}>
        {row.version ?? "—"}
      </td>
      <td className={cell}>
        {row.plan ?? "—"}
        {extras(row).length > 0 && <div className="text-small text-muted-foreground">+ {extras(row).join(", ")}</div>}
      </td>
      <td className={cell}>
        <Pill tone={subscription.tone}>{subscription.label}</Pill>
        {subscription.sub && <div className="text-small text-muted-foreground">{subscription.sub}</div>}
      </td>
      <td className={cn(cell, "text-right tabular-nums", over(row.desktops, row.desktopLimit) && "font-bold text-warning-700")}>
        {ratio(row.desktops, row.desktopLimit)}
      </td>
      <td className={cn(cell, "text-right tabular-nums", over(row.tablets, row.tabletLimit) && "font-bold text-warning-700")}>
        {ratio(row.tablets, row.tabletLimit)}
      </td>
      <td className={cell}>
        <Pill tone={backup.tone}>{backup.label}</Pill>
      </td>
      <td className={cn(cell, "tabular-nums", diskTone(row.diskUsedPercent) === "bad" && "font-bold text-danger-700")}>
        {row.diskUsedPercent != null ? `${row.diskUsedPercent} %` : "—"}
      </td>
      <td className={cn(cell, "text-right tabular-nums")}>
        {row.balanceDue > 0 ? <b>{row.balanceDue.toLocaleString("fr-FR")} F</b> : row.lifecycle === "CLIENT" ? "0 F" : "—"}
        {row.daysLate > 0 && <div className="text-small text-danger-700">J+{row.daysLate}</div>}
      </td>
      <td className={cell}>
        {row.alerts.length > 0 ? (
          <div className="flex flex-wrap gap-1">
            {row.alerts.map((a) => (
              <Pill key={a.id} tone="bad">
                {a.label}
              </Pill>
            ))}
          </div>
        ) : row.health.level === "WARN" ? (
          <Pill tone="warn">{row.health.reasons[0]}</Pill>
        ) : (
          "—"
        )}
      </td>
    </tr>
  );
}

/** Modules ajoutés à la formule (au-delà de Gestion : la maquette écrit « Gestion + Repas »). */
function extras(row: Row): string[] {
  const included: Record<string, string[]> = {
    Essentiel: [],
    Gestion: ["INSCRIPTIONS", "FACTURATION", "PORTAIL_PARENTS"],
    Complet: Object.keys(moduleLabels),
  };
  const base = row.plan ? included[row.plan] : undefined;
  if (!base) {
    return [];
  }
  return row.modules.filter((m) => !base.includes(m)).map((m) => moduleLabels[m] ?? m);
}

function ratio(used: number | null, limit: number | null): string {
  if (used == null && limit == null) {
    return "—";
  }
  return `${used ?? "?"}/${limit ?? "?"}`;
}

function SourceBanners({ parc }: { parc: Parc }) {
  const { sources } = parc;
  return (
    <>
      {sources.supervisionError && (
        <div role="status" className="flex gap-3 rounded-md bg-warning-100 px-4 py-3 text-warning-700">
          <AlertTriangle aria-hidden className="mt-0.5 size-5 shrink-0" />
          <span>
            Supervision non lue {sources.supervisionAttemptAt ? `à ${shortStamp(sources.supervisionAttemptAt)}` : ""} : {sources.supervisionError}
            {sources.supervisionAt && ` Dernière lecture réussie ${ago(sources.supervisionAt)}.`}
          </span>
        </div>
      )}
      {sources.hostError && (
        <div role="status" className="flex gap-3 rounded-md bg-warning-100 px-4 py-3 text-warning-700">
          <AlertTriangle aria-hidden className="mt-0.5 size-5 shrink-0" />
          <span>Rapport de l’hôte : {sources.hostError}</span>
        </div>
      )}
      {sources.rejectedSites.length > 0 && (
        <div role="status" className="flex gap-3 rounded-md bg-warning-100 px-4 py-3 text-warning-700">
          <AlertTriangle aria-hidden className="mt-0.5 size-5 shrink-0" />
          <span>
            État de supervision illisible, ignoré à la dernière lecture : {sources.rejectedSites.join(", ")}. Le reste du parc
            est à jour ; le détail est dans les journaux du conteneur (<span className="font-mono">docker logs</span>).
          </span>
        </div>
      )}
      {sources.unlinkedSites.length > 0 && (
        <div role="status" className="flex gap-3 rounded-md bg-info-100 px-4 py-3 text-info-700">
          <Info aria-hidden className="mt-0.5 size-5 shrink-0" />
          <span>
            {sources.unlinkedSites.length === 1 ? "Un site supervisé n’a" : `${sources.unlinkedSites.length} sites supervisés n’ont`} pas
            d’identifiant de crèche hébergée et n’apparai{sources.unlinkedSites.length === 1 ? "t" : "ssent"} pas ici :{" "}
            {sources.unlinkedSites.join(", ")}.
          </span>
        </div>
      )}
    </>
  );
}

function ProspectForm({ onDone }: { onDone: (slug: string | null) => void }) {
  const create = useCreateProspect();
  const [name, setName] = useState("");
  const [slug, setSlug] = useState("");
  const [slugEdited, setSlugEdited] = useState(false);
  const [city, setCity] = useState("");

  const submit = (e: FormEvent) => {
    e.preventDefault();
    create.mutate({ slug, name, city: city || null }, { onSuccess: (detail) => onDone(detail.row.slug) });
  };

  return (
    <form onSubmit={submit} className="flex flex-col gap-4 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <div>
        <h2 className="m-0 font-display text-title-2">Nouvelle crèche</h2>
        <p className="m-0 mt-1 text-small text-muted-foreground">
          Un prospect, sans instance. L’instance se crée avec <span className="font-mono">creche.sh creer</span> et le même
          identifiant ; elle se rattache alors toute seule.
        </p>
      </div>
      <div className="grid gap-4 md:grid-cols-3">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="p-nom">Nom</Label>
          <Input
            id="p-nom"
            value={name}
            required
            onChange={(e) => {
              setName(e.target.value);
              if (!slugEdited) {
                setSlug(slugify(e.target.value));
              }
            }}
          />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="p-slug">Identifiant</Label>
          <Input
            id="p-slug"
            value={slug}
            required
            pattern="[a-z0-9][a-z0-9\-]{1,40}"
            onChange={(e) => {
              setSlug(e.target.value);
              setSlugEdited(true);
            }}
          />
          <span className="text-small text-muted-foreground">Minuscules, chiffres, tirets : {slug || "…"}.{"<domaine>"}</span>
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="p-ville">Commune</Label>
          <Input id="p-ville" value={city} onChange={(e) => setCity(e.target.value)} />
        </div>
      </div>
      {create.isError && (
        <p role="alert" className="m-0 text-danger-700">
          {errorMessage(create.error)}
        </p>
      )}
      <div className="flex gap-2">
        <Button type="submit" disabled={create.isPending}>
          Enregistrer le prospect
        </Button>
        <Button type="button" variant="ghost" onClick={() => onDone(null)}>
          Annuler
        </Button>
      </div>
    </form>
  );
}

export function slugify(name: string): string {
  return name
    .normalize("NFD")
    .replace(/\p{M}/gu, "")
    .toLowerCase()
    .replace(/^(micro-?creche|creche)\s+/, "")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 41);
}

/** Export du parc pour un tableur (séparateur « ; », UTF-8 avec BOM). */
function exportCsv(rows: Row[]) {
  const head = ["Crèche", "Identifiant", "Commune", "Santé", "Motifs", "Dernier contact", "Version", "Formule", "Modules", "Licence",
    "Échéance", "Places", "Enfants actifs", "PC", "PC permis", "Tablettes", "Tablettes permises", "Dernière sauvegarde", "Disque (%)", "Solde dû (F)", "Retard (j)", "Alertes"];
  const lines = rows.map((r) => [r.name, r.slug, r.city, r.health.level, r.health.reasons.join(" / "), r.lastContactAt, r.version, r.plan,
    r.modules.join(" "), r.licenseState, formatDay(r.validUntil), r.places, r.activeChildren, r.desktops, r.desktopLimit, r.tablets,
    r.tabletLimit, r.backupAt, r.diskUsedPercent, r.balanceDue, r.daysLate || null, r.alerts.map((a) => a.label).join(" / ")]);
  const csv = [head, ...lines]
    .map((line) => line.map((v) => (v == null ? "" : `"${String(v).replace(/"/g, '""')}"`)).join(";"))
    .join("\r\n");
  const url = URL.createObjectURL(new Blob(["﻿" + csv], { type: "text/csv;charset=utf-8" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = `parc-ticagou-${new Date().toISOString().slice(0, 10)}.csv`;
  link.click();
  URL.revokeObjectURL(url);
}
