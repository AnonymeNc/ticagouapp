import type { ClientKind, Level, Lifecycle, Row } from "./api";
import { formatDate, formatTime } from "@/lib/format";

export type Tone = "ok" | "warn" | "bad" | "info" | "lagon" | "neutral";

export const levelLabels: Record<Level, string> = {
  OK: "En ordre",
  WARN: "À surveiller",
  BAD: "En alerte",
  OFF: "Arrêtée ou hors parc",
};

export const lifecycleLabels: Record<Lifecycle, string> = { PROSPECT: "Prospect", CLIENT: "Client", RESILIE: "Résilié", DEMO: "Démonstration" };

export const kindLabels: Record<ClientKind, string> = {
  PRIVE: "Privé",
  ASSOCIATION: "Association",
  COLLECTIVITE: "Collectivité",
  ENTREPRISE: "Crèche d’entreprise",
};

export const moduleLabels: Record<string, string> = {
  INSCRIPTIONS: "Inscriptions",
  FACTURATION: "Facturation",
  COMPTABILITE: "Comptabilité",
  PERSONNEL: "Personnel",
  REPAS: "Repas",
  PORTAIL_PARENTS: "Portail parents",
};

export const containerLabels: Record<string, string> = {
  healthy: "sain",
  unhealthy: "en échec",
  starting: "démarrage",
  running: "lancé",
  exited: "arrêté",
  created: "créé, non lancé",
  paused: "en pause",
  restarting: "redémarre",
  dead: "hors service",
  absent: "absent de l’hôte",
};

/** « 2026-10-31 » → « 31/10/2026 ». */
export function formatDay(day: string | null | undefined): string {
  if (!day) {
    return "—";
  }
  const [y, m, d] = day.slice(0, 10).split("-");
  return `${d}/${m}/${y}`;
}

/** « il y a 3 h 10 », « il y a 2 j » ; l'instant est un ISO 8601. */
export function ago(at: string | null | undefined, now: Date = new Date()): string {
  if (!at) {
    return "jamais";
  }
  const minutes = Math.max(0, Math.round((now.getTime() - new Date(at).getTime()) / 60_000));
  if (minutes < 1) {
    return "à l’instant";
  }
  if (minutes < 60) {
    return `il y a ${minutes} min`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 48) {
    const rest = minutes % 60;
    return `il y a ${hours} h${rest ? ` ${String(rest).padStart(2, "0")}` : ""}`;
  }
  return `il y a ${Math.floor(hours / 24)} j`;
}

/** Heure si c'est aujourd'hui (Nouméa), sinon date. */
export function shortStamp(at: string | null | undefined, now: Date = new Date()): string {
  if (!at) {
    return "—";
  }
  const date = new Date(at);
  return formatDate(date) === formatDate(now) ? formatTime(date) : formatDate(date);
}

export function gigabytes(bytes: number | null | undefined): string {
  if (bytes == null) {
    return "—";
  }
  return `${(bytes / 1024 ** 3).toLocaleString("fr-FR", { maximumFractionDigits: 1 })} Go`;
}

/** Pastille et sous-ligne de la colonne « Abonnement ». */
export function subscriptionPill(row: Pick<Row, "lifecycle" | "licenseState" | "validUntil" | "deadline">): { tone: Tone; label: string; sub: string | null } {
  if (row.lifecycle === "PROSPECT") {
    return { tone: "neutral", label: "Prospect", sub: null };
  }
  if (row.lifecycle === "RESILIE") {
    return { tone: "neutral", label: "Résilié", sub: null };
  }
  if (row.lifecycle === "DEMO") {
    return { tone: "lagon", label: "Démonstration", sub: null };
  }
  const until = row.validUntil ? formatDay(row.validUntil) : null;
  switch (row.licenseState) {
    case "EVALUATION":
      return { tone: "lagon", label: "Évaluation", sub: row.deadline ? `fin le ${formatDay(row.deadline)}` : null };
    case "EVALUATION_TERMINEE":
      return { tone: "bad", label: "Évaluation finie", sub: "lecture seule" };
    case "ACTIVE":
      return { tone: "ok", label: "Actif", sub: until ? `→ ${until}` : null };
    case "A_RENOUVELER":
      return { tone: "info", label: "À renouveler", sub: until ? `→ ${until}` : null };
    case "EXPIREE_GRACE":
      return { tone: "warn", label: "Grâce", sub: until ? `échu le ${until}` : null };
    case "LECTURE_SEULE":
      return { tone: "bad", label: "Lecture seule", sub: until ? `échu le ${until}` : null };
    default:
      return until ? { tone: "neutral", label: "Abonnement", sub: `→ ${until}` } : { tone: "neutral", label: "—", sub: null };
  }
}

/** Pastille de la colonne « Sauvegarde » : heure de la dernière réussite, ou retard. */
export function backupPill(row: Pick<Row, "backupAt" | "backupStale">, now: Date = new Date()): { tone: Tone; label: string } {
  if (!row.backupAt) {
    return row.backupStale ? { tone: "bad", label: "Aucune" } : { tone: "neutral", label: "—" };
  }
  if (row.backupStale) {
    const hours = Math.floor((now.getTime() - new Date(row.backupAt).getTime()) / 3_600_000);
    return { tone: "bad", label: `Retard ${hours} h` };
  }
  return { tone: "ok", label: shortStamp(row.backupAt, now) };
}

export function diskTone(percent: number | null | undefined): Tone {
  if (percent == null) {
    return "neutral";
  }
  return percent >= 90 ? "bad" : percent >= 60 ? "warn" : "ok";
}
