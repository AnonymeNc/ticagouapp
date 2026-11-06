/** Facturation : types et appels (miroir des DTO serveur, en attendant le client généré). */
import { api, apiBlob, saveBlob } from "@/lib/api";

// --- Facturer le mois -----------------------------------------------------------------------

export type FamilyStatus = "PRETE" | "ANOMALIE" | "FACTUREE" | "RIEN";
export type LineKind = "FORFAIT" | "ADAPTATION" | "FRAIS_INSCRIPTION" | "HEURES_COMPLEMENTAIRES" | "DEPASSEMENT" | "ABSENCE" | "AIDE";
export type AnomalyType = "DEPART_MANQUANT" | "SANS_CONTRAT" | "JUSTIFICATIF_MANQUANT" | "SANS_PAYEUR";

export type Anomaly = {
  familyId: string;
  familyLabel: string;
  childId: string | null;
  childName: string | null;
  type: AnomalyType;
  message: string;
  days: string[];
};

export type FamilyRow = {
  familyId: string;
  familyLabel: string;
  payerName: string | null;
  children: string[];
  forfait: number;
  extraHours: number;
  overtime: number;
  absences: number;
  other: number;
  tgc: number;
  careTotal: number;
  aids: Record<string, number>;
  aidTotal: number;
  familyTotal: number;
  status: FamilyStatus;
  anomalyCount: number;
  invoiceId: string | null;
  invoiceNumber: string | null;
};

export type MonthPreview = {
  month: string;
  generationAllowed: boolean;
  funders: { id: string; name: string }[];
  families: FamilyRow[];
  anomalies: Anomaly[];
  totals: {
    careTotal: number;
    familyTotal: number;
    aidTotal: number;
    aidByFunder: Record<string, number>;
    families: number;
    ready: number;
    withAnomalies: number;
    invoiced: number;
  };
};

export type Line = {
  childId: string | null;
  childName: string | null;
  kind: LineKind;
  label: string;
  detail: string | null;
  quantity: string | null;
  unitPrice: number | null;
  amount: number;
  tgcRate: number;
  tgcAmount: number;
  total: number;
  funderId: string | null;
};

export type FamilyPreview = {
  row: FamilyRow;
  payerAddress: string | null;
  contractText: string | null;
  dueOn: string;
  lines: Line[];
  careTotal: number;
  tgcTotal: number;
  aidTotal: number;
  rounding: number;
  familyTotal: number;
  notes: string[];
  anomalies: Anomaly[];
};

export type GenerationResult = {
  month: string;
  invoices: number;
  firstNumber: string;
  lastNumber: string;
  familyTotal: number;
  aidTotal: number;
  skipped: string[];
  funderRequests: { id: string; number: string; funderName: string; total: number }[];
};

// --- Factures --------------------------------------------------------------------------------

export type InvoiceStatus = "EMISE" | "PARTIELLE" | "PAYEE" | "EN_RETARD" | "ANNULEE";

export type InvoiceSummary = {
  id: string;
  number: string;
  familyId: string;
  familyLabel: string;
  payerName: string;
  month: string;
  issuedOn: string;
  dueOn: string;
  familyTotal: number;
  aidTotal: number;
  credited: number;
  paid: number;
  remaining: number;
  status: InvoiceStatus;
  sentOn: string | null;
  reminderLevel: number;
  daysLate: number;
};

export type CreditNote = {
  id: string;
  number: string;
  invoiceId: string;
  invoiceNumber: string;
  issuedOn: string;
  reason: string;
  fullCancel: boolean;
  familyTotal: number;
  aidTotal: number;
  lines: { label: string; amount: number }[];
  issuedBy: string;
};

export type Invoice = {
  summary: InvoiceSummary;
  payerAddress: string | null;
  careTotal: number;
  tgcTotal: number;
  rounding: number;
  contractText: string | null;
  issuedBy: string;
  lines: Line[];
  creditNotes: CreditNote[];
  payments: { paymentId: string; paidOn: string; method: PaymentMethod; reference: string | null; amount: number; cancelled: boolean }[];
  events: { kind: string; label: string; author: string; at: string }[];
};

