import { useDeferredValue, useEffect, useState, type ReactNode } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, ArrowRight, Check, RotateCcw, Trash2 } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { childKeys, childrenApi } from "@/features/children/api";
import { SectionBadge } from "@/features/children/components";
import { RelationshipOptions } from "@/features/children/NewChildPage";
import { enrollmentApi, enrollmentKeys, type CapacityTest, type RequestRecord } from "@/features/enrollment/api";
import { TonePill, capacityHint } from "@/features/enrollment/components";
import { sectionForAge } from "@/features/enrollment/RequestForm";
import { useSections, type Section } from "@/features/structure/useSections";
import { useStructure } from "@/features/structure/useStructure";
import { ageInMonths, formatAge, todayIso } from "@/lib/age";
import { ApiError, errorMessage } from "@/lib/api";
import { isoToDateFr, parseDateFr } from "@/lib/datetime";
import { DAY_NAMES, addDays, formatMinutes, formatMonthLong, formatXpf, hhmm, isoWeekday, span, timeToMinutes } from "@/lib/format";
import { isoToField, selectClass, textOrNull } from "@/lib/formFields";
import { cn } from "@/lib/utils";
import {
  bandLabel,
  billingLabels,
  contractKeys,
  contractsApi,
  type AdaptationBilling,
  type BillingMode,
  type Contract,
  type ContractDraft,
  type PlanningMode,
  type SessionInput,
} from "./api";
import { BillingModeChoice, PlanningEditor, initialDays, toDayInputs, weeklyMinutes, type DaysState } from "./PlanningEditor";

type Source = { kind: "request"; requestId: string } | { kind: "child"; childId: string } | { kind: "edit"; contractId: string };

type ChildFields = {
  firstName: string; lastName: string; birthDate: string; sectionId: string;
  guardianFirstName: string; guardianLastName: string; relationship: string; phone: string; email: string;
  address: string; postalCode: string; city: string; familyId: string | null; familyLabel: string | null;
};

type Session = { date: string; startsAt: string; endsAt: string; note: string };

type State = {
  child: ChildFields;
  start: string;
  end: string;
  noticeMonths: number;
  mode: PlanningMode;
  days: DaysState;
  monthlyHours: string;
  noticeDays: number;
  quotient: string;
  adaptation: boolean;
  adaptationWeeks: 1 | 2;
  billing: AdaptationBilling;
  /** Nul : mode par défaut des Paramètres (Barème et facturation). */
  billingMode: BillingMode | null;
  sessions: Session[];
};

/** Séances progressives (maquette Contrat › étape 3) : durée en minutes, ou « planning » pour une journée complète. */
const PROGRESSION: { minutes: number | "planning"; startsAt?: string; note: string }[] = [
  { minutes: 60, startsAt: "09:00", note: "avec un parent" },
  { minutes: 60, startsAt: "09:00", note: "seul" },
  { minutes: 120, startsAt: "09:00", note: "seul" },
  { minutes: 240, startsAt: "08:30", note: "avec repas" },
  { minutes: 360, startsAt: "08:30", note: "repas et sieste" },
];

const toTime = (minutes: number) => `${String(Math.floor(minutes / 60)).padStart(2, "0")}:${String(minutes % 60).padStart(2, "0")}`;

/** Séances sur les jours d'ouverture précédant le début du contrat, de plus en plus longues. */
export function adaptationSessions(startIso: string, weeks: 1 | 2, openDays: number[], days: DaysState): Session[] {
  const dates: string[] = [];
  for (let d = addDays(startIso, -1); dates.length < weeks * 5 && dates.length < 40; d = addDays(d, -1)) {
    if (openDays.includes(isoWeekday(d))) dates.unshift(d);
    if (d < addDays(startIso, -weeks * 7 - 7)) break;
  }
  const steps = weeks === 2 ? [...PROGRESSION, ...Array(5).fill({ minutes: "planning", note: "journée" })]
    : [PROGRESSION[0], PROGRESSION[2], PROGRESSION[3], PROGRESSION[4], { minutes: "planning" as const, note: "journée" }];
  return dates.flatMap((date, i) => {
    const step = steps[i];
    if (!step) return [];
    if (step.minutes === "planning") {
      const day = days[isoWeekday(date)];
      return day?.on ? [{ date, startsAt: day.arrivesAt, endsAt: day.leavesAt, note: step.note }] : [];
    }
    const start = timeToMinutes(step.startsAt!);
    return [{ date, startsAt: step.startsAt!, endsAt: toTime(start + step.minutes), note: step.note }];
  });
}

function firstOfNextMonth(today: string) {
  const [y, m] = today.split("-").map(Number);
  return m === 12 ? `${y + 1}-01-01` : `${y}-${String(m + 1).padStart(2, "0")}-01`;
}

const oneYearLater = (iso: string) => {
  const [y, m, d] = iso.split("-").map(Number);
  return addDays(`${y + 1}-${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}`, -1);
};

export function ContractFromRequestPage() {
  const { requestId = "" } = useParams();
  return <ContractWizard source={{ kind: "request", requestId }} />;
}

export function ContractForChildPage() {
  const { id = "" } = useParams();
  return <ContractWizard source={{ kind: "child", childId: id }} />;
}

export function ContractEditPage() {
  const { contractId = "" } = useParams();
  return <ContractWizard source={{ kind: "edit", contractId }} />;
}

