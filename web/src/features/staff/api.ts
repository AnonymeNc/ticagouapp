/** Personnel : types et appels (miroir des DTO serveur, en attendant le client généré). */
import type { Role } from "@/features/auth/types";
import { api, apiBlob, apiUpload, saveBlob } from "@/lib/api";

export type ContractType = "CDI" | "CDD" | "REMPLACEMENT" | "APPRENTISSAGE" | "VACATAIRE";
export type QualificationKind = "DIPLOME" | "SST" | "HACCP" | "APTITUDE" | "AUTRE";
export type QualificationState = "SANS_LIMITE" | "VALIDE" | "A_RENOUVELER" | "EXPIREE";
export type AbsenceKind = "CONGES" | "MALADIE" | "FORMATION" | "RECUPERATION" | "AUTRE";
export type RatioStatus = "CONFORME" | "LIMITE" | "INSUFFISANT";
export type TimesheetAnomaly = "DEPART_MANQUANT" | "SANS_PLANNING" | "ABSENCE_NON_JUSTIFIEE";

export type Alert = { userId: string; name: string; kind: "QUALIFICATION" | "CONTRAT"; state: QualificationState; label: string; date: string };

export type Member = {
  userId: string;
  firstName: string;
  lastName: string;
  role: Role;
  active: boolean;
  jobTitle: string | null;
  diploma: string | null;
  homeSectionId: string | null;
  countsInRatio: boolean;
  contractType: ContractType;
  /** Nul : rémunération à l'heure. */
  weeklyMinutes: number | null;
  hiredOn: string | null;
  endsOn: string | null;
  alerts: Alert[];
};

export type Team = {
  members: Member[];
  people: number;
  inRatio: number;
  expired: number;
  toRenew: number;
  nextDue: string | null;
  plannedTrainings: number;
  nextTraining: string | null;
  nextTrainingOn: string | null;
  alertDays: number;
};

export type Qualification = {
  id: string;
  kind: QualificationKind;
  label: string;
  obtainedOn: string | null;
  validUntil: string | null;
  fileName: string | null;
  verified: boolean;
  state: QualificationState;
  version: number;
};
export type QualificationInput = Pick<Qualification, "kind" | "label" | "obtainedOn" | "validUntil" | "verified">;

export type Training = {
  id: string;
  label: string;
  minutes: number;
  startsOn: string;
  endsOn: string;
  organization: string | null;
  done: boolean;
  fileName: string | null;
  version: number;
};
export type TrainingInput = Pick<Training, "label" | "minutes" | "startsOn" | "endsOn" | "organization" | "done">;

export type ScheduleDay = { dayOfWeek: number; startsAt: string; endsAt: string; breakMinutes: number; sectionId: string | null };
export type Schedule = { effectiveFrom: string; days: ScheduleDay[]; weekMinutes: number };

export type Profile = {
  member: Member;
  birthDate: string | null;
  phone: string | null;
  email: string | null;
  address: string | null;
  cafatNumber: string | null;
  emergencyContact: string | null;
  qualifications: Qualification[];
  trainings: Training[];
  trainingMinutesSinceJanuary: number;
  schedule: Schedule | null;
  upcomingSchedules: Schedule[];
  version: number;
};

export type ProfileInput = {
  birthDate: string | null;
  phone: string | null;
  email: string | null;
  address: string | null;
  cafatNumber: string | null;
  emergencyContact: string | null;
  diploma: string | null;
  contractType: ContractType;
  weeklyMinutes: number | null;
  hiredOn: string | null;
  endsOn: string | null;
};

export type StaffSettings = { validFrom: string; alertDays: number; defaultBreakMinutes: number; referenceWeeklyMinutes: number };

export type Shift = {
  day: string;
  startsAt: string | null;
  endsAt: string | null;
  breakMinutes: number;
  sectionId: string | null;
  overridden: boolean;
  note: string | null;
  absence: AbsenceKind | null;
  absenceId: string | null;
  halfDay: boolean;
  plannedMinutes: number;
};

export type PlanningRow = {
  userId: string;
  name: string;
  jobTitle: string | null;
  homeSectionId: string | null;
  countsInRatio: boolean;
  weeklyMinutes: number | null;
  plannedMinutes: number;
  shifts: Shift[];
};

