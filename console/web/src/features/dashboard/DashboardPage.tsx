import type { ReactNode } from "react";
import { Link } from "react-router";
import { AlertTriangle, Clock, Info, PhoneCall, RefreshCw, Server } from "lucide-react";
import { buttonVariants } from "@/components/ui/button";
import { HealthDot } from "@/components/ui/pill";
import { navItems } from "@/app/navigation";
import { useOperator } from "@/features/auth/AuthProvider";
import { useParc, type Parc, type Row } from "@/features/parc/api";
import { ago, formatDay, levelLabels } from "@/features/parc/labels";
import { errorMessage } from "@/lib/api";
import { cn } from "@/lib/utils";

type Todo = { key: string; tone: "bad" | "warn" | "info"; icon: ReactNode; title: ReactNode; hint: string; slug: string; action: string };

/**
 * Tableau de bord (maquette « Console ») : ce qui demande votre attention, tiré du parc. Les indicateurs financiers
 * (revenu récurrent, encours, délai de paiement) et le graphique « Facturé et encaissé » arrivent avec les factures.
 */
export function DashboardPage() {
  const operator = useOperator();
  const firstName = operator.displayName.split(/\s+/)[0];
  const parc = useParc();

  return (
    <>
      <div>
        <h1 className="m-0 font-display text-display">Bonjour {firstName}</h1>
        <p className="mt-1 text-muted-foreground">{parc.data ? subtitle(parc.data) : "Lecture du parc…"}</p>
      </div>
      {parc.isError && (
        <p role="alert" className="text-danger-700">
          {errorMessage(parc.error)}
        </p>
      )}
      {parc.data && <Overview parc={parc.data} />}
    </>
  );
}

function subtitle(parc: Parc): string {
  const clients = parc.creches.filter((c) => c.lifecycle === "CLIENT");
  const evaluations = clients.filter((c) => c.licenseState === "EVALUATION").length;
  if (parc.creches.length === 0) {
    return "Aucune crèche dans le parc pour l’instant.";
  }
  const base = `${clients.length} crèche${clients.length > 1 ? "s" : ""}`;
  return evaluations ? `${base}, dont ${evaluations} en évaluation.` : `${base}.`;
}