/** Charge la demande, l'enfant ou le contrat à modifier, puis ouvre l'assistant. */
function ContractWizard({ source }: { source: Source }) {
  const structure = useStructure();
  const sections = useSections();
  const request = useQuery({
    queryKey: enrollmentKeys.record(source.kind === "request" ? source.requestId : ""),
    queryFn: ({ signal }) => enrollmentApi.record((source as { requestId: string }).requestId, signal),
    enabled: source.kind === "request",
  });
  const contract = useQuery({
    queryKey: contractKeys.detail(source.kind === "edit" ? source.contractId : ""),
    queryFn: ({ signal }) => contractsApi.detail((source as { contractId: string }).contractId, signal),
    enabled: source.kind === "edit",
  });
  // La liste des enfants suffit (identité, section) et n'est pas journalisée comme une consultation de fiche.
  const children = useQuery({
    queryKey: childKeys.list({ includeExited: false }),
    queryFn: ({ signal }) => childrenApi.list({}, signal),
    enabled: source.kind === "child",
  });

  const loading = structure.isPending || sections.isPending || (source.kind === "request" && request.isPending)
    || (source.kind === "edit" && contract.isPending) || (source.kind === "child" && children.isPending);
  const error = request.error ?? contract.error ?? children.error ?? structure.error;
  if (error) return <p className="text-danger-700">{errorMessage(error)}</p>;
  if (loading || !structure.data || !sections.data) return <p className="text-muted-foreground">Chargement…</p>;

  const openDays = structure.data.openingHours.map((o) => o.dayOfWeek).sort();
  const today = todayIso();
  let initial: State;
  let title: { name: string; sectionId: string | null; birthDate: string | null };
  if (source.kind === "request") {
    const r = request.data!;
    initial = fromRequest(r, openDays, today);
    title = { name: `${r.childFirstName} ${r.childLastName}`, sectionId: r.sectionId, birthDate: r.birthDate };
  } else if (source.kind === "edit") {
    const c = contract.data!;
    initial = fromContract(c, openDays);
    title = { name: `${c.childFirstName} ${c.childLastName}`, sectionId: c.sectionId, birthDate: c.childBirthDate };
  } else {
    const child = children.data!.find((c) => c.id === source.childId);
    if (!child) return <p className="text-danger-700">Enfant introuvable.</p>;
    const start = firstOfNextMonth(today);
    initial = {
      ...emptyState(openDays, start),
      child: { ...emptyChild(), firstName: child.firstName, lastName: child.lastName, birthDate: isoToField(child.birthDate), sectionId: child.sectionId ?? "" },
    };
    title = { name: `${child.firstName} ${child.lastName}`, sectionId: child.sectionId, birthDate: child.birthDate };
  }
  return (
    <Wizard
      source={source}
      initial={initial}
      title={title}
      request={request.data}
      contract={contract.data}
      openDays={openDays}
    />
  );
}

function emptyChild(): ChildFields {
  return {
    firstName: "", lastName: "", birthDate: "", sectionId: "", guardianFirstName: "", guardianLastName: "",
    relationship: "Mère", phone: "", email: "", address: "", postalCode: "", city: "", familyId: null, familyLabel: null,
  };
}

function emptyState(openDays: number[], startIso: string): State {
  const days = initialDays(openDays, openDays.filter((d) => d <= 5).map((d) => ({ dayOfWeek: d, arrivesAt: "07:30", leavesAt: "16:30" })));
  return {
    child: emptyChild(),
    start: isoToDateFr(startIso),
    end: isoToDateFr(oneYearLater(startIso)),
    noticeMonths: 1,
    mode: "FIXE",
    days,
    monthlyHours: "",
    noticeDays: 14,
    quotient: "",
    adaptation: true,
    adaptationWeeks: 2,
    billing: "AU_TEMPS",
    billingMode: null,
    sessions: adaptationSessions(startIso, 2, openDays, days),
  };
}

function fromRequest(r: RequestRecord, openDays: number[], today: string): State {
  const start = r.desiredStart > today ? r.desiredStart : firstOfNextMonth(today);
  const base = emptyState(openDays, start);
  const days = initialDays(openDays, r.days.map((d) => ({
    dayOfWeek: d, arrivesAt: r.arrivesAt ?? "07:30", leavesAt: r.leavesAt ?? "16:30",
  })), { arrivesAt: hhmm(r.arrivesAt ?? "07:30"), leavesAt: hhmm(r.leavesAt ?? "16:30") });
  return {
    ...base,
    days,
    sessions: adaptationSessions(start, 2, openDays, days),
    child: {
      ...emptyChild(),
      firstName: r.childFirstName,
      lastName: r.childLastName,
      birthDate: isoToField(r.birthDate),
      sectionId: r.sectionId ?? "",
      guardianFirstName: r.guardianFirstName,
      guardianLastName: r.guardianLastName,
      relationship: r.relationship,
      phone: r.phone ?? "",
      email: r.email ?? "",
      familyId: r.familyId,
      familyLabel: r.familyLabel,
    },
  };
}

