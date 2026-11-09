/** Types et appels du kiosque (miroir des DTO serveur, en attendant le client généré). */
import { api, apiUpload } from "@/lib/api";
import type { PinUser, Role, SessionResponse } from "@/features/auth/types";

export type PickupKind = "RESPONSABLE" | "AUTORISEE" | "AUTRE";
export type InstructionKind = "A_MAL_DORMI" | "PEU_MANGE" | "MEDICAMENT" | "NEZ_QUI_COULE" | "SIESTE_AVANCER" | "AUTRE";

export const instructionLabels: Record<InstructionKind, string> = {
  A_MAL_DORMI: "A mal dormi",
  PEU_MANGE: "A peu mangé ce matin",
  MEDICAMENT: "Médicament à donner",
  NEZ_QUI_COULE: "Nez qui coule",
  SIESTE_AVANCER: "Sieste à avancer",
  AUTRE: "Autre consigne",
};

export type Instruction = {
  id: string;
  kind: InstructionKind;
  details: string | null;
  medicationAt: string | null;
  medicationDose: string | null;
  prescriptionGiven: boolean;
  givenAt: string | null;
  givenBy: string | null;
};

export type Candidate = {
  kind: PickupKind;
  id: string;
  name: string;
  relationship: string;
  phone: string | null;
  legalGuardian: boolean;
};

export type KioskAttendance = {
  id: string;
  arrivedAt: string;
  departedAt: string | null;
  droppedOffBy: string | null;
  pickedUpByName: string | null;
  instructions: Instruction[];
  /** Opération pas encore confirmée par le serveur. */
  pending?: boolean;
};

export type KioskHealth = {
  allergies: { allergen: string; severity: "LEGERE" | "MODEREE" | "SEVERE"; reaction: string | null }[];
  pai: boolean;
  paiReason: string | null;
  paiKitLocation: string | null;
  diet: string | null;
  dietDetails: string | null;
  instructions: string | null;
};

export type KioskChild = {
  id: string;
  firstName: string;
  lastName: string;
  birthDate: string;
  sectionId: string | null;
  planned: boolean;
  variablePlanning: boolean;
  expectedAt: string | null;
  expectedLeaveAt: string | null;
  childrenPerAdult: number;
  health: KioskHealth;
  pickup: Candidate[];
  parents: { name: string; relationship: string; phone: string }[];
  attendances: KioskAttendance[];
};

export type KioskStaffAttendance = {
  id: string;
  arrivedAt: string;
  departedAt: string | null;
  sectionId: string | null;
  pending?: boolean;
};

export type KioskStaff = {
  userId: string;
  displayName: string;
  initials: string;
  role: Role;
  jobTitle: string | null;
  homeSectionId: string | null;
  countsInRatio: boolean;
  hasPin: boolean;
  attendances: KioskStaffAttendance[];
};

export type KioskToday = {
  serverTime: string;
  day: string;
  structureName: string;
  sections: { id: string; name: string; color: string }[];
  children: KioskChild[];
  staff: KioskStaff[];
  directors: { userId: string; displayName: string }[];
  fallbackChildrenPerAdult: number;
};

export type OperationType =
  | "ARRIVEE"
  | "ANNULER_ARRIVEE"
  | "CONSIGNES"
  | "MEDICAMENT_DONNE"
  | "DEPART"
  | "POINTAGE_PERSONNEL"
  | "SECTION_PERSONNEL"
  | "CONNEXION_HORS_LIGNE"
  | "TRANSMISSION";

export type InstructionInput = {
  id: string;
  kind: InstructionKind;
  details?: string | null;
  medicationAt?: string | null;
  medicationDose?: string | null;
  prescriptionGiven?: boolean;
};

export type OverrideInput = {
  personName: string;
  relationship: string;
  idChecked: boolean;
  directorId: string;
  directorPin: string;
};

/** Opération de la file d'attente, envoyée telle quelle au serveur. */
export type Operation = {
  clientId: string;
  type: OperationType;
  occurredAt: string;
  offline: boolean;
  userId?: string;
  sessionId?: string;
  offlineSessionId?: string;
  childId?: string;
  attendanceId?: string;
  sectionId?: string | null;
  droppedOffBy?: string | null;
  instructions?: InstructionInput[];
  instructionId?: string;
  pickup?: { kind: PickupKind; personId?: string | null };
  override?: OverrideInput;
  pin?: string;
  staffAction?: "ARRIVEE" | "DEPART";
  staffUserId?: string;
  entry?: EntryInput;
};