// --- Financeurs --------------------------------------------------------------------------------

export type FunderKind = "AIDE_FAMILLE" | "SUBVENTION_STRUCTURE";
export type FunderCategory = "PROVINCE" | "ORGANISME" | "EMPLOYEUR" | "COMMUNE" | "AUTRE";
export type Calculation = "MONTANT_FIXE" | "POURCENTAGE";
export type RateBase = "FRAIS_GARDE" | "FORFAIT";

export type Funder = {
  id: string;
  name: string;
  kind: FunderKind;
  category: FunderCategory;
  calculation: Calculation;
  amount: number;
  /** Dixièmes de pour cent : 200 = 20 %. */
  rate: number;
  rateBase: RateBase;
  monthlyCap: number | null;
  priority: number;
  validFrom: string;
  validTo: string | null;
  conventionRef: string | null;
  contact: string | null;
  beneficiaries: number;
  version: number;
};

export type FunderInput = Omit<Funder, "id" | "beneficiaries" | "version">;

export type ChildFunding = {
  id: string;
  childId: string;
  funderId: string;
  funderName: string;
  calculation: Calculation;
  fromDay: string;
  toDay: string | null;
  beneficiary: string | null;
  fileNumber: string | null;
  amountOverride: number | null;
  rateOverride: number | null;
  version: number;
};

export type ChildFundingInput = Pick<ChildFunding, "funderId" | "fromDay" | "toDay" | "beneficiary" | "fileNumber" | "amountOverride" | "rateOverride">;

export type RequestStatus = "A_ENVOYER" | "ENVOYEE" | "PARTIELLE" | "REGLEE";

export type FunderRequest = {
  id: string;
  number: string;
  funderId: string;
  funderName: string;
  conventionRef: string | null;
  month: string;
  total: number;
  received: number;
  remaining: number;
  sentOn: string | null;
  sentBy: string | null;
  status: RequestStatus;
  lines: { childId: string; childName: string; beneficiary: string | null; invoiceId: string; invoiceNumber: string; attendanceDays: number; amount: number }[];
  version: number;
};

// --- Encaissements --------------------------------------------------------------------------------

export type PaymentMethod = "VIREMENT" | "CHEQUE" | "ESPECES" | "CARTE";

export type Payment = {
  id: string;
  payerName: string;
  familyId: string | null;
  funderId: string | null;
  paidOn: string;
  amount: number;
  method: PaymentMethod;
  reference: string | null;
  bank: string | null;
  receiptNumber: string | null;
  recordedBy: string;
  cancelled: boolean;
  cancelReason: string | null;
  allocations: { invoiceId: string | null; funderRequestId: string | null; number: string; amount: number }[];
};

export type PaymentInput = {
  invoiceId?: string;
  familyId?: string;
  funderRequestId?: string;
  paidOn: string;
  amount: number;
  method: PaymentMethod;
  reference: string | null;
  bank: string | null;
};

export type Collections = {
  collectedThisMonth: number;
  collectedCount: number;
  upcoming: number;
  upcomingCount: number;
  nextDueOn: string | null;
  overdue: number;
  overdueCount: number;
  overdueOver30: number;
  unpaid: InvoiceSummary[];
  recentPayments: Payment[];
};

export type Reminder = { id: string; invoiceId: string; invoiceNumber: string; level: number; createdOn: string; sentOn: string | null; author: string };

// --- Paramètres et absences --------------------------------------------------------------------------------

export type RoundingMode = "PROCHE" | "INFERIEUR" | "SUPERIEUR";
export type Service = "FORFAIT" | "HEURES_COMPLEMENTAIRES" | "DEPASSEMENT" | "ADAPTATION" | "FRAIS_INSCRIPTION";

