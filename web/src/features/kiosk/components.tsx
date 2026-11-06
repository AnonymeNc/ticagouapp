import type { ReactNode } from "react";
import { ArrowLeft, HeartPulse, ShieldAlert, Soup } from "lucide-react";
import { ageInMonths, formatAge, todayIso } from "@/lib/age";
import { formatTime } from "@/lib/datetime";
import { cn } from "@/lib/utils";
import type { KioskChild, KioskHealth } from "./api";

/** « 07:42 » depuis un instant ISO, à l'heure de Nouméa. */
export const hm = (iso: string) => formatTime(new Date(iso));

/** « 13:30:00 » → « 13:30 » */
export const hhmm = (time: string) => time.slice(0, 5);

export function childAge(child: KioskChild) {
  return formatAge(ageInMonths(child.birthDate, todayIso()));
}

export function Initials({ name, size = "md", tone = "lagon" }: { name: string; size?: "md" | "lg"; tone?: "lagon" | "muted" }) {
  const letters = name.split(" ").map((p) => p.charAt(0)).join("").slice(0, 2).toUpperCase();
  return (
    <span
      aria-hidden
      className={cn(
        "flex shrink-0 items-center justify-center rounded-full font-bold",
        size === "lg" ? "size-16 text-touch-body" : "size-12 text-touch-label",
        tone === "lagon" ? "bg-lagon-100 text-lagon-700" : "bg-muted text-muted-foreground",
      )}
    >
      {letters}
    </span>
  );
}

/** Pastilles santé visibles partout où l'enfant apparaît (CLAUDE.md). */
export function HealthBadges({ health }: { health: KioskHealth }) {
  return (
    <span className="flex flex-wrap gap-1">
      {health.allergies.length > 0 && (
        <span className="inline-flex items-center gap-1 rounded-sm bg-danger-100 px-2 py-0.5 text-small font-semibold text-danger-700">
          <ShieldAlert aria-hidden className="size-3.5" />
          Allergie
        </span>
      )}
      {health.pai && (
        <span className="inline-flex items-center gap-1 rounded-sm bg-warning-100 px-2 py-0.5 text-small font-semibold text-warning-700">
          <HeartPulse aria-hidden className="size-3.5" />
          PAI
        </span>
      )}
      {health.diet && (
        <span className="inline-flex items-center gap-1 rounded-sm bg-info-100 px-2 py-0.5 text-small font-semibold text-info-700">
          <Soup aria-hidden className="size-3.5" />
          Régime
        </span>
      )}
    </span>
  );
}

/** Détails de santé affichés à l'arrivée et au départ. */
export function HealthDetails({ health }: { health: KioskHealth }) {
  const lines: { label: string; text: string; tone: "danger" | "warning" | "info" }[] = [];
  for (const allergy of health.allergies) {
    lines.push({
      label: "Allergie",
      text: `${allergy.allergen}.${allergy.reaction ? ` ${allergy.reaction}` : ""}`,
      tone: "danger",
    });
  }
  if (health.pai) {
    lines.push({
      label: "PAI",
      text: [health.paiReason, health.paiKitLocation && `trousse d’urgence : ${health.paiKitLocation}`].filter(Boolean).join(" · ") || "en cours",
      tone: "warning",
    });
  }
  if (health.diet) lines.push({ label: "Régime", text: [health.diet, health.dietDetails].filter(Boolean).join(" : "), tone: "info" });
  if (health.instructions) lines.push({ label: "Consigne", text: health.instructions, tone: "info" });
  if (lines.length === 0) return null;
  return (
    <ul className="m-0 flex list-none flex-col gap-2 p-0">
      {lines.map((line, i) => (
        <li
          key={i}
          className={cn(
            "rounded-md px-4 py-3 text-touch-label font-normal",
            line.tone === "danger" ? "bg-danger-100 text-danger-700" : line.tone === "warning" ? "bg-warning-100 text-warning-700" : "bg-info-100 text-info-700",
          )}
        >
          <strong className="font-semibold">{line.label} :</strong> {line.text}
        </li>
      ))}
    </ul>
  );
}

export function BackButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} className="flex h-touch items-center gap-2 self-start rounded-md px-2 text-touch-label text-lagon-700">
      <ArrowLeft aria-hidden className="size-6" />
      {label}
    </button>
  );
}

export function TouchButton({
  children,
  variant = "primary",
  className,
  ...props
}: React.ComponentProps<"button"> & { variant?: "primary" | "secondary" | "danger" }) {
  return (
    <button
      type="button"
      {...props}
      className={cn(
        "flex h-touch-lg items-center justify-center gap-2 rounded-lg px-6 text-touch-label disabled:opacity-50",
        variant === "primary" && "bg-lagon-600 text-white active:bg-lagon-700",
        variant === "secondary" && "border border-border bg-card text-foreground shadow-1 active:bg-lagon-50",
        variant === "danger" && "border border-danger-700 bg-card text-danger-700 active:bg-danger-100",
        className,
      )}
    >
      {children}
    </button>
  );
}

/** Pastille sélectionnable (Déposé par, consignes). */
export function Chip({ selected, onClick, children }: { selected: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      onClick={onClick}
      className={cn(
        "flex h-touch items-center rounded-full border px-5 text-touch-label",
        selected ? "border-lagon-600 bg-lagon-100 text-lagon-700" : "border-border bg-card text-foreground",
      )}
    >
      {children}
    </button>
  );
}
