import { TIME_ZONE } from "./datetime";

/** 78901 → « 78 901 F » (francs CFP, sans décimale). */
export function formatXpf(amount: number): string {
  const digits = Math.abs(Math.round(amount)).toString().replace(/\B(?=(\d{3})+(?!\d))/g, " ");
  return `${amount < 0 ? "− " : ""}${digits} F`;
}

/** 2370 → « 39 h 30 » */
export function formatMinutes(minutes: number): string {
  const m = Math.max(0, Math.round(minutes));
  return `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, "0")}`;
}

/** Écart signé : 120 → « + 2 h 00 », −90 → « − 1 h 30 » */
export function formatMinutesDelta(minutes: number): string {
  return `${minutes < 0 ? "−" : "+"} ${formatMinutes(Math.abs(minutes))}`;
}

/** « 07:30:00 » ou « 07:30 » → « 07:30 » */
export const hhmm = (time: string) => time.slice(0, 5);

/** « 07:30 » → 450 */
export function timeToMinutes(time: string): number {
  const [h, m] = time.split(":").map(Number);
  return h * 60 + m;
}

/** Durée entre deux horaires « HH:MM », en minutes (négative si le départ précède l'arrivée). */
export const span = (from: string, to: string) => timeToMinutes(to) - timeToMinutes(from);

export const DAY_NAMES = ["Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi", "Dimanche"];
export const DAY_SHORT = ["Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim"];
export const DAY_LETTERS = ["L", "M", "M", "J", "V", "S", "D"];
/** Jour ISO (1 = lundi) → « Lundi » */
export const dayName = (day: number) => DAY_NAMES[day - 1];

/** Liste de jours ISO → « Lun à ven » ou « Lun, mar, jeu » */
export function formatDays(days: number[]): string {
  const sorted = [...days].sort();
  if (sorted.length === 5 && sorted.join() === "1,2,3,4,5") return "Lun à ven";
  return sorted.map((d, i) => (i === 0 ? DAY_SHORT[d - 1] : DAY_SHORT[d - 1].toLowerCase())).join(", ");
}

/** « 2026-11 » → « Nov. 2026 » */
export function formatMonthShort(yearMonth: string): string {
  const label = new Intl.DateTimeFormat("fr-FR", { month: "short", year: "numeric", timeZone: "UTC" })
    .format(new Date(`${yearMonth}-01T00:00:00Z`));
  return label.charAt(0).toUpperCase() + label.slice(1);
}

/** « 2026-11 » → « novembre 2026 » */
export function formatMonthLong(yearMonth: string): string {
  return new Intl.DateTimeFormat("fr-FR", { month: "long", year: "numeric", timeZone: "UTC" })
    .format(new Date(`${yearMonth}-01T00:00:00Z`));
}

/** Mois courant à Nouméa et les suivants, « AAAA-MM ». */
export function nextMonths(count: number, now: Date = new Date()): string[] {
  const [y, m] = new Intl.DateTimeFormat("en-CA", { timeZone: TIME_ZONE }).format(now).split("-").map(Number);
  return Array.from({ length: count }, (_, i) => {
    const month = m - 1 + i;
    return `${y + Math.floor(month / 12)}-${String((month % 12) + 1).padStart(2, "0")}`;
  });
}

/** « 2026-11-05T09:30:00 » → « mer. 05/11/2026 · 09:30 » */
export function formatVisit(localDateTime: string): string {
  const [date, time] = localDateTime.split("T");
  const [y, m, d] = date.split("-").map(Number);
  const weekday = new Intl.DateTimeFormat("fr-FR", { weekday: "short", timeZone: "UTC" }).format(new Date(Date.UTC(y, m - 1, d)));
  return `${weekday} ${String(d).padStart(2, "0")}/${String(m).padStart(2, "0")}/${y} · ${time.slice(0, 5)}`;
}

/** Nombre de jours entre deux dates ISO (b − a). */
export function daysBetween(a: string, b: string): number {
  return Math.round((Date.parse(`${b}T00:00:00Z`) - Date.parse(`${a}T00:00:00Z`)) / 86_400_000);
}

/** Ajoute des jours à une date ISO. */
export function addDays(iso: string, days: number): string {
  const date = new Date(`${iso}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

/** Jour ISO de la semaine (1 = lundi) d'une date ISO. */
export function isoWeekday(iso: string): number {
  const day = new Date(`${iso}T00:00:00Z`).getUTCDay();
  return day === 0 ? 7 : day;
}