function Overview({ parc }: { parc: Parc }) {
  const { summary } = parc;
  const clients = parc.creches.filter((c) => c.lifecycle === "CLIENT").length;
  const prospects = parc.creches.filter((c) => c.lifecycle === "PROSPECT").length;
  const alertKinds = count(parc.creches.flatMap((c) => c.alerts.map((a) => a.label)));
  const todos = buildTodos(parc);

  return (
    <>
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Kpi label="Crèches suivies" value={String(clients)} hint={prospects ? `${prospects} prospect${prospects > 1 ? "s" : ""} en plus` : "aucun prospect noté"} />
        <Kpi
          label="Crèches en alerte"
          value={`${summary.bad} / ${clients}`}
          hint={alertKinds.length ? alertKinds.map(([label, n]) => `${n} ${label.toLowerCase()}`).join(", ") : "aucune alerte ouverte"}
          tone={summary.bad ? "bad" : undefined}
        />
        <Kpi label="Évaluations sous 7 jours" value={String(summary.evaluationsEnding)} hint="à appeler avant la fin" />
        <Kpi label="À renouveler sous 30 jours" value={String(summary.renewals)} hint="dont les abonnements en grâce" />
      </div>

      <div className="grid gap-4 lg:grid-cols-[3fr_2fr]">
        <section className="rounded-lg border border-border-subtle bg-card p-5 shadow-1">
          <div className="mb-2 flex items-baseline justify-between">
            <h2 className="m-0 font-display text-title-2">À faire aujourd’hui</h2>
            <span className="text-small text-muted-foreground">trié par urgence</span>
          </div>
          {todos.length === 0 ? (
            <p className="m-0 py-3 text-muted-foreground">Rien d’urgent : tout le parc est en ordre.</p>
          ) : (
            <ul className="m-0 flex list-none flex-col p-0">
              {todos.map((todo) => (
                <li key={todo.key} className="flex items-center gap-3 border-b border-border-subtle py-3 last:border-b-0">
                  <span
                    className={cn(
                      "grid size-9 shrink-0 place-items-center rounded-md",
                      todo.tone === "bad" && "bg-danger-100 text-danger-700",
                      todo.tone === "warn" && "bg-warning-100 text-warning-700",
                      todo.tone === "info" && "bg-info-100 text-info-700",
                    )}
                  >
                    {todo.icon}
                  </span>
                  <div className="min-w-0 flex-1">
                    <div>{todo.title}</div>
                    <div className="text-small text-muted-foreground">{todo.hint}</div>
                  </div>
                  <Link to={`/parc/${todo.slug}`} className={buttonVariants({ variant: "outline", size: "sm" })}>
                    {todo.action}
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </section>

        <div className="flex flex-col gap-4">
          <section className="rounded-lg border border-border-subtle bg-card p-5 shadow-1">
            <div className="mb-2 flex items-baseline justify-between">
              <h2 className="m-0 font-display text-title-2">Santé du parc</h2>
              <Link to="/parc" className={buttonVariants({ variant: "ghost", size: "sm" })}>
                Tout voir
              </Link>
            </div>
            <table className="w-full border-collapse">
              <tbody>
                {(["OK", "WARN", "BAD", "OFF"] as const).map((level) => (
                  <tr key={level} className="border-b border-border-subtle last:border-b-0">
                    <td className="py-2">
                      <span className="flex items-center gap-2">
                        <HealthDot level={level} />
                        {levelLabels[level]}
                      </span>
                    </td>
                    <td className="py-2 text-right font-bold tabular-nums">
                      {{ OK: summary.ok, WARN: summary.warn, BAD: summary.bad, OFF: summary.off }[level]}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {summary.latestVersion && (
              <p className="m-0 mt-2.5 text-small text-muted-foreground">
                Version courante {summary.latestVersion} :{" "}
                {summary.versionsBehind ? `${summary.versionsBehind} crèche${summary.versionsBehind > 1 ? "s" : ""} en retard.` : "tout le parc est à jour."}
              </p>
            )}
          </section>
          <section className="rounded-lg border border-border-subtle bg-card p-5 shadow-1">
            <h2 className="m-0 mb-2 font-display text-title-2">À venir</h2>
            <ul className="m-0 flex list-none flex-col gap-1.5 p-0 text-muted-foreground">
              {navItems
                .filter((item) => item.phase > 2)
                .map((item) => (
                  <li key={item.path} className="flex items-center gap-2">
                    <item.icon aria-hidden className="size-4" strokeWidth={1.75} />
                    <span className="flex-1">{item.label}</span>
                    <span className="text-small">phase {item.phase}</span>
                  </li>
                ))}
            </ul>
          </section>
        </div>
      </div>
    </>
  );
}

function Kpi({ label, value, hint, tone }: { label: string; value: string; hint: string; tone?: "bad" }) {
  return (
    <section className="rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <div className="text-label text-muted-foreground">{label}</div>
      <div className={cn("mt-1 font-display text-display tabular-nums", tone === "bad" && "text-danger-700")}>{value}</div>
      <div className="mt-1 text-small text-muted-foreground">{hint}</div>
    </section>
  );
}

function count(labels: string[]): [string, number][] {
  const map = new Map<string, number>();
  labels.forEach((l) => map.set(l, (map.get(l) ?? 0) + 1));
  return [...map.entries()];
}

const icon = (Icon: typeof Server) => <Icon aria-hidden className="size-[18px]" strokeWidth={1.75} />;

/** Alertes d'abord, puis fins d'évaluation, renouvellements et points à surveiller. */
export function buildTodos(parc: Parc, now: Date = new Date()): Todo[] {
  const todos: Todo[] = [];
  const clients = parc.creches.filter((c) => c.lifecycle === "CLIENT");
  for (const row of clients.filter((c) => c.health.level === "BAD")) {
    const first = row.alerts[0];
    todos.push({
      key: `alerte-${row.slug}`,
      tone: "bad",
      icon: icon(AlertTriangle),
      title: (
        <>
          <b>{row.name}</b> : {reasons(row)}
        </>
      ),
      hint: first ? `${first.detail} · ouverte ${ago(first.openedAt, now)}` : `Dernier contact ${ago(row.lastContactAt, now)}`,
      slug: row.slug,
      action: "Voir",
    });
  }
  for (const row of clients.filter((c) => c.licenseState === "EVALUATION" && c.deadline && days(c.deadline, now) <= 7)) {
    todos.push({
      key: `evaluation-${row.slug}`,
      tone: "warn",
      icon: icon(Clock),
      title: (
        <>
          <b>{row.name}</b> : évaluation finie {days(row.deadline!, now) <= 0 ? "aujourd’hui" : `dans ${days(row.deadline!, now)} jour${days(row.deadline!, now) > 1 ? "s" : ""}`}
        </>
      ),
      hint: `${usage(row)}${row.lastCallAt ? ` · dernier appel ${ago(row.lastCallAt, now)}` : " · à appeler"}`,
      slug: row.slug,
      action: "Noter un appel",
    });
  }
  for (const row of clients.filter((c) => isRenewal(c, now))) {
    todos.push({
      key: `renouvellement-${row.slug}`,
      tone: row.licenseState === "EXPIREE_GRACE" ? "bad" : "warn",
      icon: icon(RefreshCw),
      title: (
        <>
          <b>{row.name}</b> :{" "}
          {row.licenseState === "EXPIREE_GRACE" ? `abonnement échu le ${formatDay(row.validUntil)}, lecture seule le ${formatDay(row.deadline)}` : `abonnement à renouveler avant le ${formatDay(row.validUntil)}`}
        </>
      ),
      hint: `${row.plan ?? "Formule ?"} · la facture de renouvellement viendra avec la phase 3`,
      slug: row.slug,
      action: "Voir",
    });
  }
  for (const row of clients.filter((c) => c.health.level === "WARN")) {
    todos.push({
      key: `surveiller-${row.slug}`,
      tone: "info",
      icon: icon(row.health.reasons.some((r) => r.startsWith("PC") || r.startsWith("Tablettes")) ? PhoneCall : Info),
      title: (
        <>
          <b>{row.name}</b> : {reasons(row)}
        </>
      ),
      hint: `Dernier contact ${ago(row.lastContactAt, now)}`,
      slug: row.slug,
      action: "Voir",
    });
  }
  return todos;
}

function days(day: string, now: Date): number {
  const today = new Intl.DateTimeFormat("en-CA", { timeZone: "Pacific/Noumea" }).format(now);
  return Math.round((Date.parse(`${day}T00:00:00Z`) - Date.parse(`${today}T00:00:00Z`)) / 86_400_000);
}

function isRenewal(row: Row, now: Date): boolean {
  if (row.licenseState === "A_RENOUVELER" || row.licenseState === "EXPIREE_GRACE") {
    return true;
  }
  if (row.licenseState === "EVALUATION" || !row.validUntil) {
    return false;
  }
  const d = days(row.validUntil, now);
  return d >= 0 && d <= 30;
}

function usage(row: Row): string {
  if (row.activeChildren == null) {
    return "Usage pas encore reçu";
  }
  return `Usage : ${row.activeChildren} enfant${row.activeChildren > 1 ? "s" : ""} actif${row.activeChildren > 1 ? "s" : ""}`;
}

/** Motifs à la suite d'un nom : première lettre en minuscule, sigles gardés (« PC au-delà du contrat »). */
function reasons(row: Row): string {
  return row.health.reasons.map((r) => (/^[A-Z]{2}/.test(r) ? r : r.charAt(0).toLowerCase() + r.slice(1))).join(", ");
}
