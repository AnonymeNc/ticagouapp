/** Paramètres de la phase 8 : structure, sections et passages, sauvegardes, licence (miroir des DTO serveur). */
import type { OpeningHours, Structure } from "@/features/structure/useStructure";
import type { LicenseModule } from "@/features/license/useModules";
import type { Section } from "@/features/structure/useSections";
import { api } from "@/lib/api";

export type StructureUpdate = {
  identity: { name: string; city: string | null; ridet: string | null; agrementNumber: string | null; agrementDate: string | null; capacity: number };
  invoiceFooter: string | null;
  openingHours: OpeningHours[];
  contact: Structure["contact"];
  bank: Structure["bank"];
};

export type Passage = { childId: string; name: string; fromSectionId: string; toSectionId: string | null; on: string; ageMonths: number; blocked: boolean };
export type SectionsOverview = {
  sections: { section: Section; enrolled: number }[];
  totalPlaces: number;
  capacity: number;
  gaps: string[];
  rules: Structure["passageRules"];
  passages: Passage[];
};
export type SectionInput = Omit<Section, "id">;

export type BackupRun = {
  id: string;
  kind: "AUTOMATIQUE" | "MANUELLE" | "TEST" | "AVANT_MIGRATION";
  author: string | null;
  startedAt: string;
  finishedAt: string | null;
  status: "EN_COURS" | "REUSSIE" | "ECHEC";
  sizeBytes: number | null;
  fileName: string | null;
  copies: number;
  detail: string | null;
};
export type BackupOverview = {
  enabled: boolean;
  dailyTime: string;
  keepDays: number;
  keepWeeks: number;
  keepMonths: number;
  lastTestOn: string | null;
  lastTestOk: boolean | null;
  toolsAvailable: boolean;
  running: boolean;
  lastSuccess: BackupRun | null;
  nextRun: string | null;
  /** Aucune sauvegarde réussie depuis 48 h. */
  stale: boolean;
  history: BackupRun[];
};

export type LicenseState = "EVALUATION" | "EVALUATION_TERMINEE" | "ACTIVE" | "A_RENOUVELER" | "EXPIREE_GRACE" | "LECTURE_SEULE";
/** État du jour (ADR 0011), lisible par tous les postes : bandeau d'échéance et lecture seule. */
export type LicenseStatus = {
  state: LicenseState;
  /** Jour où la saisie sera bloquée (fin de l'évaluation ou de la grâce). */
  deadline: string | null;
  daysLeft: number;
  message: string | null;
};
export type License = {
  state: LicenseState;
  readOnly: boolean;
  deadline: string | null;
  daysLeft: number;
  /** PC du bureau qui occupent une place, et nombre permis. */
  desktops: number;
  desktopLimit: number;
  /** Le serveur vérifie les clés avec la clé publique de développement. */
  license: {
    number: string; holder: string; plan: string; places: number;
    /** Nul : clé antérieure aux modules, tout est compris. */
    modules: LicenseModule[] | null;
    desktops: number; tablets: number; validUntil: string;
  } | null;
  renewalFrom: string | null;
  pairedTablets: number;
  sections: number;
  capacity: number;
  version: string;
  message: string | null;
};

/** PC du bureau enregistré ; « active » faux s'il n'a pas été vu depuis 90 jours (il ne compte plus). */
export type DesktopClient = { id: string; label: string; firstSeenAt: string; lastSeenAt: string; lastUserName: string | null; active: boolean };

/** Autorité locale du HTTPS (ADR 0009) ; « enabled » faux en développement. */

/** 1 288 490 189 → « 1,2 Go » */
export function formatBytes(bytes: number): string {
  const units = ["octets", "Ko", "Mo", "Go", "To"];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toLocaleString("fr-FR", { maximumFractionDigits: unit === 0 ? 0 : 1 })} ${units[unit]}`;
}

export const systemKeys = {
  sections: ["sections", "overview"] as const,
  backups: ["backups"] as const,
  license: ["license"] as const,
  licenseStatus: ["license", "status"] as const,
  desktops: ["license", "desktops"] as const,
};

export const systemApi = {
  updateStructure: (body: StructureUpdate) => api<Structure>("/structure", { method: "PUT", body }),
  sections: (signal?: AbortSignal) => api<SectionsOverview>("/sections/overview", { signal }),
  createSection: (body: SectionInput) => api<Section>("/sections", { method: "POST", body }),
  updateSection: (id: string, body: SectionInput) => api<Section>(`/sections/${id}`, { method: "PUT", body }),
  deleteSection: (id: string) => api<void>(`/sections/${id}`, { method: "DELETE" }),
  passageRules: (body: Structure["passageRules"]) => api<SectionsOverview>("/sections/passage-rules", { method: "PUT", body }),
  applyPassage: (childId: string, toSectionId: string) =>
    api<SectionsOverview>("/sections/passages", { method: "POST", body: { childId, toSectionId } }),

  backups: (signal?: AbortSignal) => api<BackupOverview>("/backups", { signal }),
  runBackup: () => api<BackupRun>("/backups/run", { method: "POST" }),
  restoreTest: () => api<BackupRun>("/backups/restore-test", { method: "POST" }),

  license: (signal?: AbortSignal) => api<License>("/system/license", { signal }),
  licenseStatus: (signal?: AbortSignal, auth: true | "device" = true) => api<LicenseStatus>("/system/license/status", { signal, auth }),
  desktops: (signal?: AbortSignal) => api<DesktopClient[]>("/system/desktops", { signal }),
  revokeDesktop: (id: string) => api<void>(`/system/desktops/${id}/revoke`, { method: "POST" }),
};