export type BillingRule = {
  id: string;
  validFrom: string;
  validTo: string | null;
  extraRateMode: "CONTRAT" | "FIXE";
  extraHourlyRate: number;
  extraToleranceMinutes: number;
  extraRoundingMinutes: number;
  overtimeFee: number;
  overtimeToleranceMinutes: number;
  sickWaitingDays: number;
  leaveNoticeDays: number;
  paymentDays: number;
  totalRoundingStep: number;
  totalRoundingMode: RoundingMode;
  /** Mode proposé aux nouveaux contrats (planning fixe). */
  defaultBillingMode: "MENSUALISE" | "REEL";
  version: number;
};

export type BillingRuleInput = Omit<BillingRule, "id" | "version">;

export type AbsenceReason = {
  id: string;
  label: string;
  deductible: boolean;
  proofRequired: boolean;
  waitingDaysApply: boolean;
  noticeApplies: boolean;
  displayOrder: number;
  active: boolean;
  used: boolean;
  version: number;
};

export type AbsenceReasonInput = Omit<AbsenceReason, "id" | "used" | "version">;

export type TgcRate = { id: string; service: Service; rate: number; exempt: boolean; validFrom: string; validTo: string | null; version: number };
export type TgcRateInput = Omit<TgcRate, "id" | "version">;

export type Absence = {
  id: string;
  childId: string;
  childName: string;
  fromDay: string;
  toDay: string;
  reasonId: string;
  reason: string;
  deductible: boolean;
  proofRequired: boolean;
  notifiedOn: string;
  documentId: string | null;
  documentName: string | null;
  note: string | null;
  recordedBy: string;
  invoiced: boolean;
  version: number;
};

export type AbsenceInput = { fromDay: string; toDay: string; reasonId: string; notifiedOn: string; documentId: string | null; note: string | null };

// --- Libellés --------------------------------------------------------------------------------

export const statusLabels: Record<InvoiceStatus, string> = {
  EMISE: "En attente de paiement",
  PARTIELLE: "Payée en partie",
  PAYEE: "Payée",
  EN_RETARD: "En retard",
  ANNULEE: "Annulée par avoir",
};

export const methodLabels: Record<PaymentMethod, string> = {
  VIREMENT: "Virement",
  CHEQUE: "Chèque",
  ESPECES: "Espèces",
  CARTE: "Carte",
};

export const categoryLabels: Record<FunderCategory, string> = {
  PROVINCE: "Province",
  ORGANISME: "Organisme",
  EMPLOYEUR: "Employeur conventionné",
  COMMUNE: "Commune",
  AUTRE: "Autre",
};

export const serviceLabels: Record<Service, string> = {
  FORFAIT: "Forfait mensuel",
  HEURES_COMPLEMENTAIRES: "Heures complémentaires",
  DEPASSEMENT: "Départs après la fermeture",
  ADAPTATION: "Adaptation",
  FRAIS_INSCRIPTION: "Frais d’inscription",
};

export const reminderLevels = ["Rappel de paiement", "Deuxième relance", "Dernière relance"];

/** 200 → « 20 % », 55 → « 5,5 % » */
export function formatRate(tenths: number): string {
  return `${(tenths / 10).toLocaleString("fr-FR", { maximumFractionDigits: 1 })} %`;
}

/** « 20 » ou « 5,5 » → 200 ou 55 ; null si illisible. */
export function parseRate(input: string): number | null {
  const value = Number(input.replace(",", ".").replace("%", "").trim());
  if (!Number.isFinite(value) || value < 0 || value > 100) return null;
  return Math.round(value * 10);
}

/** Montant ou taux d'un financeur : « 12 000 F / mois », « 20 % des frais de garde ». */
export function funderValue(f: Pick<Funder, "calculation" | "amount" | "rate" | "rateBase" | "monthlyCap">, xpf: (n: number) => string): string {
  const value = f.calculation === "MONTANT_FIXE" ? `${xpf(f.amount)} / mois`
    : `${formatRate(f.rate)} ${f.rateBase === "FORFAIT" ? "du forfait" : "des frais de garde"}`;
  return f.monthlyCap === null ? value : `${value}, plafond ${xpf(f.monthlyCap)}`;
}