export type WeekPlanning = { monday: string; openDays: number[]; rows: PlanningRow[]; shortages: Record<string, number>; validatedMonths: string[] };

export type OverrideInput = { startsAt: string | null; endsAt: string | null; breakMinutes: number; sectionId: string | null; note: string | null };

export type StaffAbsence = {
  id: string;
  userId: string;
  name: string;
  kind: AbsenceKind;
  fromDay: string;
  toDay: string;
  halfDay: boolean;
  note: string | null;
  author: string;
  version: number;
};
export type StaffAbsenceInput = Pick<StaffAbsence, "userId" | "kind" | "fromDay" | "toDay" | "halfDay" | "note">;

export type Load = { children: number; adults: number; required: number; status: RatioStatus };
export type Slot = { startsAt: string; endsAt: string; sections: Record<string, Load>; global: Load };
export type Forecast = {
  day: string;
  open: boolean;
  sections: { id: string; name: string; color: string | null }[];
  slots: Slot[];
  shortages: number;
  variableChildren: number;
};

export type DaySheet = {
  day: string;
  plannedMinutes: number;
  workedMinutes: number;
  absence: AbsenceKind | null;
  absenceMinutes: number;
  punches: { arrivedAt: string; departedAt: string | null }[];
  anomalies: TimesheetAnomaly[];
};
export type WeekSheet = { monday: string; workedMinutes: number; trainingMinutes: number; contractMinutes: number; overtimeMinutes: number };
export type PersonSheet = {
  userId: string;
  name: string;
  jobTitle: string | null;
  contractType: ContractType;
  weeklyMinutes: number | null;
  plannedMinutes: number;
  workedMinutes: number;
  overtimeMinutes: number;
  absenceMinutes: Partial<Record<AbsenceKind, number>>;
  anomalies: number;
  days: DaySheet[];
  weeks: WeekSheet[];
};
export type MonthSheet = { month: string; validated: boolean; validatedAt: string | null; validatedBy: string | null; people: PersonSheet[] };

// --- Libellés --------------------------------------------------------------------------------

export const contractLabels: Record<ContractType, string> = {
  CDI: "CDI",
  CDD: "CDD",
  REMPLACEMENT: "CDD de remplacement",
  APPRENTISSAGE: "Apprentissage",
  VACATAIRE: "Vacataire",
};

export const qualificationLabels: Record<QualificationKind, string> = {
  DIPLOME: "Diplôme",
  SST: "Sauveteur secouriste du travail",
  HACCP: "Hygiène alimentaire (HACCP)",
  APTITUDE: "Aptitude, médecine du travail",
  AUTRE: "Autre",
};

export const absenceLabels: Record<AbsenceKind, string> = {
  CONGES: "Congés",
  MALADIE: "Maladie",
  FORMATION: "Formation",
  RECUPERATION: "Récupération",
  AUTRE: "Autre absence",
};

export const anomalyLabels: Record<TimesheetAnomaly, string> = {
  DEPART_MANQUANT: "départ non pointé",
  SANS_PLANNING: "pointé sans planning",
  ABSENCE_NON_JUSTIFIEE: "prévu, ni pointé ni absent",
};

// --- Appels --------------------------------------------------------------------------------

export const staffKeys = {
  team: ["staff", "team"] as const,
  profile: (id: string) => ["staff", "profile", id] as const,
  alerts: ["staff", "alerts"] as const,
  settings: ["staff", "settings"] as const,
  planning: (monday: string, mine: boolean) => ["staff", "planning", monday, mine] as const,
  forecast: (day: string) => ["staff", "forecast", day] as const,
  absences: (from: string, to: string) => ["staff", "absences", from, to] as const,
  timesheet: (month: string, mine: boolean) => ["staff", "timesheet", month, mine] as const,
};

async function download(path: string, fallback: string) {
  const { blob, fileName } = await apiBlob(path);
  saveBlob(blob, fileName ?? fallback);
}

