import { z } from "zod";
import { parseDateFr } from "@/lib/datetime";

const digits = (message: string) => z.string().trim().regex(/^\d+$/, message);

export const identitySchema = z.object({
  name: z.string().trim().min(1, "Indiquez le nom de la structure.").max(150, "150 caractères au maximum."),
  city: z.string().trim().max(100, "100 caractères au maximum."),
});

export const agrementSchema = z.object({
  ridet: z
    .string()
    .trim()
    .refine((v) => v === "" || /^\d{7}(\.\d{3})?$/.test(v), "Le RIDET comporte 7 chiffres, puis éventuellement un point et 3 chiffres (ex. 1234567.001)."),
  agrementNumber: z.string().trim().max(50, "50 caractères au maximum."),
  agrementDate: z
    .string()
    .trim()
    .refine((v) => v === "" || parseDateFr(v) !== null, "Date attendue au format JJ/MM/AAAA."),
  capacity: digits("Indiquez le nombre de places de l’agrément.").refine(
    (v) => Number(v) >= 1 && Number(v) <= 500,
    "Entre 1 et 500 places.",
  ),
});

export const sectionSchema = z
  .object({
    name: z.string().trim().min(1, "Nom requis."),
    minAgeMonths: digits("En mois."),
    maxAgeMonths: z.string().trim().refine((v) => v === "" || /^\d+$/.test(v), "En mois."),
    capacity: digits("Nombre de places.").refine((v) => Number(v) >= 1, "Au moins 1 place."),
    color: z.string(),
  })
  .refine((s) => s.maxAgeMonths === "" || Number(s.maxAgeMonths) > Number(s.minAgeMonths), {
    message: "L’âge de sortie doit dépasser l’âge d’entrée.",
    path: ["maxAgeMonths"],
  });

export const sectionsSchema = (capacity: number) =>
  z
    .object({ sections: z.array(sectionSchema).min(1, "Créez au moins une section.") })
    .refine((v) => v.sections.reduce((sum, s) => sum + (Number(s.capacity) || 0), 0) <= capacity, {
      message: `Le total des places ne peut pas dépasser les ${capacity} places de l’agrément.`,
      path: ["sections"],
    });

export const adminSchema = z
  .object({
    firstName: z.string().trim().min(1, "Indiquez votre prénom."),
    lastName: z.string().trim().min(1, "Indiquez votre nom."),
    login: z
      .string()
      .trim()
      .regex(/^[A-Za-z0-9._-]{3,60}$/, "3 à 60 caractères : lettres sans accent, chiffres, point ou tiret."),
    password: z.string().min(10, "Au moins 10 caractères."),
    confirmation: z.string(),
  })
  .refine((v) => v.password === v.confirmation, {
    message: "Les deux mots de passe ne sont pas identiques.",
    path: ["confirmation"],
  });

export type IdentityValues = z.infer<typeof identitySchema>;
export type AgrementValues = z.infer<typeof agrementSchema>;
export type SectionValues = z.infer<typeof sectionSchema>;
export type AdminValues = z.infer<typeof adminSchema>;

export const sectionColors = ["#9e2f66", "#924510", "#3d479b", "#0b6e72", "#3f5a26", "#b5532a"];

/** Sections proposées par défaut (maquette Paramètres › Sections et tranches d'âge). */
export const defaultSections: SectionValues[] = [
  { name: "Bébés", minAgeMonths: "2", maxAgeMonths: "18", capacity: "12", color: "#9e2f66" },
  { name: "Moyens", minAgeMonths: "18", maxAgeMonths: "30", capacity: "16", color: "#924510" },
  { name: "Grands", minAgeMonths: "30", maxAgeMonths: "", capacity: "20", color: "#3d479b" },
];

export type WizardData = {
  identity: IdentityValues;
  agrement: AgrementValues;
  sections: SectionValues[];
  admin: AdminValues;
  /** Dernier groupe de la clé de secours, recopié depuis la fiche. */
};

/** Corps de POST /api/v1/setup. */
export function toSetupRequest(data: WizardData) {
  const optional = (v: string) => (v.trim() === "" ? null : v.trim());
  return {
    structure: {
      name: data.identity.name.trim(),
      city: optional(data.identity.city),
      ridet: optional(data.agrement.ridet),
      agrementNumber: optional(data.agrement.agrementNumber),
      agrementDate: data.agrement.agrementDate ? parseDateFr(data.agrement.agrementDate) : null,
      capacity: Number(data.agrement.capacity),
    },
    sections: data.sections.map((s, index) => ({
      name: s.name.trim(),
      minAgeMonths: Number(s.minAgeMonths),
      maxAgeMonths: s.maxAgeMonths === "" ? null : Number(s.maxAgeMonths),
      capacity: Number(s.capacity),
      color: s.color,
      displayOrder: index,
    })),
    admin: {
      firstName: data.admin.firstName.trim(),
      lastName: data.admin.lastName.trim(),
      login: data.admin.login.trim(),
      password: data.admin.password,
    },
  };
}

/** « 30 » → « 2 ans 6 mois » */
export function describeMonths(value: string): string {
  if (!/^\d+$/.test(value)) return "";
  const months = Number(value);
  const years = Math.floor(months / 12);
  const rest = months % 12;
  if (years === 0) return `${months} mois`;
  const y = years === 1 ? "1 an" : `${years} ans`;
  return rest === 0 ? y : `${y} ${rest} mois`;
}
