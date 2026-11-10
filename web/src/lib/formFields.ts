import { z } from "zod";
import { todayIso } from "./age";
import { isoToDateFr, parseDateFr } from "./datetime";

/** Champs de formulaire saisis en texte puis convertis pour l'API. */
export const optionalText = (max: number) => z.string().trim().max(max, `${max} caractères au maximum.`);
export const requiredText = (max: number, message: string) => optionalText(max).min(1, message);

export const optionalDateFr = z
  .string()
  .trim()
  .refine((v) => v === "" || parseDateFr(v) !== null, "Date attendue au format JJ/MM/AAAA.");

export const requiredDateFr = (message: string) =>
  z.string().trim().min(1, message).refine((v) => parseDateFr(v) !== null, "Date attendue au format JJ/MM/AAAA.");

/** Date passée ou du jour (naissance, vaccin, signature) : le serveur refuse une date future. */
const notFuture = (v: string) => {
  const iso = parseDateFr(v);
  return iso === null || iso <= todayIso();
};
export const optionalPastDateFr = optionalDateFr.refine((v) => v === "" || notFuture(v), "La date ne peut pas être dans le futur.");
export const requiredPastDateFr = (message: string) =>
  requiredDateFr(message).refine(notFuture, "La date ne peut pas être dans le futur.");

export const optionalPhone = z
  .string()
  .trim()
  .refine((v) => v === "" || /^\+?[0-9 .-]{6,20}$/.test(v), "Numéro de téléphone invalide.");

export const optionalEmail = z
  .string()
  .trim()
  .refine((v) => v === "" || /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v), "Adresse e-mail invalide.");

/** « 15/01/2024 » → « 2024-01-15 » ; « » → null */
export const dateToIso = (v: string) => (v.trim() === "" ? null : parseDateFr(v));
/** « 2024-01-15 » → « 15/01/2024 » ; null → « » */
export const isoToField = (v: string | null | undefined) => (v ? isoToDateFr(v) : "");
export const textOrNull = (v: string) => (v.trim() === "" ? null : v.trim());
export const nullToEmpty = (v: string | null | undefined) => v ?? "";

/** Liste déroulante native aux couleurs du design system. */
export const selectClass =
  "h-control w-full rounded-md border border-input bg-card px-3 text-body outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 aria-invalid:border-destructive";
