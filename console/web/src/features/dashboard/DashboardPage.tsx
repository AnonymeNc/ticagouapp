import type { ReactNode } from "react";
import { Link } from "react-router";
import { AlertTriangle, Clock, Info, Mail, PhoneCall, Plus, Receipt, RefreshCw, Send, Server, SquareTerminal } from "lucide-react";
import { buttonVariants } from "@/components/ui/button";
import { HealthDot } from "@/components/ui/pill";
import { useOperator } from "@/features/auth/AuthProvider";
import { useBoard, type Board } from "@/features/billing/api";
import { monthShort, xpf } from "@/features/billing/labels";
import { typeLabels, useOrders, type OrdersBoard } from "@/features/orders/api";
import { useParc, type Parc, type Row } from "@/features/parc/api";
import { ago, formatDay, levelLabels } from "@/features/parc/labels";
import { errorMessage } from "@/lib/api";
import { cn } from "@/lib/utils";

type Todo = { key: string; tone: "bad" | "warn" | "info"; icon: ReactNode; title: ReactNode; hint: string; to: string; action: string };

/**
 * Tableau de bord (maquette « Console ») : revenu récurrent, encours, alertes, délai de paiement ; ce qui demande votre
 * attention aujourd'hui (envois, brouillons, retards, alertes, évaluations, renouvellements) ; santé du parc et six mois
 * de facturé et d'encaissé.
 */
export function DashboardPage() {
  const operator = useOperator();
  const firstName = operator.displayName.split(/\s+/)[0];
  const parc = useParc();
  const board = useBoard();
  const orders = useOrders();

  return (
    <>
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="m-0 font-display text-display">Bonjour {firstName}</h1>
          <p className="mt-1 text-muted-foreground">{parc.data ? subtitle(parc.data, board.data) : "Lecture du parc…"}</p>
        </div>
        <div className="flex gap-2">
          <Link to="/factures?nouvelle=" className={buttonVariants({ variant: "outline" })}>
            <Plus aria-hidden />
            Facture
          </Link>
          <Link to="/envoi" className={buttonVariants({ variant: "default" })}>
            <Send aria-hidden />
            Boîte d’envoi ({board.data?.pendingMessages ?? 0})
          </Link>
        </div>
      </div>
      {(parc.isError || board.isError) && (
        <p role="alert" className="text-danger-700">
          {errorMessage(parc.error ?? board.error)}
        </p>
      )}
      {parc.data && <Overview parc={parc.data} board={board.data} orders={orders.data} />}
    </>
  );
}

function subtitle(parc: Parc, board?: Board): string {
  const clients = parc.creches.filter((c) => c.lifecycle === "CLIENT");
  const evaluations = clients.filter((c) => c.licenseState === "EVALUATION").length;
  if (parc.creches.length === 0) {
    return "Aucune crèche dans le parc pour l’instant.";
  }
  const base = `${clients.length} crèche${clients.length > 1 ? "s" : ""}${evaluations ? `, dont ${evaluations} en évaluation` : ""}.`;
  const waiting = board?.pendingMessages ?? 0;
  return waiting ? `${base} ${waiting} message${waiting > 1 ? "s attendent" : " attend"} votre clic.` : base;
}

function Overview({ parc, board, orders }: { parc: Parc; board?: Board; orders?: OrdersBoard }) {
  const { summary } = parc;
  const clients = parc.creches.filter((c) => c.lifecycle === "CLIENT").length;
  const alertKinds = count(parc.creches.flatMap((c) => c.alerts.map((a) => a.label)));
  const todos = buildTodos(parc, new Date(), board, orders);

  return (
    <>
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Kpi label="Revenu mensuel récurrent" value={xpf(board?.mrr)} hint={board ? mrrHint(board, new Date()) : "…"} />
        <Kpi
          label="Encours clients"
          value={xpf(board?.outstanding)}
          hint={board?.overdueCount ? `${xpf(board.overdue)} en retard sur ${board.overdueCount} facture${board.overdueCount > 1 ? "s" : ""}` : "aucune facture en retard"}
          tone={board?.overdueCount ? "bad" : undefined}
        />
        <Kpi
          label="Crèches en alerte"
          value={`${summary.bad} / ${clients}`}
          hint={alertKinds.length ? alertKinds.map(([label, n]) => `${n} ${label.toLowerCase()}`).join(", ") : "aucune alerte ouverte"}
          tone={summary.bad ? "bad" : undefined}
        />
        <Kpi label="Délai moyen de paiement" value={board?.dso != null ? `${board.dso} j` : "—"} hint="factures soldées sur 90 jours · objectif 30 j" />
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
                  <Link to={todo.to} className={buttonVariants({ variant: todo.tone === "bad" && todo.key.startsWith("envoi") ? "default" : "outline", size: "sm" })}>
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
          {board && <Chart months={board.months} />}
        </div>
      </div>
    </>
  );
}

