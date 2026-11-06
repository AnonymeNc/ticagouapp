/** Formats d'affichage : fuseau de Nouméa, dates JJ/MM/AAAA, montants XPF entiers. */
const ZONE = "Pacific/Noumea";

export const todayIso = (date = new Date()) => date.toLocaleDateString("sv-SE", { timeZone: ZONE });

export function addDays(iso: string, days: number) {
  const d = new Date(`${iso}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

export const dateFr = (iso: string) => {
  const [y, m, d] = iso.slice(0, 10).split("-");
  return `${d}/${m}/${y}`;
};

export const time = (iso: string) => new Date(iso).toLocaleTimeString("fr-FR", { timeZone: ZONE, hour: "2-digit", minute: "2-digit" });

export const longDay = (iso: string) => {
  const text = new Date(`${iso}T12:00:00Z`).toLocaleDateString("fr-FR", { timeZone: "UTC", weekday: "long", day: "numeric", month: "long", year: "numeric" });
  return text.charAt(0).toUpperCase() + text.slice(1);
};

export const weekdayDate = (iso: string) => {
  const day = new Date(`${iso}T12:00:00Z`).toLocaleDateString("fr-FR", { timeZone: "UTC", weekday: "long" });
  return `${day.charAt(0).toUpperCase()}${day.slice(1)} ${dateFr(iso)}`;
};

export const monthYear = (yearMonth: string) => {
  const text = new Date(`${yearMonth}-15T12:00:00Z`).toLocaleDateString("fr-FR", { timeZone: "UTC", month: "long", year: "numeric" });
  return text.charAt(0).toUpperCase() + text.slice(1);
};

/** 58 500 F (espace fine insécable entre milliers, montants entiers). */
export const xpf = (amount: number) => `${Math.round(amount).toLocaleString("fr-FR").replace(/\s/g, " ")} F`;

/** « 1 h 45 » entre deux heures HH:MM. */
export function duration(from: string, to: string) {
  const [fh, fm] = from.split(":").map(Number);
  const [th, tm] = to.split(":").map(Number);
  const minutes = th * 60 + tm - (fh * 60 + fm);
  if (minutes <= 0) return null;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return h === 0 ? `${m} min` : m === 0 ? `${h} h` : `${h} h ${String(m).padStart(2, "0")}`;
}
