import { useModules } from "@/features/license/useModules";
import { ReportedAbsencesCard } from "@/features/portal/ReportedAbsencesCard";
import type { ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router";
import { BadgeCheck, CalendarX, ClipboardCheck, ClipboardList, FileStack, HardDrive, Receipt, ShieldAlert, TriangleAlert } from "lucide-react";
import { buttonVariants } from "@/components/ui/button";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { attendanceApi, attendanceKeys } from "@/features/attendance/api";
import { billingApi, billingKeys } from "@/features/billing/api";
import { TonePill, type Tone } from "@/features/enrollment/components";
import type { KioskToday } from "@/features/kiosk/api";
import { statusShort, type RatioStatus } from "@/features/kiosk/ratio";
import { mealKeys, mealsApi } from "@/features/meals/api";
import { NeedsBadges } from "@/features/meals/components";
import { systemApi, systemKeys } from "@/features/settings/systemApi";
import { staffApi, staffKeys } from "@/features/staff/api";
import { alertText } from "@/features/staff/components";
import { useStaffingLive, type StaffingSnapshot } from "@/features/staffing/live";
import { useSections } from "@/features/structure/useSections";
import { useNow } from "@/hooks/useNow";
import { todayIso } from "@/lib/age";
import { api } from "@/lib/api";
import { formatDate, formatLongDate, formatTime, isoToDateFr } from "@/lib/datetime";
import { daysBetween, formatMonthLong, formatXpf, hhmm } from "@/lib/format";
import { cn } from "@/lib/utils";
import { dashboardApi, dashboardKeys, type Today } from "./api";

const tones: Record<RatioStatus, Tone> = { CONFORME: "success", LIMITE: "warning", INSUFFISANT: "danger" };

/**
 * Tableau de bord de la direction (maquette « matinée type ») : actions rapides, alertes, encadrement en temps réel
 * avec les arrivées attendues, présences du jour, repas du jour et indicateurs du mois.
 */
export function DashboardPage() {
  const user = useCurrentUser();
  const now = useNow();
  const { snapshot } = useStaffingLive();
  const day = todayIso(now);
  const isDirection = user.role === "DIRECTION";
  const modules = useModules();
  const seesChildren = user.role === "DIRECTION" || user.role === "EDUCATRICE";
  const seesMoney = user.role === "DIRECTION" || user.role === "COMPTABILITE";
  const view = useQuery({ queryKey: attendanceKeys.day(day), queryFn: ({ signal }) => attendanceApi.day(day, signal) });
  const today = useQuery({
    queryKey: ["kiosk-today", "desk"],
    queryFn: ({ signal }) => api<KioskToday>("/kiosk/today", { signal }),
    enabled: user.role !== "COMPTABILITE",
  });
  const journal = useQuery({ queryKey: dashboardKeys.today(day), queryFn: ({ signal }) => dashboardApi.today(signal), enabled: seesChildren });
  const review = useQuery({ queryKey: attendanceKeys.review, queryFn: ({ signal }) => attendanceApi.review(signal), enabled: isDirection });
  const staffAlerts = useQuery({ queryKey: staffKeys.alerts, queryFn: ({ signal }) => staffApi.alerts(signal), enabled: isDirection && modules.has("PERSONNEL") });
  const collections = useQuery({ queryKey: billingKeys.collections, queryFn: ({ signal }) => billingApi.collections(signal), enabled: seesMoney && modules.has("FACTURATION") });
  const license = useQuery({ queryKey: systemKeys.license, queryFn: ({ signal }) => systemApi.license(signal), enabled: isDirection });
  const backups = useQuery({ queryKey: systemKeys.backups, queryFn: ({ signal }) => systemApi.backups(signal), enabled: isDirection });

  const data = view.data;
  const present = data?.children.filter((c) => !c.departedAt) ?? [];
  const presentIds = new Set(present.map((c) => c.childId));
  const health = (today.data?.children ?? []).filter((c) => presentIds.has(c.id) && (c.health.allergies.length > 0 || c.health.pai));
  const insufficient = snapshot?.sections.filter((s) => s.load.status === "INSUFFISANT") ?? [];
  const toReview = (review.data?.children.length ?? 0) + (review.data?.staff.length ?? 0) + (review.data?.refused.length ?? 0);
  const sectionName = (id: string | null) => snapshot?.sections.find((s) => s.sectionId === id)?.name;
  const unpaid = collections.data;
  const unpaidCount = unpaid?.unpaid.length ?? 0;
  const nextStaff = (sectionId: string) => journal.data?.staffArrivals.find((a) => a.countsInRatio && a.sectionId === sectionId);
  const licenseWarning = license.data && license.data.state !== "ACTIVE" && license.data.state !== "EVALUATION" ? license.data : null;

  const staleBackup = backups.data?.stale ? backups.data : null;

  const alerts: ReactNode[] = [
    ...(staleBackup ? [
      <AlertCard key="sauvegarde" tone="danger" icon={<HardDrive aria-hidden className="size-5 text-danger-700" />}
        title={staleBackup.lastSuccess ? `Aucune sauvegarde réussie depuis le ${formatDate(new Date(staleBackup.lastSuccess.startedAt))}` : "Aucune sauvegarde réussie"}
        action={{ to: "/parametres", label: "Voir les sauvegardes" }}>
        Les données de la crèche ne sont plus protégées : vérifiez le disque de copie, puis lancez une sauvegarde.
      </AlertCard>,
    ] : []),
    ...insufficient.map((s) => {
      const next = nextStaff(s.sectionId);
      return (
        <AlertCard key={`ratio-${s.sectionId}`} tone="danger" icon={<TriangleAlert aria-hidden className="size-5 text-danger-700" />}
          title={`Encadrement insuffisant chez les ${s.name}.`} action={isDirection && modules.has("PERSONNEL") ? { to: "/personnel/planning", label: "Trouver un renfort" } : undefined}>
          {s.load.children} enfant{s.load.children > 1 ? "s" : ""} pour {s.load.adults} adulte{s.load.adults > 1 ? "s" : ""} présent{s.load.adults > 1 ? "s" : ""}, {s.load.required} requis.
          {next && <> {next.name} est attendue à {hhmm(next.startsAt)}.</>}
        </AlertCard>
      );
    }),
    ...health.map((c) => (
      <AlertCard key={`sante-${c.id}`} tone="warning" icon={<ShieldAlert aria-hidden className="size-5 text-danger-700" />}
        title={`${c.health.allergies.length > 0 ? "Allergie" : "PAI"} · enfant présent`} action={{ to: `/enfants/${c.id}`, label: "Voir la fiche" }}>
        {c.firstName} {c.lastName}{sectionName(c.sectionId) && ` (${sectionName(c.sectionId)})`} est présent·e.
        {c.health.allergies.length > 0 && <> Allergie : {c.health.allergies.map((a) => a.allergen).join(", ").toLowerCase()}.</>}
        {c.health.pai && <> PAI{c.health.paiKitLocation ? `, trousse d’urgence : ${c.health.paiKitLocation}` : ""}.</>}
      </AlertCard>
    )),
    ...(modules.has("PERSONNEL") ? staffAlerts.data ?? [] : []).slice(0, 3).map((a, i) => (
      <AlertCard key={`perso-${i}`} tone={a.state === "EXPIREE" ? "danger" : "warning"} icon={<BadgeCheck aria-hidden className="size-5 text-warning-700" />}
        title={a.kind === "CONTRAT" ? "Contrat qui se termine" : a.state === "EXPIREE" ? "Qualification expirée" : "Qualification à renouveler"}
        action={{ to: `/personnel/${a.userId}`, label: "Ouvrir son dossier" }}>
        {a.name} : {alertText(a)}{a.state === "A_RENOUVELER" && `, dans ${daysBetween(day, a.date)} jours`}.
      </AlertCard>
    )),
    ...(modules.has("FACTURATION") && unpaid && unpaidCount > 0 ? [
      <AlertCard key="impayes" tone="warning" icon={<Receipt aria-hidden className="size-5 text-warning-700" />}
        title={`${unpaidCount} facture${unpaidCount > 1 ? "s" : ""} impayée${unpaidCount > 1 ? "s" : ""}`}
        action={{ to: "/facturation/encaissements", label: "Relancer les familles" }}>
        {formatXpf(unpaid.overdue + unpaid.upcoming)} au total{unpaid.overdueOver30 > 0 && `, dont ${unpaid.overdueOver30} facture${unpaid.overdueOver30 > 1 ? "s" : ""} de plus de 30 jours`}.
      </AlertCard>,
    ] : []),
    ...(toReview > 0 ? [
      <AlertCard key="pointages" tone="warning" icon={<TriangleAlert aria-hidden className="size-5 text-warning-700" />}
        title={`${toReview} pointage${toReview > 1 ? "s" : ""} à vérifier`} action={{ to: "/presences", label: "Ouvrir les présences" }}>
        Hors connexion, code incorrect ou départ non pointé.
      </AlertCard>,
    ] : []),
    ...(licenseWarning ? [
      <AlertCard key="licence" tone={licenseWarning.readOnly || licenseWarning.state === "EXPIREE_GRACE" ? "danger" : "warning"} icon={<BadgeCheck aria-hidden className="size-5 text-warning-700" />}
        title="Abonnement" action={{ to: "/parametres", label: "Voir l’abonnement" }}>
        {licenseWarning.message}
      </AlertCard>,
    ] : []),
  ];

  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="m-0 font-display text-display">Bonjour, {user.firstName}</h1>
          <p className="mt-1 mb-0 text-muted-foreground">
            {formatLongDate(now)}
            {data && <> · {present.length} enfant{present.length > 1 ? "s" : ""} présent{present.length > 1 ? "s" : ""} sur {data.expected} attendu{data.expected > 1 ? "s" : ""}</>}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          {user.role !== "COMPTABILITE" && <Link to="/presences" className={buttonVariants()}><ClipboardCheck aria-hidden />Pointer</Link>}
          {isDirection && modules.has("INSCRIPTIONS") && <Link to="/inscriptions/nouvelle" className={buttonVariants({ variant: "outline" })}><ClipboardList aria-hidden />Nouvelle inscription</Link>}
          {seesMoney && modules.has("FACTURATION") && <Link to="/facturation" className={buttonVariants({ variant: "outline" })}><FileStack aria-hidden />Facturer le mois</Link>}
          {seesChildren && <Link to="/presences" className={buttonVariants({ variant: "outline" })}><CalendarX aria-hidden />Signaler une absence</Link>}
        </div>
      </div>

      {alerts.length > 0 && (
        <section aria-labelledby="alertes" className="flex flex-col gap-3">
          <h2 id="alertes" className="m-0 text-title-2">Alertes</h2>
          <div className="grid gap-3 md:grid-cols-2">{alerts}</div>
        </section>
      )}

      {modules.has("PORTAIL_PARENTS") && (isDirection || user.role === "EDUCATRICE") && <ReportedAbsencesCard canConfirm={isDirection} />}
      {user.role !== "COMPTABILITE" && <Staffing snapshot={snapshot} journal={journal.data} expected={data?.notArrived ?? []} />}
      {seesChildren && data && <Presences view={data} journal={journal.data} sectionName={sectionName} />}
      {user.role !== "COMPTABILITE" && modules.has("REPAS") && <MealsToday day={day} />}
      {seesMoney && modules.has("FACTURATION") && <Kpis />}
    </>
  );
}