/** Facturé TTC (factures moins avoirs) et encaissé, par mois (maquette : barres appariées). */
function Chart({ months }: { months: Board["months"] }) {
  const top = Math.max(1, ...months.flatMap((m) => [m.invoiced, m.collected]));
  return (
    <section className="rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <div className="mb-2 flex items-baseline justify-between">
        <h2 className="m-0 font-display text-title-2">Facturé et encaissé</h2>
        <span className="text-small text-muted-foreground">6 mois</span>
      </div>
      <div role="img" aria-label={`Facturé et encaissé par mois : ${months.map((m) => `${monthShort(m.month)} ${xpf(m.invoiced)} facturés, ${xpf(m.collected)} encaissés`).join(" ; ")}`} className="flex h-40 items-end gap-3.5 pt-2">
        {months.map((m) => (
          <div key={m.month} className="flex h-full flex-1 flex-col items-center justify-end gap-1.5">
            <div className="flex h-full w-full items-end justify-center gap-[3px]">
              <span title={`Facturé : ${xpf(m.invoiced)}`} className="w-2/5 max-w-[22px] rounded-t border border-lagon-600 bg-lagon-100" style={{ height: `${Math.max(1, (m.invoiced / top) * 100)}%` }} />
              <span title={`Encaissé : ${xpf(m.collected)}`} className="w-2/5 max-w-[22px] rounded-t bg-lagon-600" style={{ height: `${Math.max(1, (m.collected / top) * 100)}%` }} />
            </div>
            <span className="text-small font-semibold text-muted-foreground">{monthShort(m.month)}</span>
          </div>
        ))}
      </div>
      <div className="mt-2 flex gap-4 text-small text-muted-foreground">
        <span className="flex items-center gap-1.5"><i className="inline-block size-2.5 rounded-sm border border-lagon-600 bg-lagon-100" />Facturé TTC</span>
        <span className="flex items-center gap-1.5"><i className="inline-block size-2.5 rounded-sm bg-lagon-600" />Encaissé</span>
      </div>
    </section>
  );
}

/** « + 13 500 F depuis octobre · ARR 162 000 F » ; sans variation, l'ARR seul. */
export function mrrHint(board: Pick<Board, "mrr" | "mrrPrevious" | "arr">, now: Date): string {
  const delta = board.mrr - (board.mrrPrevious ?? board.mrr);
  const previous = new Date(now.getFullYear(), now.getMonth() - 1, 15).toLocaleString("fr-FR", { month: "long", timeZone: "Pacific/Noumea" });
  const change = delta === 0 ? null : `${delta > 0 ? "+" : "−"} ${xpf(Math.abs(delta))} depuis ${previous}`;
  return [change, `ARR ${xpf(board.arr)}`, change ? null : "contrats en cours, HT"].filter(Boolean).join(" · ");
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
export function buildTodos(parc: Parc, now: Date = new Date(), board?: Board, orders?: OrdersBoard): Todo[] {
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
      to: `/parc/${row.slug}`,
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
      to: `/parc/${row.slug}`,
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
      hint: `${row.plan ?? "Formule ?"} · avec un contrat saisi, le brouillon de renouvellement se prépare 45 jours avant`,
      to: `/parc/${row.slug}`,
      action: "Voir",
    });
  }
  for (const row of clients.filter((c) => c.health.level === "WARN" && !(board && c.health.reasons.every((r) => r.startsWith("Impayé"))))) {
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
      to: `/parc/${row.slug}`,
      action: "Voir",
    });
  }
  if (!board) {
    return todos;
  }
  const alerts = todos.filter((t) => t.key.startsWith("alerte"));
  return [...alerts, ...billingTodos(board), ...(orders ? orderTodos(orders, now) : []), ...todos.filter((t) => !t.key.startsWith("alerte"))];
}

