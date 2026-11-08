import type { ReactNode } from "react";
import { cn } from "@/lib/utils";
import type { Level } from "@/features/parc/api";
import type { Tone } from "@/features/parc/labels";

const tones: Record<Tone, string> = {
  ok: "bg-success-100 text-success-700",
  warn: "bg-warning-100 text-warning-700",
  bad: "bg-danger-100 text-danger-700",
  info: "bg-info-100 text-info-700",
  lagon: "bg-lagon-100 text-lagon-700",
  neutral: "bg-muted text-muted-foreground",
};

/** Pastille de texte de la maquette (.pill). */
export function Pill({ tone, children, className }: { tone: Tone; children: ReactNode; className?: string }) {
  return (
    <span className={cn("inline-flex items-center rounded-full px-2.5 py-0.5 text-small font-semibold whitespace-nowrap", tones[tone], className)}>
      {children}
    </span>
  );
}

const dots: Record<Level, string> = {
  OK: "bg-success-600",
  WARN: "bg-warning-500",
  BAD: "bg-danger-600",
  OFF: "bg-border-control",
};

const dotLabels: Record<Level, string> = { OK: "En ordre", WARN: "À surveiller", BAD: "En alerte", OFF: "Arrêtée ou hors parc" };

/** Pastille de santé d'une crèche (.health) : vert, orange, rouge, gris. */
export function HealthDot({ level, className }: { level: Level; className?: string }) {
  return (
    <span
      role="img"
      aria-label={dotLabels[level]}
      title={dotLabels[level]}
      className={cn("inline-block size-2.5 shrink-0 rounded-full", dots[level], className)}
    />
  );
}