function fromContract(c: Contract, openDays: number[]): State {
  const v = c.versions[0];
  return {
    ...emptyState(openDays, c.startDate),
    start: isoToDateFr(c.startDate),
    end: isoToDateFr(c.endDate),
    noticeMonths: c.noticeMonths,
    mode: v.planningMode,
    days: initialDays(openDays, v.days),
    monthlyHours: v.monthlyReservedMinutes ? String(v.monthlyReservedMinutes / 60) : "",
    noticeDays: v.planningNoticeDays ?? 14,
    quotient: v.familyQuotient !== null ? String(v.familyQuotient) : "",
    adaptation: c.adaptation.length > 0,
    adaptationWeeks: c.adaptation.length > 5 ? 2 : 1,
    billing: c.adaptationBilling,
    billingMode: v.billingMode,
    sessions: c.adaptation.map((s) => ({ date: s.date, startsAt: hhmm(s.startsAt), endsAt: hhmm(s.endsAt), note: s.note ?? "" })),
  };
}

const STEP_LABELS = [
  ["Planning type", "Jours et horaires"],
  ["Tarification", "Forfait mensuel"],
  ["Adaptation", "Dates de début"],
  ["Récapitulatif", "Contrat à signer"],
] as const;

function Wizard({ source, initial, title, request, contract, openDays }: {
  source: Source;
  initial: State;
  title: { name: string; sectionId: string | null; birthDate: string | null };
  request?: RequestRecord;
  contract?: Contract;
  openDays: number[];
}) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const structure = useStructure();
  const sections = useSections();
  const [state, setState] = useState<State>(initial);
  const withChildStep = source.kind === "request";
  const steps = withChildStep ? [["Enfant et famille", "Repris de la demande"] as const, ...STEP_LABELS] : [...STEP_LABELS];
  const [step, setStep] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const set = (change: Partial<State>) => setState((s) => ({ ...s, ...change }));
  const setChild = (change: Partial<ChildFields>) => setState((s) => ({ ...s, child: { ...s.child, ...change } }));

  const startIso = parseDateFr(state.start);
  const endIso = parseDateFr(state.end);
  const birthIso = parseDateFr(state.child.birthDate) ?? title.birthDate;
  const quotient = state.quotient.trim() === "" ? null : Number(state.quotient.replace(/\s/g, ""));
  const planning = {
    mode: state.mode,
    days: state.mode === "FIXE" ? toDayInputs(state.days) : [],
    monthlyReservedMinutes: state.mode === "VARIABLE" && Number(state.monthlyHours) > 0 ? Math.round(Number(state.monthlyHours.replace(",", ".")) * 60) : null,
    planningNoticeDays: state.mode === "VARIABLE" ? state.noticeDays : null,
    familyQuotient: quotient !== null && Number.isFinite(quotient) ? quotient : null,
    billingMode: state.mode === "FIXE" ? state.billingMode : null,
  };
  const sessions: SessionInput[] = state.adaptation ? state.sessions.map((s) => ({ ...s, note: textOrNull(s.note) })) : [];
  const planningReady = state.mode === "FIXE"
    ? planning.days.length > 0 && planning.days.every((d) => span(d.arrivesAt, d.leavesAt) > 0)
    : planning.monthlyReservedMinutes !== null;
  const quoteInput = useDeferredValue(JSON.stringify({ effectiveFrom: startIso, planning, adaptationBilling: state.billing, adaptation: sessions }));
  const quote = useQuery({
    queryKey: ["contract-quote", quoteInput],
    queryFn: ({ signal }) => contractsApi.quote(JSON.parse(quoteInput), signal),
    enabled: Boolean(startIso) && planningReady,
    placeholderData: (previous) => previous,
    retry: false,
  });
  const quoteError = quote.error instanceof ApiError ? quote.error : null;
  const capacity = useQuery({
    queryKey: ["capacity-test", startIso, birthIso, state.child.sectionId, planning.days.map((d) => d.dayOfWeek).join()],
    queryFn: () => enrollmentApi.testCapacity({
      birthDate: birthIso, sectionId: state.child.sectionId || title.sectionId,
      days: state.mode === "FIXE" ? planning.days.map((d) => d.dayOfWeek) : openDays,
      desiredStart: startIso!, excludeRequestId: request?.id,
    }),
    enabled: Boolean(startIso) && (state.mode === "VARIABLE" || planning.days.length > 0),
  });

  // L'adaptation suit le début du contrat et le planning, tant qu'elle n'a pas été retouchée à la main.
  const [sessionsTouched, setSessionsTouched] = useState(source.kind === "edit");
  useEffect(() => {
    if (!sessionsTouched && startIso) {
      setState((s) => ({ ...s, sessions: adaptationSessions(startIso, s.adaptationWeeks, openDays, s.days) }));
    }
  }, [startIso, state.adaptationWeeks, state.days, sessionsTouched, openDays]);

  const childStep = withChildStep ? 0 : -1;
  const index = (n: number) => n + (withChildStep ? 1 : 0); // n : 0 planning … 3 récapitulatif
  const isLast = step === steps.length - 1;

  const stepProblem = (): string | null => {
    if (step === childStep) {
      const c = state.child;
      if (!c.firstName.trim() || !c.lastName.trim()) return "Indiquez le prénom et le nom de l’enfant.";
      const birth = parseDateFr(c.birthDate);
      if (!birth) return "Indiquez la date de naissance de l’enfant (JJ/MM/AAAA) : un contrat ne s’établit qu’après la naissance.";
      if (birth > todayIso()) return "La date de naissance ne peut pas être dans le futur.";
      if (!c.familyId && (!c.guardianFirstName.trim() || !c.guardianLastName.trim() || !c.relationship.trim())) {
        return "Indiquez le responsable légal (prénom, nom et lien).";
      }
    }
    if (step === index(0) && !planningReady) {
      return state.mode === "FIXE" ? "Cochez au moins un jour et vérifiez que chaque départ suit l’arrivée." : "Indiquez le volume d’heures réservé par mois.";
    }
    if (step === index(1) && (quote.isError || !quote.data)) return quoteError?.message ?? "Le forfait n’a pas pu être calculé.";
    if (step === index(2)) {
      if (!startIso || !endIso) return "Indiquez les dates de début et de fin au format JJ/MM/AAAA.";
      if (endIso <= startIso) return "La fin du contrat doit suivre son début.";
    }
    if (isLast && quote.data && quote.data.problems.length > 0) return quote.data.problems.join(" ");
    return null;
  };

  const next = () => {
    const problem = stepProblem();
    setError(problem);
    if (!problem) setStep((s) => Math.min(s + 1, steps.length - 1));
  };

  const draft = (): ContractDraft => ({
    startDate: startIso!,
    endDate: endIso!,
    noticeMonths: state.noticeMonths,
    planning,
    adaptationBilling: state.billing,
    adaptation: sessions,
  });

  const submit = async () => {
    const problem = stepProblem();
    setError(problem);
    if (problem) return;
    setPending(true);
    try {
      let childId: string;
      if (source.kind === "edit") {
        await contractsApi.updateDraft(source.contractId, draft());
        childId = contract!.childId;
      } else if (source.kind === "child") {
        await contractsApi.create({ childId: source.childId, contract: draft() });
        childId = source.childId;
      } else {
        const c = state.child;
        const identity = {
          firstName: c.firstName.trim(), lastName: c.lastName.trim(), birthDate: parseDateFr(c.birthDate)!,
          sectionId: c.sectionId || null, entryDate: startIso, exitDate: null, homeLanguages: null,
        };
        const { id } = await contractsApi.create({
          requestId: source.requestId,
          contract: draft(),
          newChild: c.familyId
            ? { child: identity, familyId: c.familyId }
            : {
                child: identity,
                family: {
                  family: { label: `Famille ${c.lastName.trim()}`, address: textOrNull(c.address), postalCode: textOrNull(c.postalCode), city: textOrNull(c.city), situation: null, notes: null },
                  guardian: {
                    firstName: c.guardianFirstName.trim(), lastName: c.guardianLastName.trim(), relationship: c.relationship.trim(),
                    legalGuardian: true, payer: true, mobilePhone: textOrNull(c.phone), workPhone: null, email: textOrNull(c.email),
                    employer: null, displayOrder: 0,
                  },
                },
              },
        });
        childId = (await contractsApi.detail(id)).childId;
      }
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["contracts"] }),
        queryClient.invalidateQueries({ queryKey: ["contract"] }),
        queryClient.invalidateQueries({ queryKey: ["children"] }),
        queryClient.invalidateQueries({ queryKey: ["enrollment-board"] }),
        queryClient.invalidateQueries({ queryKey: ["enrollment-request"] }),
      ]);
      navigate(`/enfants/${childId}?onglet=contrat`);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setPending(false);
    }
  };

  const section = sections.data?.find((s) => s.id === (state.child.sectionId || title.sectionId));
  const back = source.kind === "request" ? `/inscriptions/${source.requestId}`
    : source.kind === "child" ? `/enfants/${source.childId}?onglet=contrat` : `/enfants/${contract!.childId}?onglet=contrat`;
  const weekly = weeklyMinutes(state.days);
  const dayCount = Object.values(state.days).filter((d) => d.on).length;
  const opening = structure.data?.openingHours ?? [];
  const age = birthIso && startIso ? ageInMonths(birthIso, startIso) : null;

  return (
    <>
      <nav aria-label="Fil d’Ariane" className="text-label text-muted-foreground">
        <Link to={back} className="text-lagon-700">{source.kind === "request" ? "Inscriptions" : "Enfants"}</Link> › {title.name} › {source.kind === "edit" ? `Contrat n° ${contract!.number}` : "Nouveau contrat d’accueil"}
      </nav>
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div className="flex flex-col gap-1">
          <h1 className="m-0 font-display text-display">{source.kind === "edit" ? "Modifier le contrat d’accueil" : "Nouveau contrat d’accueil"}</h1>
          <p className="m-0 flex flex-wrap items-center gap-2 text-muted-foreground">
            <strong className="text-foreground">{state.child.firstName || title.name.split(" ")[0]} {state.child.lastName || title.name.split(" ").slice(1).join(" ")}</strong>
            <SectionBadge section={section} />
            {age !== null && birthIso && <span>{formatAge(age)} au début du contrat · né(e) le {isoToDateFr(birthIso)}</span>}
          </p>
        </div>
        <Button variant="ghost" asChild><Link to={back}>Annuler</Link></Button>
      </div>

      <ol aria-label="Étapes du contrat" className="m-0 flex list-none flex-wrap gap-2 p-0">
        {steps.map(([label, sub], i) => (
          <li key={label}>
            <button
              type="button"
              aria-current={i === step ? "step" : undefined}
              disabled={i > step}
              onClick={() => setStep(i)}
              className={cn("flex items-center gap-2 rounded-md px-3 py-2 text-left", i === step ? "bg-lagon-100" : "disabled:opacity-60")}
            >
              <span aria-hidden className={cn("inline-flex size-6 items-center justify-center rounded-full text-small font-bold",
                i < step ? "bg-success-100 text-success-700" : i === step ? "bg-lagon-600 text-white" : "bg-muted text-muted-foreground")}>
                {i < step ? <Check className="size-3.5" /> : i + 1}
              </span>
              <span>
                <span className={cn("block text-label", i === step ? "text-lagon-700" : "")}>{label}</span>
                <span className="block text-small text-muted-foreground">{sub}</span>
              </span>
            </button>
          </li>
        ))}
      </ol>

      <div className="grid grid-cols-1 items-start gap-4 xl:grid-cols-[minmax(0,1fr)_340px]">
        <div className="flex min-w-0 flex-col gap-4">
          {step === childStep && <ChildStep child={state.child} onChange={setChild} sections={sections.data ?? []} startIso={startIso} />}

          {step === index(0) && (
            <Card title="Planning type" subtitle="Indiquez les jours et les horaires d’accueil prévus. Le forfait mensuel est calculé à partir de ce planning.">
              <div role="radiogroup" aria-label="Type de planning" className="flex gap-2">
                {(["FIXE", "VARIABLE"] as const).map((m) => (
                  <label key={m} className={cn("inline-flex h-control items-center gap-2 rounded-md border px-3", state.mode === m ? "border-lagon-600 bg-lagon-100 text-lagon-700" : "border-input")}>
                    <input type="radio" name="mode" checked={state.mode === m} onChange={() => set({ mode: m })} className="accent-lagon-600" />
                    {m === "FIXE" ? "Planning fixe" : "Planning variable"}
                  </label>
                ))}
              </div>
              {state.mode === "FIXE" ? (
                <>
                  <PlanningEditor days={state.days} onChange={(days) => set({ days })} opening={opening} />
                  <dl className="m-0 grid grid-cols-3 gap-3">
                    <Fact label="Total hebdomadaire" value={formatMinutes(weekly)} />
                    <Fact label="Jours d’accueil" value={`${dayCount} sur ${openDays.length}`} />
                    <Fact label="Moyenne par jour" value={dayCount ? formatMinutes(Math.round(weekly / dayCount)) : "—"} />
                  </dl>
                  {(request || contract) && (
                    <Button variant="link" size="sm" className="self-start" onClick={() => set({ days: initial.days })}>
                      <RotateCcw aria-hidden />
                      Rétablir le planning proposé
                    </Button>
                  )}
                </>
              ) : (
                <>
                  <p className="m-0 text-muted-foreground">
                    Travail posté, roulements, gardes : la famille réserve un volume d’heures et transmet son planning chaque mois.
                    Les heures réservées non utilisées restent dues ; les heures en plus sont facturées au tarif horaire du barème.
                  </p>
                  <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                    <FormField label="Heures réservées par mois">
                      <Input inputMode="decimal" value={state.monthlyHours} onChange={(e) => set({ monthlyHours: e.target.value })} />
                    </FormField>
                    <FormField label="Planning transmis">
                      <select className={selectClass} value={state.noticeDays} onChange={(e) => set({ noticeDays: Number(e.target.value) })}>
                        <option value={7}>7 jours avant le mois</option>
                        <option value={14}>14 jours avant le mois</option>
                        <option value={30}>1 mois avant</option>
                      </select>
                    </FormField>
                  </div>
                </>
              )}
              <p className="m-0 text-small text-muted-foreground">
                Horaires d’ouverture : {opening.map((o) => `${DAY_NAMES[o.dayOfWeek - 1].toLowerCase()} ${hhmm(o.opensAt)}–${hhmm(o.closesAt)}`).join(", ")}.
                Les horaires se saisissent au quart d’heure.
              </p>
            </Card>
          )}

          {step === index(1) && (
            <Card title="Forfait mensuel" subtitle="Calculé d’après le planning et le barème de la structure en vigueur au début du contrat.">
              {quote.isPending && planningReady && <p className="m-0 text-muted-foreground">Calcul en cours…</p>}
              {quoteError && <Alert>{quoteError.message}</Alert>}
              {quote.data?.criterion === "QUOTIENT_FAMILIAL" || quoteError?.code === "quotient_required" ? (
                <FormField label="Quotient familial mensuel de la famille" hint="Le barème dépend du quotient familial, en francs CFP." className="max-w-80">
                  <Input inputMode="numeric" value={state.quotient} onChange={(e) => set({ quotient: e.target.value.replace(/[^\d]/g, "") })} />
                </FormField>
              ) : null}
              {quote.data && (
                <>
                  <dl className="m-0 grid grid-cols-2 gap-3 md:grid-cols-4">
                    <Fact label="Heures par semaine" value={formatMinutes(quote.data.weeklyMinutes)} />
                    <Fact label="Semaines d’accueil par an" value={String(quote.data.weeksPerYear)} />
                    <Fact label="Heures par mois" value={formatMinutes(quote.data.monthlyMinutes)} />
                    <Fact label="Tarif horaire" value={formatXpf(quote.data.hourlyRate)} />
                  </dl>
                  <p className="m-0 rounded-md bg-muted px-4 py-3 tabular-nums">
                    {state.mode === "FIXE"
                      ? `${formatMinutes(quote.data.weeklyMinutes)} × ${quote.data.weeksPerYear} semaines ÷ 12 mois × ${formatXpf(quote.data.hourlyRate)}, arrondi au franc`
                      : `${formatMinutes(quote.data.monthlyMinutes)} réservées × ${formatXpf(quote.data.hourlyRate)}`}
                    {" "}= <strong>{formatXpf(quote.data.monthlyFee)}</strong>
                  </p>
                  {state.mode === "FIXE" && (
                    <BillingModeChoice value={state.billingMode ?? quote.data.billingMode} quote={quote.data}
                      onChange={(billingMode) => set({ billingMode })} />
                  )}
                  <table className="w-full border-collapse text-left">
                    <caption className="mb-2 text-left text-label">Barème « {quote.data.tariffLabel} »</caption>
                    <thead>
                      <tr className="border-b border-border-subtle text-label text-muted-foreground">
                        <th scope="col" className="py-2">{quote.data.criterion === "HEURES_SEMAINE" ? "Volume hebdomadaire" : "Quotient familial"}</th>
                        <th scope="col" className="py-2 text-right">Tarif horaire</th>
                        <th scope="col" className="py-2 text-right"><span className="sr-only">Statut</span></th>
                      </tr>
                    </thead>
                    <tbody>
                      {quote.data.bands.map((b) => (
                        <tr key={b.lowerBound} className={cn("border-b border-border-subtle last:border-0", b.lowerBound === quote.data!.bandLowerBound && "bg-lagon-50 font-semibold")}>
                          <td className="py-2">{bandLabel(quote.data!.criterion, quote.data!.bands, b.lowerBound)}</td>
                          <td className="py-2 text-right tabular-nums">{formatXpf(b.hourlyRate)}</td>
                          <td className="py-2 text-right">{b.lowerBound === quote.data!.bandLowerBound && <TonePill tone="success">Appliqué</TonePill>}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  {quote.data.registrationFee > 0 && <p className="m-0">Frais d’inscription, facturés avec la première mensualité : <strong className="tabular-nums">{formatXpf(quote.data.registrationFee)}</strong></p>}
                  <p className="m-0 text-small text-muted-foreground">Les aides (province, employeur) seront déduites sur les factures, avec le module Facturation.</p>
                </>
              )}
            </Card>
          )}

          {step === index(2) && (
            <>
              <Card title="Dates du contrat" subtitle="Le forfait mensuel s’applique à partir de la date de début.">
                <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
                  <FormField label="Début du contrat">
                    <Input placeholder="JJ/MM/AAAA" inputMode="numeric" value={state.start} onChange={(e) => set({ start: e.target.value })} />
                  </FormField>
                  <FormField label="Fin du contrat">
                    <Input placeholder="JJ/MM/AAAA" inputMode="numeric" value={state.end} onChange={(e) => set({ end: e.target.value })} />
                  </FormField>
                  <FormField label="Préavis de rupture">
                    <select className={selectClass} value={state.noticeMonths} onChange={(e) => set({ noticeMonths: Number(e.target.value) })}>
                      <option value={1}>1 mois</option>
                      <option value={2}>2 mois</option>
                    </select>
                  </FormField>
                </div>
                <CapacityCheck test={capacity.data ?? null} onUse={(month) => set({ start: isoToDateFr(`${month}-01`) })} />
              </Card>
              <Card title="Période d’adaptation" subtitle="Des séances de plus en plus longues, avant le début du contrat.">
                <label className="inline-flex items-center gap-2">
                  <input type="checkbox" checked={state.adaptation} onChange={(e) => set({ adaptation: e.target.checked })} className="size-4 accent-lagon-600" />
                  Prévoir une adaptation
                </label>
                {state.adaptation && (
                  <>
                    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                      <FormField label="Durée">
                        <select className={selectClass} value={state.adaptationWeeks} onChange={(e) => { setSessionsTouched(false); set({ adaptationWeeks: Number(e.target.value) as 1 | 2 }); }}>
                          <option value={1}>1 semaine</option>
                          <option value={2}>2 semaines</option>
                        </select>
                      </FormField>
                      <FormField label="Facturation de l’adaptation">
                        <select className={selectClass} value={state.billing} onChange={(e) => set({ billing: e.target.value as AdaptationBilling })}>
                          {(Object.keys(billingLabels) as AdaptationBilling[]).map((b) => (
                            <option key={b} value={b}>{billingLabels[b]}{quote.data && b !== "OFFERTE" ? `, ${formatXpf(quote.data.adaptationRate)}/h` : ""}</option>
                          ))}
                        </select>
                      </FormField>
                    </div>
                    <Sessions sessions={state.sessions} onChange={(s) => { setSessionsTouched(true); set({ sessions: s }); }} />
                    <p className="m-0 text-small text-muted-foreground">La première séance se fait avec un parent. Les horaires pourront être ajustés avec la référente de la section.</p>
                  </>
                )}
              </Card>
            </>
          )}

          {step === index(3) && quote.data && (
            <Card title="Récapitulatif" subtitle="Vérifiez le contrat. Il sera enregistré en brouillon : imprimez-le depuis la fiche de l’enfant, puis enregistrez la signature.">
              <Recap label="Planning" onEdit={() => setStep(index(0))}>
                {state.mode === "FIXE"
                  ? planning.days.map((d) => `${DAY_NAMES[d.dayOfWeek - 1]} ${d.arrivesAt}–${d.leavesAt}`).join(" · ")
                  : `Variable, ${formatMinutes(planning.monthlyReservedMinutes ?? 0)} réservées par mois`}
                <span className="block text-muted-foreground">Par semaine : {formatMinutes(quote.data.weeklyMinutes)}</span>
              </Recap>
              <Recap label="Tarification" onEdit={() => setStep(index(1))}>
                Forfait ({formatMinutes(quote.data.monthlyMinutes)} × {formatXpf(quote.data.hourlyRate)}) : <strong>{formatXpf(quote.data.monthlyFee)}</strong> par mois
                {quote.data.registrationFee > 0 && <span className="block">Frais d’inscription : {formatXpf(quote.data.registrationFee)}</span>}
              </Recap>
              <Recap label="Adaptation et dates" onEdit={() => setStep(index(2))}>
                {sessions.length > 0
                  ? `Adaptation du ${isoToDateFr(sessions[0].date)} au ${isoToDateFr(sessions[sessions.length - 1].date)}, ${sessions.length} séances : ${state.billing === "OFFERTE" ? "offerte" : formatXpf(quote.data.adaptationCost)}`
                  : "Pas d’adaptation"}
                <span className="block">Contrat du {state.start} au {state.end}, préavis {state.noticeMonths} mois</span>
              </Recap>
              <p className="m-0 text-small text-muted-foreground">Personnes autorisées à venir chercher l’enfant : à saisir dans la fiche enfant avant le premier jour.</p>
            </Card>
          )}

          {error && <Alert>{error}</Alert>}
          <div className="flex flex-wrap items-center gap-3">
            {step > 0 && (
              <Button variant="outline" onClick={() => { setError(null); setStep(step - 1); }}>
                <ArrowLeft aria-hidden />
                Étape précédente
              </Button>
            )}
            <span className="text-muted-foreground">Étape {step + 1} sur {steps.length}</span>
            {isLast ? (
              <Button onClick={() => void submit()} disabled={pending || !quote.data}>
                <Check aria-hidden />
                {pending ? "Enregistrement…" : source.kind === "edit" ? "Enregistrer le brouillon" : "Créer le contrat"}
              </Button>
            ) : (
              <Button onClick={next}>
                Étape suivante : {steps[step + 1][0].toLowerCase()}
                <ArrowRight aria-hidden />
              </Button>
            )}
          </div>
        </div>

        <aside aria-label="Synthèse du contrat" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1 xl:sticky xl:top-4">
          <h2 className="m-0 text-title-3">Synthèse du contrat</h2>
          <dl className="m-0 flex flex-col gap-2">
            <Fact label="Enfant" value={title.name} />
            <Fact label="Structure" value={structure.data?.name ?? ""} />
            <Fact label="Planning" value={state.mode === "FIXE" ? `Fixe, ${dayCount} jour${dayCount > 1 ? "s" : ""}` : `Variable, ${state.monthlyHours || "—"} h par mois`} />
            <Fact label="Heures par semaine" value={quote.data ? formatMinutes(quote.data.weeklyMinutes) : state.mode === "FIXE" ? formatMinutes(weekly) : "—"} />
            {quote.data && <Fact label="Tranche du barème" value={`${bandLabel(quote.data.criterion, quote.data.bands, quote.data.bandLowerBound)} · ${formatXpf(quote.data.hourlyRate)}/h`} />}
            <Fact label="Forfait mensuel, avant aides" value={quote.data ? formatXpf(quote.data.monthlyFee) : "—"} strong />
            {quote.data && sessions.length > 0 && <Fact label="Adaptation" value={formatXpf(quote.data.adaptationCost)} />}
          </dl>
          {quote.data && quote.data.problems.length > 0 && (
            <ul className="m-0 flex flex-col gap-1 rounded-md bg-warning-100 py-2 pr-3 pl-7 text-small text-warning-700">
              {quote.data.problems.map((p) => <li key={p}>{p}</li>)}
            </ul>
          )}
        </aside>
      </div>
    </>
  );
}

function Card({ title, subtitle, children }: { title: string; subtitle?: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-4 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <div>
        <h2 className="m-0 text-title-3">{title}</h2>
        {subtitle && <p className="mt-1 mb-0 text-muted-foreground">{subtitle}</p>}
      </div>
      {children}
    </section>
  );
}

function Fact({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <div className="flex flex-col">
      <dt className="text-small text-muted-foreground">{label}</dt>
      <dd className={cn("m-0 tabular-nums", strong && "font-display text-title-2")}>{value}</dd>
    </div>
  );
}

function Recap({ label, onEdit, children }: { label: string; onEdit: () => void; children: ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-4 border-b border-border-subtle pb-3 last:border-0">
      <div>
        <p className="m-0 text-label">{label}</p>
        <div className="tabular-nums">{children}</div>
      </div>
      <Button variant="link" size="sm" onClick={onEdit}>Modifier</Button>
    </div>
  );
}

function CapacityCheck({ test, onUse }: { test: CapacityTest | null; onUse: (month: string) => void }) {
  if (!test) return null;
  const hint = capacityHint(test, "NOUVELLE");
  return (
    <div role="status" className="flex flex-wrap items-center gap-3">
      {hint && <TonePill tone={hint.tone}>{hint.label}</TonePill>}
      {test.verdict === "ACCEPTABLE" && <span className="text-small text-muted-foreground">Une place est libre chaque jour prévu, du début du contrat à la fin des prévisions.</span>}
      {test.verdict === "LATER" && test.firstMonth && (
        <>
          <span className="text-small">
            {test.fullDays.length > 0 && `Complet le ${test.fullDays.map((d) => DAY_NAMES[d - 1].toLowerCase()).join(" et le ")} au début prévu. `}
            Tous les jours sont libres à partir de {formatMonthLong(test.firstMonth)}.
          </span>
          <Button variant="outline" size="sm" onClick={() => onUse(test.firstMonth!)}>Commencer le {isoToDateFr(`${test.firstMonth}-01`)}</Button>
        </>
      )}
    </div>
  );
}

function Sessions({ sessions, onChange }: { sessions: Session[]; onChange: (s: Session[]) => void }) {
  const total = sessions.reduce((sum, s) => sum + Math.max(0, span(s.startsAt, s.endsAt)), 0);
  const patch = (i: number, change: Partial<Session>) => onChange(sessions.map((s, j) => (j === i ? { ...s, ...change } : s)));
  return (
    <div className="flex flex-col gap-2">
      <table className="w-full border-collapse text-left">
        <caption className="sr-only">Séances d’adaptation</caption>
        <thead>
          <tr className="border-b border-border-subtle text-label text-muted-foreground">
            <th scope="col" className="py-2">Date</th>
            <th scope="col" className="py-2">Début</th>
            <th scope="col" className="py-2">Fin</th>
            <th scope="col" className="py-2">Note</th>
            <th scope="col" className="py-2"><span className="sr-only">Retirer</span></th>
          </tr>
        </thead>
        <tbody>
          {sessions.map((s, i) => (
            <tr key={s.date} className="border-b border-border-subtle last:border-0">
              <th scope="row" className="py-1.5 pr-2 font-normal tabular-nums">{DAY_NAMES[isoWeekday(s.date) - 1].slice(0, 3)}. {isoToDateFr(s.date)}</th>
              <td className="py-1.5 pr-2"><input type="time" step={900} aria-label={`Début le ${isoToDateFr(s.date)}`} value={s.startsAt} onChange={(e) => patch(i, { startsAt: e.target.value })} className="h-control rounded-md border border-input bg-card px-2" /></td>
              <td className="py-1.5 pr-2"><input type="time" step={900} aria-label={`Fin le ${isoToDateFr(s.date)}`} value={s.endsAt} onChange={(e) => patch(i, { endsAt: e.target.value })} className="h-control rounded-md border border-input bg-card px-2" /></td>
              <td className="py-1.5 pr-2"><Input aria-label={`Note du ${isoToDateFr(s.date)}`} value={s.note} maxLength={80} onChange={(e) => patch(i, { note: e.target.value })} /></td>
              <td className="py-1.5 text-right">
                <Button variant="ghost" size="sm" aria-label={`Retirer la séance du ${isoToDateFr(s.date)}`} onClick={() => onChange(sessions.filter((_, j) => j !== i))}>
                  <Trash2 aria-hidden />
                </Button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="m-0 text-muted-foreground tabular-nums">{sessions.length} séances · {formatMinutes(total)}</p>
    </div>
  );
}

function ChildStep({ child, onChange, sections, startIso }: {
  child: ChildFields;
  onChange: (c: Partial<ChildFields>) => void;
  sections: Section[];
  startIso: string | null;
}) {
  const birth = parseDateFr(child.birthDate);
  const suggested = birth && startIso ? sectionForAge(sections, ageInMonths(birth, startIso)) : undefined;
  const field = (key: keyof ChildFields, label: string, props: Record<string, string> = {}, hint?: string) => (
    <FormField label={label} hint={hint}>
      <Input value={(child[key] as string) ?? ""} onChange={(e) => onChange({ [key]: e.target.value })} {...props} />
    </FormField>
  );
  return (
    <Card title="Enfant et famille" subtitle="Les champs sont repris de la demande. Vérifiez-les : la fiche de l’enfant sera créée avec le contrat.">
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        {field("firstName", "Prénom de l’enfant")}
        {field("lastName", "Nom de l’enfant")}
        {field("birthDate", "Date de naissance", { placeholder: "JJ/MM/AAAA", inputMode: "numeric" }, child.birthDate ? undefined : "À compléter : l’enfant doit être né.")}
        <FormField label="Section" hint={suggested ? `Selon l’âge au début du contrat : ${suggested.name}` : undefined}>
          <select className={selectClass} value={child.sectionId} onChange={(e) => onChange({ sectionId: e.target.value })}>
            <option value="">À déterminer</option>
            {sections.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
        </FormField>
      </div>
      {child.familyId ? (
        <p className="m-0">Rattaché à la famille déjà accueillie : <strong>{child.familyLabel}</strong>.</p>
      ) : (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          {field("guardianFirstName", "Prénom du responsable légal")}
          {field("guardianLastName", "Nom du responsable légal")}
          {field("relationship", "Lien avec l’enfant", { list: "liens" })}
          <RelationshipOptions />
          {field("phone", "Téléphone", { inputMode: "tel" })}
          {field("email", "E-mail", { type: "email" })}
          <span />
          {field("address", "Adresse", { placeholder: "N° et rue" })}
          {field("postalCode", "Code postal", { inputMode: "numeric" })}
          {field("city", "Commune")}
        </div>
      )}
    </Card>
  );
}
