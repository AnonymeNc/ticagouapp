import type { ReactNode } from "react";
import { NavLink } from "react-router";
import { CalendarDays, CircleCheck, CircleX, Clock, Hand, Inbox, Phone, TriangleAlert, type LucideIcon } from "lucide-react";
import { ageInMonths, formatAge, todayIso } from "@/lib/age";
import { isoToDateFr } from "@/lib/datetime";
import { DAY_LETTERS, DAY_NAMES, daysBetween, formatMonthLong, formatVisit } from "@/lib/format";
import { cn } from "@/lib/utils";
import { stageLabels, type CapacityTest, type RequestCard, type Stage } from "./api";

export type Tone = "success" | "warning" | "info" | "danger" | "neutral";

const toneClasses: Record<Tone, string> = {
  success: "bg-success-100 text-success-700",
  warning: "bg-warning-100 text-warning-700",
  info: "bg-info-100 text-info-700",
  danger: "bg-danger-100 text-danger-700",
  neutral: "bg-muted text-muted-foreground",
};
const toneIcons: Record<Tone, LucideIcon> = {
  success: CircleCheck,
  warning: TriangleAlert,
  info: Clock,
  danger: CircleX,
  neutral: Clock,
};

export function TonePill({ tone, icon, children }: { tone: Tone; icon?: LucideIcon; children: ReactNode }) {
  const Icon = icon ?? toneIcons[tone];
  return (
    <span className={cn("inline-flex items-center gap-1 self-start rounded-sm px-2 py-0.5 text-small font-semibold", toneClasses[tone])}>
      <Icon aria-hidden className="size-3.5 shrink-0" />
      {children}
    </span>
  );
}

export const stageIcons: Record<Stage, LucideIcon> = {
  NOUVELLE: Inbox,
  A_RECONTACTER: Phone,
  VISITE_PREVUE: CalendarDays,
  PLACE_PROPOSEE: Hand,
  SIGNEE: CircleCheck,
  REFUSEE: CircleX,
};

export function StagePill({ stage }: { stage: Stage }) {
  const tone: Tone = stage === "SIGNEE" ? "success" : stage === "REFUSEE" ? "danger" : "info";
  return <TonePill tone={tone} icon={stageIcons[stage]}>{stageLabels[stage]}</TonePill>;
}

/** Jours souhaités en pastilles L M M J V ; le libellé complet est lu par les lecteurs d'écran. */
export function DayChips({ days, openDays = [1, 2, 3, 4, 5] }: { days: number[]; openDays?: number[] }) {
  return (
    <span className="inline-flex gap-1">
      <span className="sr-only">Jours souhaités : {[...days].sort().map((d) => DAY_NAMES[d - 1].toLowerCase()).join(", ")}</span>
      {openDays.map((d) => (
        <span
          key={d}
          aria-hidden
          className={cn(
            "inline-flex size-[22px] items-center justify-center rounded-sm text-small font-semibold",
            days.includes(d) ? "bg-lagon-100 text-lagon-700" : "bg-muted text-muted-foreground",
          )}
        >
          {DAY_LETTERS[d - 1]}
        </span>
      ))}
    </span>
  );
}

/** « 18 mois à l'entrée » ou « Naissance prévue le 12/01/2027 ». */
export function ageLabel(r: { birthDate: string | null; expectedBirthDate: string | null; desiredStart: string }) {
  if (!r.birthDate) return `Naissance prévue le ${isoToDateFr(r.expectedBirthDate!)}`;
  return `${formatAge(ageInMonths(r.birthDate, r.desiredStart))} à l’entrée`;
}

/** Places libres pour la demande, d'après la vue Capacité. */
export function capacityHint(test: CapacityTest | null, stage: Stage): { tone: Tone; label: string } | null {
  if (stage === "PLACE_PROPOSEE") return { tone: "success", label: "Place réservée" };
  if (!test) return null;
  switch (test.verdict) {
    case "ACCEPTABLE":
      return { tone: "success", label: "Place disponible" };
    case "LATER":
      return { tone: "warning", label: `Possible dès ${formatMonthLong(test.firstMonth!).split(" ")[0]}` };
    case "NONE":
      return { tone: "danger", label: "Complet sur les 6 prochains mois" };
    case "OUT_OF_RANGE":
      return { tone: "info", label: "Entrée au-delà des 6 prochains mois" };
  }
}

/** Pied de carte : la prochaine chose à faire ou la dernière étape franchie. */
export function cardFooter(card: RequestCard, today = todayIso()): { tone: Tone; label: string; icon: LucideIcon } {
  switch (card.stage) {
    case "NOUVELLE":
      return { tone: "info", icon: Inbox, label: `Reçue le ${isoToDateFr(card.receivedOn)}` };
    case "A_RECONTACTER": {
      if (card.followUpOn && card.followUpOn < today) {
        const late = daysBetween(card.followUpOn, today);
        return { tone: "warning", icon: TriangleAlert, label: `À relancer depuis ${late} jour${late > 1 ? "s" : ""}` };
      }
      return card.followUpOn
        ? { tone: "info", icon: Phone, label: `Relance prévue le ${isoToDateFr(card.followUpOn)}` }
        : { tone: "info", icon: Phone, label: "À recontacter" };
    }
    case "VISITE_PREVUE":
      if (!card.visitAt) return { tone: "info", icon: CalendarDays, label: "Visite à fixer" };
      return card.visitDone
        ? { tone: "success", icon: CircleCheck, label: `Visite faite (${formatVisit(card.visitAt)})` }
        : { tone: "info", icon: CalendarDays, label: `Visite ${formatVisit(card.visitAt)}` };
    case "PLACE_PROPOSEE":
      return card.offerExpiresOn
        ? { tone: "warning", icon: TriangleAlert, label: `Réponse attendue avant le ${isoToDateFr(card.offerExpiresOn)}` }
        : { tone: "info", icon: Hand, label: `Proposée le ${isoToDateFr(card.stageChangedOn)}` };
    case "SIGNEE":
      return { tone: "success", icon: CircleCheck, label: `Signé le ${isoToDateFr(card.stageChangedOn)}` };
    case "REFUSEE":
      return { tone: "danger", icon: CircleX, label: `Refusée le ${isoToDateFr(card.stageChangedOn)}` };
  }
}

export const footerColors: Record<Tone, string> = {
  success: "text-success-700",
  warning: "text-warning-700",
  info: "text-info-700",
  danger: "text-danger-700",
  neutral: "text-muted-foreground",
};

/** En-tête commun du module : titre, nouvelle demande, onglets Demandes / Capacité. */
export function EnrollmentHeader({ subtitle, actions }: { subtitle: string; actions?: ReactNode }) {
  const tab = ({ isActive }: { isActive: boolean }) =>
    cn("inline-flex h-control items-center rounded-full px-3.5 text-label", isActive ? "bg-card text-lagon-700 shadow-1" : "text-foreground");
  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="m-0 font-display text-display">Inscriptions</h1>
          <p className="mt-1 mb-0 text-muted-foreground">{subtitle}</p>
        </div>
        {actions}
      </div>
      <nav aria-label="Vues des inscriptions" className="flex max-w-full gap-1 self-start overflow-x-auto rounded-[14px] bg-muted p-1 [&>*]:shrink-0 [&>*]:whitespace-nowrap">
        <NavLink to="/inscriptions" end className={tab}>Demandes</NavLink>
        <NavLink to="/inscriptions/capacite" className={tab}>Capacité</NavLink>
      </nav>
    </>
  );
}