function AlertCard({ tone, icon, title, action, children }: {
  tone: "danger" | "warning";
  icon: ReactNode;
  title: string;
  action?: { to: string; label: string };
  children: ReactNode;
}) {
  return (
    <article role={tone === "danger" ? "alert" : undefined}
      className={cn("flex items-start gap-3 rounded-lg border bg-card px-4 py-3 shadow-1", tone === "danger" ? "border-danger-600 bg-danger-100/40" : "border-warning-700/30")}>
      <span className="mt-0.5 shrink-0">{icon}</span>
      <div className="flex flex-col gap-1">
        <strong>{title}</strong>
        <span>{children}</span>
        {action && <Link to={action.to} className="text-label font-semibold text-lagon-700">{action.label}</Link>}
      </div>
    </article>
  );
}

type Expected = { childId: string; firstName: string; lastName: string; sectionId: string | null; expectedAt: string; late: boolean };

function Staffing({ snapshot, journal, expected }: { snapshot: StaffingSnapshot | null; journal?: Today; expected: Expected[] }) {
  return (
    <section aria-labelledby="encadrement" className="flex flex-col gap-4 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <div>
        <h2 id="encadrement" className="m-0 text-title-2">Encadrement en temps réel</h2>
        {snapshot && (
          <p className="m-0 text-muted-foreground">
            Mis à jour à {formatTime(new Date(snapshot.at))}
            {snapshot.rules.length > 0 && <> · règles : {snapshot.rules.join(", ")}</>}
          </p>
        )}
      </div>
      {!snapshot && <p className="m-0 text-muted-foreground">Chargement…</p>}
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        {snapshot?.sections.map((s) => {
          const nextChild = expected.filter((e) => e.sectionId === s.sectionId && !e.late).sort((a, b) => a.expectedAt.localeCompare(b.expectedAt))[0];
          const arrivals = (journal?.staffArrivals ?? []).filter((a) => a.countsInRatio && a.sectionId === s.sectionId);
          const waiting = expected.filter((e) => e.sectionId === s.sectionId).length;
          return (
            <article key={s.sectionId} aria-label={`Section ${s.name}`} className={cn("flex flex-col gap-3 rounded-lg border p-4", s.load.status === "INSUFFISANT" ? "border-danger-600" : "border-border-subtle")}>
              <div className="flex items-center justify-between gap-2">
                <h3 className="m-0 flex items-center gap-2 text-title-3">
                  <span aria-hidden className="size-3 rounded-full" style={{ backgroundColor: s.color }} />
                  {s.name}
                </h3>
                <TonePill tone={tones[s.load.status]}>{statusShort[s.load.status]}</TonePill>
              </div>
              <dl className="m-0 grid grid-cols-3 gap-2 text-center">
                {[["Enfants", s.load.children], ["Adultes", s.load.adults], ["Requis", s.load.required]].map(([label, value]) => (
                  <div key={label} className="rounded-md bg-muted py-2">
                    <dt className="text-small text-muted-foreground">{label}</dt>
                    <dd className="m-0 text-title-2 tabular-nums">{value}</dd>
                  </div>
                ))}
              </dl>
              <p className="m-0 text-small">
                Places couvertes par l’effectif : <strong className="tabular-nums">{s.load.children} sur {s.load.coveredPlaces}</strong>
                {s.load.missingRule && <span className="block text-warning-700">Un enfant n’a pas de règle d’encadrement pour son âge.</span>}
              </p>
              {s.load.status !== "INSUFFISANT" && waiting > 0 && s.load.coveredPlaces >= s.load.children + waiting && (
                <p className="m-0 text-small text-muted-foreground">Encore {waiting} arrivée{waiting > 1 ? "s" : ""} attendue{waiting > 1 ? "s" : ""}, l’effectif suffit.</p>
              )}
              {nextChild && <p className="m-0 text-small text-muted-foreground">{nextChild.firstName} {nextChild.lastName} arrive à {hhmm(nextChild.expectedAt)}</p>}
              <ul className="m-0 flex list-none flex-wrap gap-2 p-0">
                {s.staff.map((m) => (
                  <li key={m.userId} className="flex items-center gap-1.5 rounded-full bg-lagon-50 py-1 pr-3 pl-1 text-small">
                    <span aria-hidden className="flex size-6 items-center justify-center rounded-full bg-lagon-100 text-caption text-lagon-700">{m.initials}</span>
                    {m.displayName}
                  </li>
                ))}
                {arrivals.map((a) => (
                  <li key={a.userId} className="flex items-center gap-1.5 rounded-full border border-dashed border-border py-1 pr-3 pl-3 text-small text-muted-foreground">
                    {a.name} · à {hhmm(a.startsAt)}
                  </li>
                ))}
                {s.staff.length === 0 && arrivals.length === 0 && <li className="text-small text-muted-foreground">Aucun adulte pointé</li>}
              </ul>
            </article>
          );
        })}
      </div>
      {snapshot && (
        <p className="m-0 text-muted-foreground">
          Toute la structure : {snapshot.global.children} enfant{snapshot.global.children > 1 ? "s" : ""}, {snapshot.global.adults} adulte{snapshot.global.adults > 1 ? "s" : ""}, {snapshot.global.required} requis ·{" "}
          <span className={cn(snapshot.global.status === "INSUFFISANT" && "font-semibold text-danger-700")}>{statusShort[snapshot.global.status]}</span>
        </p>
      )}
    </section>
  );
}

