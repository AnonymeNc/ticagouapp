import type { ReactNode } from "react";
import { HeartPulse, ShieldAlert, TriangleAlert, Utensils } from "lucide-react";
import { useAuthorizedImage } from "@/hooks/useAuthorizedImage";
import { initials } from "@/lib/initials";
import { cn } from "@/lib/utils";
import { softColor, type Section } from "@/features/structure/useSections";
import { isoToDateFr } from "@/lib/datetime";
import { severityLabels, type ChildRecord, type HealthSummary } from "./api";

const FALLBACK_COLOR = "#0b6e72";

/** Photo de l'enfant, ou ses initiales sur la couleur de sa section. */
export function ChildAvatar({
  child,
  section,
  size = "sm",
  photoVersion,
}: {
  child: { id: string; firstName: string; lastName: string; hasPhoto: boolean };
  section?: Section;
  size?: "sm" | "lg";
  photoVersion?: unknown;
}) {
  const photo = useAuthorizedImage(child.hasPhoto ? `/children/${child.id}/photo` : null, photoVersion);
  const color = section?.color ?? FALLBACK_COLOR;
  const name = `${child.firstName} ${child.lastName}`;
  const dimension = size === "lg" ? "size-22 text-[30px] border-3" : "size-9 text-small border-2";
  return (
    <span
      role="img"
      aria-label={photo ? `Photo de ${name}` : `Initiales de ${name}`}
      className={cn("inline-flex shrink-0 items-center justify-center overflow-hidden rounded-full font-bold", dimension)}
      style={{ borderColor: color, backgroundColor: softColor(color), color }}
    >
      {photo ? <img src={photo} alt="" className="size-full object-cover" /> : initials(name)}
    </span>
  );
}

export function SectionBadge({ section }: { section?: Section }) {
  if (!section) return <span className="text-muted-foreground">—</span>;
  return (
    <span
      className="inline-flex h-6 items-center gap-1.5 rounded-sm px-2 text-caption"
      style={{ backgroundColor: softColor(section.color), color: section.color }}
    >
      <span aria-hidden className="size-2 rounded-full" style={{ backgroundColor: section.color }} />
      {section.name}
    </span>
  );
}

function Pill({ tone, children, title }: { tone: "danger" | "info" | "niaouli" | "neutral"; children: ReactNode; title?: string }) {
  const tones = {
    danger: "bg-danger-100 text-danger-700",
    info: "bg-info-100 text-info-700",
    niaouli: "bg-niaouli-100 text-niaouli-700",
    neutral: "bg-muted text-muted-foreground",
  };
  return (
    <span title={title} className={cn("inline-flex h-6 items-center gap-1 rounded-full px-2 text-caption", tones[tone])}>
      {children}
    </span>
  );
}

/** Badges santé de la liste : allergie, PAI, régime — ou « Rien à signaler ». */
export function HealthBadges({ health }: { health: HealthSummary }) {
  const nothing = health.allergies.length === 0 && !health.pai && !health.diet;
  return (
    <div className="flex flex-wrap gap-1">
      {health.allergies.length > 0 && (
        <Pill tone="danger" title={health.allergies.map((a) => a.allergen).join(", ")}>
          <TriangleAlert aria-hidden className="size-3.5" />
          Allergie : {health.allergies.map((a) => a.allergen).join(", ")}
        </Pill>
      )}
      {health.pai && (
        <Pill tone="info">
          <ShieldAlert aria-hidden className="size-3.5" />
          PAI
        </Pill>
      )}
      {health.diet && (
        <Pill tone="niaouli" title={health.diet}>
          <Utensils aria-hidden className="size-3.5" />
          {health.diet}
        </Pill>
      )}
      {nothing && <Pill tone="neutral">Rien à signaler</Pill>}
    </div>
  );
}

/**
 * Bandeau « Santé : à connaître avant toute prise en charge » (maquette Fiche enfant), affiché au-dessus
 * des onglets dès qu'il y a une allergie, un PAI, un régime ou un protocole d'urgence.
 */
export function CriticalHealth({ record, onShowHealth }: { record: ChildRecord; onShowHealth: () => void }) {
  const h = record.health;
  const severe = h.allergies.some((a) => a.severity === "SEVERE");
  const cards: ReactNode[] = [];
  if (h.allergies.length > 0) {
    cards.push(
      <Card key="allergy" tone="danger" label={severe ? "Allergie sévère" : "Allergie"}
        title={h.allergies.map((a) => a.allergen).join(" et ")}
        detail={h.allergies.map((a) => a.reaction).filter(Boolean).join(" ") ||
          h.allergies.map((a) => `${a.allergen} : ${severityLabels[a.severity].toLowerCase()}`).join(", ")} />,
    );
  }
  if (h.paiActive) {
    cards.push(
      <Card key="pai" tone="info" label="PAI actif" title={h.paiKitLocation ? `Trousse : ${h.paiKitLocation}` : h.paiReason ?? "Projet d’accueil individualisé"}
        detail={[h.paiKitDetails, h.paiValidUntil ? `Valable jusqu’au ${isoToDateFr(h.paiValidUntil)}.` : null].filter(Boolean).join(" ")} />,
    );
  }
  if (h.diet) {
    cards.push(<Card key="diet" tone="niaouli" label="Régime" title={h.diet} detail={h.dietDetails ?? ""} />);
  }
  if (h.emergencyProtocol) {
    const payer = record.family.guardians.find((g) => g.legalGuardian && g.mobilePhone);
    cards.push(
      <Card key="protocol" tone="danger" label="En cas de réaction" title={h.emergencyProtocol}
        detail={payer ? `Puis prévenir ${payer.firstName} ${payer.lastName} au ${payer.mobilePhone} et la direction.` : ""} />,
    );
  }
  if (cards.length === 0) return null;

  return (
    <section aria-labelledby="sante-critique" className="flex flex-col gap-3 rounded-lg border border-danger-700 bg-danger-100 p-4">
      <div className="flex flex-wrap items-center gap-2">
        <h2 id="sante-critique" className="m-0 mr-auto flex items-center gap-2 text-title-3 text-danger-700">
          <HeartPulse aria-hidden className="size-5" />
          Santé : à connaître avant toute prise en charge
        </h2>
        <button
          type="button"
          onClick={onShowHealth}
          className="h-8 rounded-md border border-danger-700 bg-card px-2.5 text-label text-danger-700"
        >
          Voir l’onglet Santé
        </button>
      </div>
      <div className="grid grid-cols-[repeat(auto-fit,minmax(230px,1fr))] gap-3">{cards}</div>
    </section>
  );
}

function Card({ tone, label, title, detail }: { tone: "danger" | "info" | "niaouli"; label: string; title: string; detail: string }) {
  return (
    <div className="flex flex-col gap-1.5 rounded-md bg-card p-3">
      <span className="self-start">
        <Pill tone={tone}>{label}</Pill>
      </span>
      <strong className="font-semibold">{title}</strong>
      {detail && <span className="text-small text-muted-foreground">{detail}</span>}
    </div>
  );
}