// --- Appels --------------------------------------------------------------------------------

export const billingKeys = {
  preview: (month: string) => ["billing", "preview", month] as const,
  familyPreview: (month: string, familyId: string) => ["billing", "preview", month, familyId] as const,
  invoices: (filter: string) => ["billing", "invoices", filter] as const,
  invoice: (id: string) => ["billing", "invoice", id] as const,
  requests: (month: string) => ["billing", "requests", month] as const,
  collections: ["billing", "collections"] as const,
  reminders: (invoiceId: string) => ["billing", "reminders", invoiceId] as const,
  funders: ["billing", "funders"] as const,
  fundings: (childId: string) => ["billing", "fundings", childId] as const,
  rules: ["billing", "rules"] as const,
  reasons: ["billing", "reasons"] as const,
  tgc: ["billing", "tgc"] as const,
  absences: (childId: string) => ["billing", "absences", childId] as const,
  absencesBetween: (from: string, to: string) => ["billing", "absences", from, to] as const,
};

async function open(path: string, fallback: string) {
  const { blob, fileName } = await apiBlob(path);
  saveBlob(blob, fileName ?? fallback);
}

export const billingApi = {
  preview: (month: string, signal?: AbortSignal) => api<MonthPreview>(`/billing/months/${month}/preview`, { signal }),
  familyPreview: (month: string, familyId: string, signal?: AbortSignal) =>
    api<FamilyPreview>(`/billing/months/${month}/preview/${familyId}`, { signal }),
  generate: (month: string, familyIds?: string[]) =>
    api<GenerationResult>(`/billing/months/${month}/generate`, { method: "POST", body: { familyIds: familyIds ?? null } }),

  invoices: (filter: { month?: string; familyId?: string; status?: InvoiceStatus }, signal?: AbortSignal) => {
    const params = new URLSearchParams(Object.entries(filter).filter(([, v]) => v) as [string, string][]);
    return api<InvoiceSummary[]>(`/billing/invoices?${params}`, { signal });
  },
  invoice: (id: string, signal?: AbortSignal) => api<Invoice>(`/billing/invoices/${id}`, { signal }),
  markSent: (id: string, sentOn: string, channel: string | null) =>
    api<Invoice>(`/billing/invoices/${id}/sent`, { method: "POST", body: { sentOn, channel } }),
  creditNote: (id: string, body: { reason: string; full: boolean; lines: { label: string; amount: number }[] }) =>
    api<CreditNote>(`/billing/invoices/${id}/credit-notes`, { method: "POST", body }),
  invoicePdf: (id: string, number: string) => open(`/billing/invoices/${id}/pdf`, `facture-${number}.pdf`),
  creditNotePdf: (id: string, number: string) => open(`/billing/credit-notes/${id}/pdf`, `avoir-${number}.pdf`),

  requests: (month: string | null, signal?: AbortSignal) =>
    api<FunderRequest[]>(`/billing/funder-requests${month ? `?month=${month}` : ""}`, { signal }),
  markRequestSent: (id: string, sentOn: string) =>
    api<FunderRequest>(`/billing/funder-requests/${id}/sent`, { method: "POST", body: { sentOn } }),
  requestPdf: (id: string, number: string) => open(`/billing/funder-requests/${id}/pdf`, `demande-${number}.pdf`),
  requestsXlsx: (month: string) => open(`/billing/funder-requests/export?month=${month}`, `demandes-financeurs-${month}.xlsx`),

  collections: (signal?: AbortSignal) => api<Collections>("/billing/collections", { signal }),
  pay: (body: PaymentInput) => api<Payment>("/billing/payments", { method: "POST", body }),
  cancelPayment: (id: string, reason: string) => api<Payment>(`/billing/payments/${id}/cancel`, { method: "POST", body: { reason } }),
  receiptPdf: (id: string, number: string) => open(`/billing/payments/${id}/receipt`, `recu-${number}.pdf`),
  reminders: (invoiceId: string, signal?: AbortSignal) => api<Reminder[]>(`/billing/invoices/${invoiceId}/reminders`, { signal }),
  remind: (invoiceId: string) => api<Reminder>(`/billing/invoices/${invoiceId}/reminders`, { method: "POST" }),
  markReminderSent: (id: string, sentOn: string) => api<Reminder>(`/billing/reminders/${id}/sent`, { method: "POST", body: { sentOn } }),
  reminderPdf: (id: string, invoiceNumber: string, level: number) => open(`/billing/reminders/${id}/pdf`, `relance-${level}-${invoiceNumber}.pdf`),
  attestationPdf: (familyId: string, year: number) => open(`/billing/families/${familyId}/attestation?year=${year}`, `attestation-${year}.pdf`),

  funders: (signal?: AbortSignal) => api<Funder[]>("/billing/funders", { signal }),
  createFunder: (body: FunderInput) => api<Funder>("/billing/funders", { method: "POST", body }),
  updateFunder: (id: string, body: FunderInput) => api<Funder>(`/billing/funders/${id}`, { method: "PUT", body }),
  deleteFunder: (id: string) => api<void>(`/billing/funders/${id}`, { method: "DELETE" }),
  fundings: (childId: string, signal?: AbortSignal) => api<ChildFunding[]>(`/children/${childId}/fundings`, { signal }),
  createFunding: (childId: string, body: ChildFundingInput) => api<ChildFunding>(`/children/${childId}/fundings`, { method: "POST", body }),
  updateFunding: (childId: string, id: string, body: ChildFundingInput) =>
    api<ChildFunding>(`/children/${childId}/fundings/${id}`, { method: "PUT", body }),
  deleteFunding: (childId: string, id: string) => api<void>(`/children/${childId}/fundings/${id}`, { method: "DELETE" }),

  rules: (signal?: AbortSignal) => api<BillingRule[]>("/billing/rules", { signal }),
  createRule: (body: BillingRuleInput) => api<BillingRule>("/billing/rules", { method: "POST", body }),
  updateRule: (id: string, body: BillingRuleInput) => api<BillingRule>(`/billing/rules/${id}`, { method: "PUT", body }),
  reasons: (signal?: AbortSignal) => api<AbsenceReason[]>("/billing/absence-reasons", { signal }),
  createReason: (body: AbsenceReasonInput) => api<AbsenceReason>("/billing/absence-reasons", { method: "POST", body }),
  updateReason: (id: string, body: AbsenceReasonInput) => api<AbsenceReason>(`/billing/absence-reasons/${id}`, { method: "PUT", body }),
  deleteReason: (id: string) => api<void>(`/billing/absence-reasons/${id}`, { method: "DELETE" }),
  tgcRates: (signal?: AbortSignal) => api<TgcRate[]>("/billing/tgc-rates", { signal }),
  createTgcRate: (body: TgcRateInput) => api<TgcRate>("/billing/tgc-rates", { method: "POST", body }),
  updateTgcRate: (id: string, body: TgcRateInput) => api<TgcRate>(`/billing/tgc-rates/${id}`, { method: "PUT", body }),

  absences: (childId: string, signal?: AbortSignal) => api<Absence[]>(`/children/${childId}/absences`, { signal }),
  absencesBetween: (from: string, to: string, signal?: AbortSignal) => api<Absence[]>(`/absences?from=${from}&to=${to}`, { signal }),
  createAbsence: (childId: string, body: AbsenceInput) => api<Absence>(`/children/${childId}/absences`, { method: "POST", body }),
  updateAbsence: (id: string, body: AbsenceInput) => api<Absence>(`/absences/${id}`, { method: "PUT", body }),
  deleteAbsence: (id: string) => api<void>(`/absences/${id}`, { method: "DELETE" }),
};