type DayView = Awaited<ReturnType<typeof attendanceApi.day>>;

/** Présences du jour : présents, pas encore là, absents signalés, départs prévus avant la fermeture. */
function Presences({ view, journal, sectionName }: { view: DayView; journal?: Today; sectionName: (id: string | null) => string | undefined }) {
  const sections = useSections();
  const name = (id: string | null) => sectionName(id) ?? sections.data?.find((s) => s.id === id)?.name ?? "—";
  const present = view.children.filter((c) => !c.departedAt).length;
  const late = view.notArrived.filter((e) => e.late).length;
  const absences = journal?.absences ?? [];
  const departures = journal?.departures ?? [];
  return (
    <section aria-labelledby="presences" className="flex flex-col gap-4 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <div className="flex items-center justify-between gap-3">
        <h2 id="presences" className="m-0 text-title-2">Présences du jour</h2>
        <Link to="/presences" className="text-lagon-700 underline">Ouvrir les présences</Link>
      </div>
      <dl className="m-0 grid grid-cols-2 gap-4 md:grid-cols-4">
        <Stat label="Présents" value={present} detail={`sur ${view.expected} attendus`} />
        <Stat label="Pas encore là" value={view.notArrived.length} detail={late > 0 ? `dont ${late} en retard` : "aucun retard"} />
        <Stat label="Absents signalés" value={absences.length} detail={absences.length > 0 ? "prévenus au bureau" : "aucune absence"} />
        <Stat label="Départs prévus" value={departures.length} detail="avant la fermeture" />
      </dl>
      <div className="grid gap-4 lg:grid-cols-3">
        <List title="Pas encore arrivés" empty="Tout le monde est arrivé.">
          {view.notArrived.map((e) => (
            <li key={e.childId} className="flex items-center justify-between gap-3">
              <span>{e.firstName} {e.lastName}<span className="block text-small text-muted-foreground">{name(e.sectionId)} · prévu à {hhmm(e.expectedAt)}</span></span>
              <TonePill tone={e.late ? "warning" : "info"}>{e.late ? "En retard" : "Attendu"}</TonePill>
            </li>
          ))}
        </List>
        <List title="Absents signalés" empty="Aucune absence signalée.">
          {absences.map((a) => (
            <li key={a.childId}>
              {a.name}
              <span className="block text-small text-muted-foreground">
                {name(a.sectionId)} · {a.reason?.toLowerCase() ?? "absence"}{a.until > journal!.day && `, jusqu’au ${isoToDateFr(a.until)}`}
              </span>
            </li>
          ))}
        </List>
        <List title="Départs prévus" empty="Aucun départ avant la fermeture.">
          {departures.map((d) => (
            <li key={d.childId} className="flex items-baseline gap-3">
              <span className="w-12 tabular-nums font-semibold">{hhmm(d.leavesAt)}</span>
              <span>{d.name}<span className="text-small text-muted-foreground"> · {name(d.sectionId)}{!d.present && " · pas encore arrivé"}</span></span>
            </li>
          ))}
        </List>
      </div>
    </section>
  );
}