export type OperationResult = {
  clientId: string;
  outcome: "APPLIQUEE" | "DEJA_RECUE" | "REFUSEE";
  code: string | null;
  message: string | null;
  needsReview: boolean;
};

export type StaffPunchResult = {
  userId: string;
  displayName: string;
  action: "ARRIVEE" | "DEPART";
  at: string;
  sectionId: string | null;
  attendanceId: string;
};

// --- Transmissions du jour (onglet « Journée ») ---------------------------------------------

export type EntryKind = "REPAS" | "SIESTE" | "CHANGE" | "MEDICAMENT" | "ACTIVITE" | "PHOTO" | "VIDEO" | "MOT";

/** Transmission saisie : détails admis par type (repas, quantité, heures de sieste, change, médicament). */
export type EntryInput = {
  kind: EntryKind;
  childIds: string[];
  text?: string | null;
  details?: Record<string, string>;
  shared: boolean;
  /** Heure réelle (« 10:30 ») si la saisie est faite après coup. */
  time?: string;
};

export type JournalEntry = {
  id: string;
  day: string;
  occurredAt: string;
  kind: EntryKind;
  text: string | null;
  details: Record<string, string>;
  hasPhoto: boolean;
  shared: boolean;
  children: { id: string; firstName: string; lastName: string }[];
  authorName: string;
  updatedAt: string;
};

export type KioskJournal = {
  entries: JournalEntry[];
  /** Enfants dont l'autorisation de photographier est accordée. */
  photoConsents: string[];
  reportedAbsences: { childId: string; reason: string | null; guardianName: string | null }[];
};

export const kioskApi = {
  journal: (signal?: AbortSignal) => api<KioskJournal>("/kiosk/journal", { signal, auth: "device" }),
  photo: (operation: Operation, photo: Blob) => {
    const form = new FormData();
    form.append("operation", new Blob([JSON.stringify(operation)], { type: "application/json" }));
    form.append("photo", photo, "photo.jpg");
    return apiUpload<OperationResult>("/kiosk/journal/photo", form, "POST", "device");
  },
  /** Vidéo de 30 s au plus (ADR 0019), en ligne seulement comme une photo. */
  video: (operation: Operation, video: Blob) => {
    const form = new FormData();
    form.append("operation", new Blob([JSON.stringify(operation)], { type: "application/json" }));
    form.append("video", video, video.type.includes("mp4") ? "video.mp4" : "video.webm");
    return apiUpload<OperationResult>("/kiosk/journal/video", form, "POST", "device");
  },
  today: (signal?: AbortSignal) => api<KioskToday>("/kiosk/today", { signal, auth: "device" }),
  operations: (operations: Operation[]) =>
    api<{ serverTime: string; results: OperationResult[] }>("/kiosk/operations", {
      method: "POST",
      body: { operations },
      auth: "device",
    }),
  staffPunch: (body: { clientId: string; pin: string; sectionId?: string | null }) =>
    api<StaffPunchResult>("/kiosk/staff-punch", { method: "POST", body, auth: "device" }),
  pinUsers: (signal?: AbortSignal) => api<PinUser[]>("/auth/pin-users", { signal, auth: "device" }),
  pinLogin: (userId: string, pin: string) =>
    api<SessionResponse>("/auth/pin", { method: "POST", body: { userId, pin }, auth: "device" }),
  logout: (refreshToken: string) =>
    api<void>("/auth/logout", { method: "POST", body: { refreshToken }, auth: false }),
};

export function newId(): string {
  return globalThis.crypto?.randomUUID?.() ?? fallbackUuid();
}

/** crypto.randomUUID n'existe qu'en contexte sécurisé (HTTPS) : repli sur crypto.getRandomValues. */
function fallbackUuid(): string {
  const bytes = new Uint8Array(16);
  globalThis.crypto.getRandomValues(bytes);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
