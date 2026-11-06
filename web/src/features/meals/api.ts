/** Repas : types et appels (miroir des DTO serveur, en attendant le client généré). */
import { api, apiBlob, saveBlob } from "@/lib/api";

export type Course = "ENTREE" | "PLAT" | "VARIANTE" | "LAITAGE" | "DESSERT" | "GOUTER";
export type Meal = "REPAS" | "GOUTER";
export type Texture = "NORMALE" | "HACHEE" | "MIXEE";
export type Basis = "POINTAGE" | "PREVISION" | "CORRECTION";

export const courseLabels: Record<Course, string> = {
  ENTREE: "Entrée",
  PLAT: "Plat",
  VARIANTE: "Variante",
  LAITAGE: "Laitage",
  DESSERT: "Dessert",
  GOUTER: "Goûter",
};

/** Lignes du menu, dans l'ordre de la maquette ; les variantes s'insèrent après le plat. */
export const COURSES: Exclude<Course, "VARIANTE">[] = ["ENTREE", "PLAT", "LAITAGE", "DESSERT", "GOUTER"];

export const textureLabels: Record<Texture, string> = { NORMALE: "Normale", HACHEE: "Hachée", MIXEE: "Mixée" };

/** Étiquettes des plats : les 14 allergènes réglementaires, puis porc et viande (régimes). */
export const TAGS = [
  "gluten", "crustacés", "œuf", "poisson", "arachide", "soja", "lait", "fruits à coque", "céleri", "moutarde",
  "sésame", "sulfites", "lupin", "mollusques", "porc", "viande",
];

export type Diet = { id: string; label: string; excludes: string[]; displayOrder: number; children: number; version: number };
export type MealSettings = { lunchTime: string; snackTime: string; tags?: string[] };

export type MenuItem = { dayOfWeek: number; course: Course; dietId: string | null; label: string; tags: string[] };
export type VariantStatus = "CONVIENT" | "VARIANTE" | "MANQUANTE" | "SANS_MENU";
export type DietRow = {
  dietId: string;
  label: string;
  excludes: string[];
  children: string[];
  days: { dayOfWeek: number; status: VariantStatus; conflicts: string[] }[];
};
export type MenuWeek = {
  weekStart: string;
  weekEnd: string;
  isoWeek: number;
  status: "BROUILLON" | "PUBLIE";
  saved: boolean;
  updatedAt: string | null;
  updatedBy: string | null;
  openDays: number[];
  items: MenuItem[];
  diets: DietRow[];
  warnings: string[];
  version: number;
};

export type SectionCount = {
  sectionId: string | null;
  name: string;
  color: string | null;
  lunches: number;
  snacks: number;
  packedLunches: number;
  children: number;
  minced: number;
  mashed: number;
  leavingBeforeSnack: number;
};

export type Adjustment = { served: boolean; reason: string; author: string; at: string };

export type KitchenChild = {
  childId: string;
  firstName: string;
  lastName: string;
  sectionId: string | null;
  sectionName: string | null;
  allergies: string[];
  pai: boolean;
  diet: string | null;
  texture: Texture;
  packedLunch: boolean;
  lunch: boolean;
  snack: boolean;
  present: boolean;
  planned: boolean;
  lunchBasis: Basis;
  snackBasis: Basis;
  advice: string[];
  note: string | null;
  lunchAdjustment: Adjustment | null;
  snackAdjustment: Adjustment | null;
};

export type KitchenDay = {
  day: string;
  countedAt: string;
  structureName: string;
  lunchTime: string;
  snackTime: string;
  openDay: boolean;
  closedQuarter: boolean;
  sections: SectionCount[];
  total: SectionCount;
  toAdapt: KitchenChild[];
  absentWithNeeds: KitchenChild[];
  children: KitchenChild[];
  menu: MenuItem[];
  /** Libellé de chaque régime, pour les variantes du menu. */
  diets: Record<string, string>;
};

