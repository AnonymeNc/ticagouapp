const ZONE = "Pacific/Noumea";

/** « Lundi 09/11/2026 · 08:47 », heure de Nouméa. */
export function formatStamp(date: Date): string {
  const day = new Intl.DateTimeFormat("fr-FR", { weekday: "long", timeZone: ZONE }).format(date);
  return `${day.charAt(0).toUpperCase()}${day.slice(1)} ${formatDate(date)} · ${formatTime(date)}`;
}

/** JJ/MM/AAAA. */
export function formatDate(date: Date): string {
  return new Intl.DateTimeFormat("fr-FR", { day: "2-digit", month: "2-digit", year: "numeric", timeZone: ZONE }).format(date);
}

/** HH:MM. */
export function formatTime(date: Date): string {
  return new Intl.DateTimeFormat("fr-FR", { hour: "2-digit", minute: "2-digit", timeZone: ZONE }).format(date);
}

export function formatDateTime(date: Date): string {
  return `${formatDate(date)} ${formatTime(date)}`;
}

export function initials(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join("");
}
