/** Fuseau de référence de toutes les structures (Nouvelle-Calédonie). */
export const TIME_ZONE = "Pacific/Noumea";
const LOCALE = "fr-FR";

function parts(date: Date, options: Intl.DateTimeFormatOptions) {
  const out: Record<string, string> = {};
  for (const p of new Intl.DateTimeFormat(LOCALE, { timeZone: TIME_ZONE, ...options }).formatToParts(date)) {
    out[p.type] = p.value;
  }
  return out;
}

function capitalize(value: string) {
  return value.charAt(0).toUpperCase() + value.slice(1);
}

/** 30/10/2026 */
export function formatDate(date: Date): string {
  const p = parts(date, { day: "2-digit", month: "2-digit", year: "numeric" });
  return `${p.day}/${p.month}/${p.year}`;
}

/** 09:15 */
export function formatTime(date: Date): string {
  const p = parts(date, { hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
  return `${p.hour}:${p.minute}`;
}

/** Vendredi 30 octobre 2026 */
export function formatLongDate(date: Date): string {
  const p = parts(date, { weekday: "long", day: "numeric", month: "long", year: "numeric" });
  return `${capitalize(p.weekday)} ${p.day} ${p.month} ${p.year}`;
}

/** « 15/01/2024 » → « 2024-01-15 » ; null si la date est mal saisie ou n'existe pas. */
export function parseDateFr(input: string): string | null {
  const match = /^(\d{1,2})\/(\d{1,2})\/(\d{4})$/.exec(input.trim());
  if (!match) return null;
  const [, d, m, y] = match.map(Number);
  const date = new Date(Date.UTC(y, m - 1, d));
  if (date.getUTCFullYear() !== y || date.getUTCMonth() !== m - 1 || date.getUTCDate() !== d) return null;
  return `${y}-${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}`;
}

/** « 2024-01-15 » → « 15/01/2024 » */
export function isoToDateFr(iso: string): string {
  const [y, m, d] = iso.split("-");
  return `${d}/${m}/${y}`;
}

/** Ven. 30/10 · 09:15 (barre supérieure du poste bureau) */
export function formatTopbarStamp(date: Date): string {
  const p = parts(date, { weekday: "short", day: "2-digit", month: "2-digit" });
  const weekday = capitalize(p.weekday.replace(/\.$/, ""));
  return `${weekday}. ${p.day}/${p.month} · ${formatTime(date)}`;
}