export type QuarterState = "A_VENIR" | "EN_COURS" | "A_CLORE" | "CLOS";
export type MonthRow = {
  month: string | null;
  label: string;
  openDays: number;
  lunchesBySection: Record<string, number>;
  lunches: number;
  snacks: number;
  packedLunches: number;
};
export type Quarter = {
  year: number;
  quarter: number;
  label: string;
  from: string;
  to: string;
  state: QuarterState;
  closedAt: string | null;
  closedBy: string | null;
  sections: { id: string; name: string; color: string }[];
  months: MonthRow[];
  total: MonthRow;
  averagePerDay: string;
  checks: { monthsOver: boolean; toReview: number; closed: boolean };
};
export type QuarterSummary = { year: number; quarter: number; label: string; state: QuarterState };

export const mealKeys = {
  all: ["meals"] as const,
  kitchen: (day: string) => ["meals", "kitchen", day] as const,
  menus: (weekStart: string) => ["meals", "menus", weekStart] as const,
  quarters: ["meals", "quarters"] as const,
  quarter: (year: number, quarter: number) => ["meals", "quarter", year, quarter] as const,
  diets: ["diets"] as const,
  settings: ["meals", "settings"] as const,
};

export const mealsApi = {
  kitchen: (day: string, signal?: AbortSignal) => api<KitchenDay>(`/meals/kitchen?date=${day}`, { signal }),
  adjust: (body: { childId: string; day: string; meal: Meal; served: boolean | null; reason?: string }) =>
    api<KitchenDay>("/meals/adjustments", { method: "PUT", body }),
  note: (body: { childId: string; day: string; note: string }) => api<void>("/meals/notes", { method: "PUT", body }),
  settings: (signal?: AbortSignal) => api<MealSettings>("/meals/settings", { signal }),
  updateSettings: (body: MealSettings) => api<MealSettings>("/meals/settings", { method: "PUT", body }),
  diets: (signal?: AbortSignal) => api<Diet[]>("/diets", { signal }),
  createDiet: (body: { label: string; excludes: string[]; displayOrder: number }) => api<Diet>("/diets", { method: "POST", body }),
  updateDiet: (id: string, body: { label: string; excludes: string[]; displayOrder: number }) =>
    api<Diet>(`/diets/${id}`, { method: "PUT", body }),
  deleteDiet: (id: string) => api<void>(`/diets/${id}`, { method: "DELETE" }),
  week: (day: string, signal?: AbortSignal) => api<MenuWeek>(`/menus?date=${day}`, { signal }),
  saveWeek: (weekStart: string, body: { status: MenuWeek["status"]; items: MenuItem[] }) =>
    api<MenuWeek>(`/menus/${weekStart}`, { method: "PUT", body }),
  copyPrevious: (weekStart: string) => api<MenuWeek>(`/menus/${weekStart}/copy-previous`, { method: "POST" }),
  quarters: (signal?: AbortSignal) => api<QuarterSummary[]>("/meals/quarters", { signal }),
  quarter: (year: number, quarter: number, signal?: AbortSignal) =>
    api<Quarter>(`/meals/quarters/${year}/${quarter}`, { signal }),
  close: (year: number, quarter: number) => api<Quarter>(`/meals/quarters/${year}/${quarter}/close`, { method: "POST" }),
  reopen: (year: number, quarter: number, reason: string) =>
    api<Quarter>(`/meals/quarters/${year}/${quarter}/reopen`, { method: "POST", body: { reason } }),
  download: async (year: number, quarter: number, format: "pdf" | "xlsx") => {
    const { blob, fileName } = await apiBlob(`/meals/quarters/${year}/${quarter}/${format}`);
    saveBlob(blob, fileName ?? `repas-${year}-T${quarter}.${format}`);
  },
};

/** Lundi de la semaine d'une date ISO. */
export function mondayOf(iso: string): string {
  const date = new Date(`${iso}T12:00:00Z`);
  const shift = (date.getUTCDay() + 6) % 7;
  date.setUTCDate(date.getUTCDate() - shift);
  return date.toISOString().slice(0, 10);
}

/** « 10 repas », « 1 goûter », « 2 paniers repas » */
export function plural(n: number, word: string) {
  if (n <= 1) return `${n} ${word}`;
  const [first, ...rest] = word.split(" ");
  const many = /[sx]$/.test(first) ? first : `${first}s`;
  return `${n} ${[many, ...rest].join(" ")}`;
}