function List({ title, empty, children }: { title: string; empty: string; children: ReactNode[] }) {
  return (
    <div className="flex flex-col gap-2">
      <h3 className="m-0 text-title-3">{title}</h3>
      {children.length === 0 ? <p className="m-0 text-small text-muted-foreground">{empty}</p>
        : <ul className="m-0 flex list-none flex-col gap-2 p-0">{children}</ul>}
    </div>
  );
}

/** Carte « Repas du jour » (maquette du tableau de bord) : comptes d'après les présences, menu, régimes. */
function MealsToday({ day }: { day: string }) {
  const kitchen = useQuery({ queryKey: mealKeys.kitchen(day), queryFn: ({ signal }) => mealsApi.kitchen(day, signal) });
  const data = kitchen.data;
  if (!data) return null;
  const textured = data.total.mashed + data.total.minced;
  const plat = data.menu.find((i) => i.course === "PLAT");
  const gouter = data.menu.find((i) => i.course === "GOUTER");
  return (
    <section aria-labelledby="repas-du-jour" className="flex flex-col gap-4 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <div className="flex items-center justify-between gap-3">
        <div>
          <h2 id="repas-du-jour" className="m-0 text-title-2">Repas du jour</h2>
          <p className="m-0 text-muted-foreground">Compté à {formatTime(new Date(data.countedAt))} d’après les présences</p>
        </div>
        <Link to="/repas" className="text-lagon-700 underline">Ouvrir la vue cuisine</Link>
      </div>
      <dl className="m-0 grid grid-cols-2 gap-4 md:grid-cols-3">
        <Stat label="Repas de midi" value={data.total.lunches}
          detail={textured > 0 ? `dont ${data.total.mashed} mixé${data.total.mashed > 1 ? "s" : ""}, ${data.total.minced} haché${data.total.minced > 1 ? "s" : ""}` : `à ${data.lunchTime.slice(0, 5)}`} />
        <Stat label="Goûters" value={data.total.snacks} detail={`à ${data.snackTime.slice(0, 5)}`} />
        <Stat label="Paniers repas" value={data.total.packedLunches} detail="fournis par les familles" />
      </dl>
      {plat && (
        <p className="m-0"><strong>Au menu :</strong> {plat.label}{gouter && <>. Goûter : {gouter.label}</>}.</p>
      )}
      {data.toAdapt.length > 0 && (
        <div>
          <h3 className="m-0 mb-2 text-title-3">Régimes particuliers · {data.toAdapt.length}</h3>
          <ul className="m-0 flex list-none flex-col gap-2 p-0">
            {data.toAdapt.map((c) => (
              <li key={c.childId} className="flex flex-wrap items-center justify-between gap-2 border-b border-border-subtle pb-2 last:border-0">
                <span>{c.firstName} {c.lastName} <span className="text-muted-foreground">· {c.sectionName ?? "—"}</span></span>
                <NeedsBadges allergies={c.allergies} pai={c.pai} diet={c.packedLunch ? "panier repas" : c.diet} />
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}

/** Indicateurs du mois (maquette) : remplissage, chiffre d'affaires facturé, impayés, liste d'attente. */
function Kpis() {
  const kpis = useQuery({ queryKey: dashboardKeys.kpis, queryFn: ({ signal }) => dashboardApi.kpis(signal) });
  const sections = useSections();
  const k = kpis.data;
  if (!k) return null;
  const monthName = formatMonthLong(k.month).split(" ")[0];
  const percent = (perMille: number) => `${(perMille / 10).toLocaleString("fr-FR", { maximumFractionDigits: 1 })} %`;
  return (
    <section aria-labelledby="indicateurs" className="flex flex-col gap-4 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <h2 id="indicateurs" className="m-0 text-title-2">Indicateurs {/^[aeiou]/.test(monthName) ? "d’" : "de "}{monthName}</h2>
      <dl className="m-0 grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-4">
        <Kpi label="Taux de remplissage" value={percent(k.fillRate)} detail={`Heures réservées · ${k.enrolled} enfant${k.enrolled > 1 ? "s" : ""} inscrit${k.enrolled > 1 ? "s" : ""} pour ${k.capacity} places`} />
        <Kpi label={k.invoicedMonth ? `CA facturé · ${formatMonthLong(k.invoicedMonth)}` : "CA facturé"} value={formatXpf(k.invoiced)}
          detail={k.invoicedMonth ? <>{k.invoicedChange !== null && <span className={k.invoicedChange >= 0 ? "text-success-700" : "text-danger-700"}>{k.invoicedChange >= 0 ? "+ " : "− "}{percent(Math.abs(k.invoicedChange))} sur le mois précédent · </span>}{k.invoices} facture{k.invoices > 1 ? "s" : ""} émise{k.invoices > 1 ? "s" : ""} sur {k.billableFamilies}</> : "Aucune facture émise"} />
        <Kpi label="Impayés" value={formatXpf(k.unpaid)}
          detail={k.unpaidCount > 0 ? `${k.unpaidCount} facture${k.unpaidCount > 1 ? "s" : ""} impayée${k.unpaidCount > 1 ? "s" : ""}${k.oldestUnpaid ? ` · la plus ancienne date du ${isoToDateFr(k.oldestUnpaid)}` : ""}` : "Tout est réglé"} />
        <Kpi label="Liste d’attente" value={`${k.waitingList} demande${k.waitingList > 1 ? "s" : ""}`}
          detail={<>{Object.entries(k.waitingBySection).map(([id, n]) => `${sections.data?.find((s) => s.id === id)?.name ?? "—"} · ${n}`).join(" · ")}{k.newRequests > 0 && <span className="block">{k.newRequests} nouvelle{k.newRequests > 1 ? "s" : ""} demande{k.newRequests > 1 ? "s" : ""} ce mois-ci</span>}</>} />
      </dl>
      <Link to="/facturation" className="self-start text-lagon-700 underline"><Receipt aria-hidden className="mr-1 inline size-4" />Voir la facturation</Link>
    </section>
  );
}

function Kpi({ label, value, detail }: { label: string; value: string; detail: ReactNode }) {
  return (
    <div className="flex flex-col gap-1 rounded-md bg-muted p-4">
      <dt className="text-label text-muted-foreground">{label}</dt>
      <dd className="m-0 text-numeric-lg tabular-nums">{value}</dd>
      <dd className="m-0 text-small text-muted-foreground">{detail}</dd>
    </div>
  );
}

function Stat({ label, value, detail }: { label: string; value: number; detail: string }) {
  return (
    <div className="flex flex-col gap-1 rounded-md bg-muted p-4">
      <dt className="text-label text-muted-foreground">{label}</dt>
      <dd className="m-0 text-numeric-lg tabular-nums">{value}</dd>
      <dd className="m-0 text-small text-muted-foreground">{detail}</dd>
    </div>
  );
}