export const staffApi = {
  team: (signal?: AbortSignal) => api<Team>("/staff", { signal }),
  alerts: (signal?: AbortSignal) => api<Alert[]>("/staff/alerts", { signal }),
  profile: (id: string, signal?: AbortSignal) => api<Profile>(`/staff/${id}`, { signal }),
  updateProfile: (id: string, body: ProfileInput) => api<Profile>(`/staff/${id}/profile`, { method: "PUT", body }),
  setSchedule: (id: string, body: { effectiveFrom: string; days: ScheduleDay[] }) =>
    api<Schedule>(`/staff/${id}/schedule`, { method: "PUT", body }),

  addQualification: (id: string, body: QualificationInput) => api<Qualification>(`/staff/${id}/qualifications`, { method: "POST", body }),
  updateQualification: (id: string, body: QualificationInput) => api<Qualification>(`/staff/qualifications/${id}`, { method: "PUT", body }),
  attachQualification: (id: string, file: File) => {
    const form = new FormData();
    form.append("file", file);
    return apiUpload<Qualification>(`/staff/qualifications/${id}/file`, form, "PUT");
  },
  qualificationFile: (q: Qualification) => download(`/staff/qualifications/${q.id}/file`, q.fileName ?? "justificatif.pdf"),
  deleteQualification: (id: string) => api<void>(`/staff/qualifications/${id}`, { method: "DELETE" }),

  addTraining: (id: string, body: TrainingInput) => api<Training>(`/staff/${id}/trainings`, { method: "POST", body }),
  updateTraining: (id: string, body: TrainingInput) => api<Training>(`/staff/trainings/${id}`, { method: "PUT", body }),
  attachTraining: (id: string, file: File) => {
    const form = new FormData();
    form.append("file", file);
    return apiUpload<Training>(`/staff/trainings/${id}/file`, form, "PUT");
  },
  trainingFile: (t: Training) => download(`/staff/trainings/${t.id}/file`, t.fileName ?? "attestation.pdf"),
  deleteTraining: (id: string) => api<void>(`/staff/trainings/${id}`, { method: "DELETE" }),

  settings: (signal?: AbortSignal) => api<StaffSettings>("/staff/settings", { signal }),
  updateSettings: (body: StaffSettings) => api<StaffSettings>("/staff/settings", { method: "PUT", body }),

  planning: (week: string, mine: boolean, signal?: AbortSignal) =>
    api<WeekPlanning>(`/staff/planning${mine ? "/me" : ""}?week=${week}`, { signal }),
  setShift: (userId: string, day: string, body: OverrideInput) => api<Shift>(`/staff/${userId}/shifts/${day}`, { method: "PUT", body }),
  resetShift: (userId: string, day: string) => api<Shift>(`/staff/${userId}/shifts/${day}`, { method: "DELETE" }),
  forecast: (day: string, signal?: AbortSignal) => api<Forecast>(`/staff/forecast?day=${day}`, { signal }),
  absences: (from: string, to: string, signal?: AbortSignal) => api<StaffAbsence[]>(`/staff/absences?from=${from}&to=${to}`, { signal }),
  createAbsence: (body: StaffAbsenceInput) => api<StaffAbsence>("/staff/absences", { method: "POST", body }),
  updateAbsence: (id: string, body: StaffAbsenceInput) => api<StaffAbsence>(`/staff/absences/${id}`, { method: "PUT", body }),
  deleteAbsence: (id: string) => api<void>(`/staff/absences/${id}`, { method: "DELETE" }),

  timesheet: (month: string, mine: boolean, signal?: AbortSignal) =>
    api<MonthSheet>(`/staff/timesheets/${mine ? "me/" : ""}${month}`, { signal }),
  validate: (month: string) => api<MonthSheet>(`/staff/timesheets/${month}/validate`, { method: "POST" }),
  reopen: (month: string, reason: string) => api<MonthSheet>(`/staff/timesheets/${month}/reopen`, { method: "POST", body: { reason } }),
  exportCsv: (month: string) => download(`/staff/timesheets/${month}/export.csv`, `heures-${month}.csv`),
  exportXlsx: (month: string) => download(`/staff/timesheets/${month}/export.xlsx`, `heures-${month}.xlsx`),
};
