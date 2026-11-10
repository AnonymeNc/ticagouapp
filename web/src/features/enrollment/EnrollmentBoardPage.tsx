import { useDeferredValue, useMemo, useState } from "react";
import { Link } from "react-router";
import { useQuery } from "@tanstack/react-query";
import { Plus, Search } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { SectionBadge } from "@/features/children/components";
import { useSections, type Section } from "@/features/structure/useSections";
import { todayIso } from "@/lib/age";
import { errorMessage } from "@/lib/api";
import { isoToDateFr } from "@/lib/datetime";
import { formatMonthLong, nextMonths } from "@/lib/format";
import { selectClass } from "@/lib/formFields";
import { cn } from "@/lib/utils";
import { STAGES, enrollmentApi, enrollmentKeys, isClosed, stageLabels, type BoardStats, type RequestCard } from "./api";
import { DayChips, EnrollmentHeader, TonePill, ageLabel, capacityHint, cardFooter, footerColors, stageIcons } from "./components";
import { NoTariffNotice } from "./NoTariffNotice";

const normalized = (s: string) => s.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase();

/**
 * Tableau des demandes (maquette Inscriptions › Demandes) : une colonne par étape.
 * Écart : pas de glisser-déposer ; l'étape se change depuis la fiche de la demande (« Changer d'étape »).
 */
export function EnrollmentBoardPage() {
  const user = useCurrentUser();
  const canEdit = user.role === "DIRECTION";
  const sections = useSections();
  const [q, setQ] = useState("");
  const [sectionId, setSectionId] = useState("");
  const [month, setMonth] = useState("");
  const [includeClosed, setIncludeClosed] = useState(false);
  const deferredQ = useDeferredValue(q.trim());
  const board = useQuery({
    queryKey: enrollmentKeys.board({ includeClosed }),
    queryFn: ({ signal }) => enrollmentApi.board({ includeClosed }, signal),
  });
  const months = nextMonths(6);
  const sectionById = new Map((sections.data ?? []).map((s) => [s.id, s]));

  const filtered = useMemo(
    () =>
      (board.data?.requests ?? []).filter(
        (r) =>
          (!deferredQ || normalized(`${r.childFirstName} ${r.childLastName}`).includes(normalized(deferredQ))) &&
          (!sectionId || r.sectionId === sectionId) &&
          (!month || r.desiredStart.startsWith(month)),
      ),
    [board.data, deferredQ, sectionId, month],
  );

  return (
    <>
      <EnrollmentHeader
        subtitle="Suivez chaque demande de la première prise de contact à la signature du contrat."
        actions={
          canEdit && (
            <Button asChild>
              <Link to="/inscriptions/nouvelle">
                <Plus aria-hidden />
                Nouvelle demande
              </Link>
            </Button>
          )
        }
      />

      <NoTariffNotice />

      {board.data && <Stats stats={board.data.stats} requests={board.data.requests} />}

      <section aria-label="Filtres" className="flex flex-wrap items-end gap-x-6 gap-y-4 rounded-lg border border-border-subtle bg-card p-4 shadow-1">
        <div className="flex min-w-60 flex-1 flex-col gap-1.5">
          <label htmlFor="recherche-demande" className="text-label">Rechercher une demande</label>
          <div className="flex h-control items-center gap-2 rounded-md border border-input bg-card px-3 focus-within:outline-2 focus-within:outline-ring">
            <Search aria-hidden className="size-4 text-muted-foreground" />
            <input
              id="recherche-demande"
              type="search"
              placeholder="Prénom ou nom de l’enfant"
              value={q}
              onChange={(e) => setQ(e.target.value)}
              className="min-w-0 flex-1 bg-transparent outline-none"
            />
          </div>
        </div>
        <div className="flex w-48 flex-col gap-1.5">
          <label htmlFor="filtre-section" className="text-label">Section</label>
          <select id="filtre-section" className={selectClass} value={sectionId} onChange={(e) => setSectionId(e.target.value)}>
            <option value="">Toutes les sections</option>
            {(sections.data ?? []).map((s) => (
              <option key={s.id} value={s.id}>{s.name}</option>
            ))}
          </select>
        </div>
        <div className="flex w-52 flex-col gap-1.5">
          <label htmlFor="filtre-mois" className="text-label">Entrée souhaitée</label>
          <select id="filtre-mois" className={selectClass} value={month} onChange={(e) => setMonth(e.target.value)}>
            <option value="">Tous les mois</option>
            {months.map((m) => (
              <option key={m} value={m}>{formatMonthLong(m)}</option>
            ))}
          </select>
        </div>
        <label className="inline-flex h-control items-center gap-2">
          <input type="checkbox" checked={includeClosed} onChange={(e) => setIncludeClosed(e.target.checked)} className="size-4 accent-lagon-600" />
          Afficher toutes les demandes clôturées
        </label>
      </section>

      {board.isError ? (
        <p className="text-danger-700">{errorMessage(board.error)}</p>
      ) : (
        <div className="grid grid-flow-col auto-cols-[minmax(240px,1fr)] gap-3 overflow-x-auto pb-2">
          {STAGES.map((stage) => {
            const cards = filtered.filter((r) => r.stage === stage);
            const Icon = stageIcons[stage];
            return (
              <section
                key={stage}
                aria-labelledby={`colonne-${stage}`}
                className="flex min-h-40 flex-col gap-2 rounded-lg bg-muted p-2"
              >
                <h2 id={`colonne-${stage}`} className="m-0 flex items-center gap-2 px-1.5 pt-1 text-label">
                  <Icon aria-hidden className={cn("size-4", stage === "SIGNEE" ? "text-success-700" : stage === "REFUSEE" ? "text-danger-700" : "text-muted-foreground")} />
                  {stageLabels[stage]}
                  <span className="ml-auto rounded-full bg-card px-2 text-small text-muted-foreground tabular-nums">{cards.length}</span>
                </h2>
                {board.isPending && <p className="m-0 px-1.5 text-small text-muted-foreground">Chargement…</p>}
                {cards.map((card) => (
                  <Card key={card.id} card={card} section={card.sectionId ? sectionById.get(card.sectionId) : undefined} />
                ))}
              </section>
            );
          })}
        </div>
      )}
    </>
  );
}