/** Facturation : messages à envoyer, brouillons à valider, factures en retard (les trois plus anciennes). */
function billingTodos(board: Board): Todo[] {
  const todos: Todo[] = [];
  if (board.pendingMessages > 0) {
    todos.push({
      key: "envoi",
      tone: "bad",
      icon: icon(Mail),
      title: <><b>{board.pendingMessages} message{board.pendingMessages > 1 ? "s préparés" : " préparé"}</b> dans la boîte d’envoi</>,
      hint: "Rien n’est parti : relisez puis envoyez d’un clic",
      to: "/envoi",
      action: "Relire et envoyer",
    });
  }
  if (board.drafts > 0) {
    todos.push({
      key: "brouillons",
      tone: "warn",
      icon: icon(Receipt),
      title: <><b>{board.drafts} brouillon{board.drafts > 1 ? "s" : ""}</b> à valider : {board.draftList.slice(0, 3).map((d) => d.crecheName ?? d.clientName).join(", ")}</>,
      hint: board.renewalDrafts ? `dont  renouvellement préparé 45 jours avant l’échéance` : "Factures saisies, pas encore validées",
      to: "/factures",
      action: "Valider",
    });
  }
  for (const s of board.suspensions) {
    todos.push({
      key: `suspension-${s.id}`,
      tone: "bad",
      icon: icon(AlertTriangle),
      title: <><b>{s.crecheName ?? s.clientName}</b> : dernière relance partie, facture {s.number} toujours due</>,
      hint: `${xpf(s.remaining)} restent dus · suspension à décider (rien n’est suspendu sans vous)`,
      to: s.crecheSlug ? `/parc/${s.crecheSlug}` : `/factures?id=${s.id}`,
      action: "Décider",
    });
  }
  for (const s of board.late.slice(0, 3)) {
    todos.push({
      key: `retard-${s.id}`,
      tone: s.daysLate > 15 ? "bad" : "warn",
      icon: icon(Clock),
      title: <><b>{s.crecheName ?? s.clientName}</b> : facture {s.number} en retard de {s.daysLate} jour{s.daysLate > 1 ? "s" : ""}</>,
      hint: `${xpf(s.remaining)} restent dus · échue le ${formatDay(s.dueOn)}${s.remindersPaused ? " · relances suspendues" : ""}`,
      to: `/factures?id=${s.id}`,
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

/** Ordres : propositions à confirmer, échecs récents, abonnements non alignés sur le contrat. */
function orderTodos(board: OrdersBoard, now: Date): Todo[] {
  const todos: Todo[] = [];
  const toConfirm = board.orders.filter((o) => o.status === "A_CONFIRMER");
  if (toConfirm.length > 0) {
    todos.push({
      key: "ordres-a-confirmer",
      tone: "info",
      icon: icon(SquareTerminal),
      title: <><b>{toConfirm.length} ordre{toConfirm.length > 1 ? "s" : ""} à confirmer</b> : {toConfirm.slice(0, 3).map((o) => `${typeLabels[o.type].toLowerCase()} ${o.crecheName ?? o.slug}`).join(", ")}</>,
      hint: toConfirm[0].reason ?? "Proposé par la console",
      to: "/ordres",
      action: "Confirmer",
    });
  }
  const week = now.getTime() - 7 * 86_400_000;
  for (const o of board.orders.filter((x) => x.status === "ECHEC" && new Date(x.finishedAt ?? x.createdAt).getTime() > week).slice(0, 3)) {
    todos.push({
      key: `ordre-echec-${o.id}`,
      tone: "bad",
      icon: icon(SquareTerminal),
      title: <><b>{o.crecheName ?? o.slug}</b> : ordre « {typeLabels[o.type].toLowerCase()} » en échec</>,
      hint: (o.output ?? "").trim().split("\n").pop() ?? "Voir la sortie",
      to: "/ordres",
      action: "Voir",
    });
  }
  for (const m of board.misaligned.slice(0, 3)) {
    todos.push({
      key: `aligner-${m.slug}`,
      tone: "warn",
      icon: icon(RefreshCw),
      title: <><b>{m.name}</b> : abonnement de l’instance non aligné sur le contrat</>,
      hint: m.differences.join(" · "),
      to: `/parc/${m.slug}`,
      action: "Aligner",
    });
  }
  return todos;
}
