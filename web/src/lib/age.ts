import { TIME_ZONE } from "./datetime";

/** Date du jour à Nouméa, « AAAA-MM-JJ ». */
export function todayIso(now: Date = new Date()): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: TIME_ZONE }).format(now);
}

/** Âge en mois révolus entre une date de naissance et une date (ISO « AAAA-MM-JJ »). */
export function ageInMonths(birthIso: string, onIso: string): number {
  const [by, bm, bd] = birthIso.split("-").map(Number);
  const [y, m, d] = onIso.split("-").map(Number);
  let months = (y - by) * 12 + (m - bm);
  if (d < bd) months -= 1;
  return Math.max(0, months);
}

/** 27 → « 2 ans 3 mois » ; 8 → « 8 mois » ; 12 → « 1 an » */
export function formatAge(months: number): string {
  const years = Math.floor(months / 12);
  const rest = months % 12;
  if (years === 0) return `${rest} mois`;
  const y = years === 1 ? "1 an" : `${years} ans`;
  return rest === 0 ? y : `${y} ${rest} mois`;
}