function Stats({ stats, requests }: { stats: BoardStats; requests: RequestCard[] }) {
  const today = todayIso();
  const late = requests.filter((r) => r.stage === "A_RECONTACTER" && r.followUpOn && r.followUpOn < today);
  const offers = requests.filter((r) => r.stage === "PLACE_PROPOSEE" && r.offerExpiresOn).map((r) => r.offerExpiresOn!).sort();
  const tiles = [
    { label: "Demandes en cours", value: stats.inProgress, hint: "hors contrats signés et refus", warn: false },
    {
      label: "Relances en retard",
      value: stats.overdueFollowUps,
      hint: late.length === 1 ? `${late[0].childFirstName} ${late[0].childLastName}` : late.length ? "à rappeler" : "aucune",
      warn: stats.overdueFollowUps > 0,
    },
    { label: "Visites cette semaine", value: stats.visitsThisWeek, hint: "visites non encore faites", warn: false },
    {
      label: "Réponses attendues",
      value: stats.awaitingAnswers,
      hint: offers.length ? `avant le ${isoToDateFr(offers[0])}` : "places proposées",
      warn: false,
    },
  ];
  return (
    <dl className="m-0 grid grid-cols-2 gap-3 lg:grid-cols-4">
      {tiles.map((t) => (
        <div key={t.label} className="flex flex-col gap-1 rounded-lg border border-border-subtle bg-card p-4 shadow-1">
          <dt className="text-label text-muted-foreground">{t.label}</dt>
          <dd className={cn("m-0 font-display text-title-1 tabular-nums", t.warn && "text-warning-700")}>{t.value}</dd>
          <dd className="m-0 text-small text-muted-foreground">{t.hint}</dd>
        </div>
      ))}
    </dl>
  );
}

function Card({ card, section }: { card: RequestCard; section?: Section }) {
  const hint = capacityHint(card.capacity, card.stage);
  const footer = cardFooter(card);
  const FooterIcon = footer.icon;
  const closed = isClosed(card.stage);
  return (
    <Link
      to={`/inscriptions/${card.id}`}
      className="flex flex-col gap-2 rounded-md border border-border-subtle bg-card p-3 text-foreground no-underline shadow-1 hover:border-lagon-600 focus-visible:outline-2 focus-visible:outline-ring"
    >
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="m-0 font-semibold">{card.childFirstName} {card.childLastName}</p>
          <p className="m-0 text-small text-muted-foreground">
            {card.stage === "REFUSEE" && card.refusalReason ? card.refusalReason : ageLabel(card)}
          </p>
        </div>
        <SectionBadge section={section} />
      </div>
      {!closed && (
        <>
          <div className="flex flex-wrap items-center gap-2 text-small">
            <DayChips days={card.days} />
            <span className="text-muted-foreground tabular-nums">Entrée {isoToDateFr(card.desiredStart)}</span>
          </div>
          {card.tags.length > 0 && (
            <div className="flex flex-wrap gap-1">
              {card.tags.map((t) => (
                <span key={t} className="rounded-sm bg-muted px-1.5 text-small text-muted-foreground">{t}</span>
              ))}
            </div>
          )}
          {hint && <TonePill tone={hint.tone}>{hint.label}</TonePill>}
        </>
      )}
      <p className={cn("m-0 flex items-center gap-1.5 border-t border-border-subtle pt-2 text-small font-semibold", footerColors[footer.tone])}>
        <FooterIcon aria-hidden className="size-3.5 shrink-0" />
        {footer.label}
      </p>
    </Link>
  );
}
