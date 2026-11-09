import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";

export type InvoiceState = "BROUILLON" | "A_ENVOYER" | "ENVOYEE" | "PARTIELLE" | "PAYEE" | "RETARD" | "ANNULEE" | "AVOIR";
export type TgcNature = "ABONNEMENT" | "PRESTATION" | "MATERIEL";

export type InvoiceSummary = {
  id: string;
  type: "FACTURE" | "AVOIR";
  number: string | null;
  status: "BROUILLON" | "VALIDEE";
  kind: "ABONNEMENT" | "COMPLEMENT" | "PRESTATION" | "AVOIR";
  state: InvoiceState;
  daysLate: number;
  clientId: string;
  clientName: string | null;
  crecheSlug: string | null;
  crecheName: string | null;
  subject: string;
  periodFrom: string | null;
  periodTo: string | null;
  issuedOn: string | null;
  dueOn: string | null;
  totalHt: number;
  totalTgc: number;
  totalTtc: number;
  paid: number;
  credited: number;
  remaining: number;
  sentAt: string | null;
  remindersPaused: boolean;
  correctsId: string | null;
  correctsNumber: string | null;
};

export type InvoiceLine = { id: string; position: number; label: string; detail: string | null; quantity: number; unitPrice: number; amount: number; tgcNature: TgcNature; tgcRate: number; creditsLineId: string | null };
export type RateTotal = { rate: number; base: number; tgc: number };
export type Allocation = { paymentId: string; invoiceId: string; invoiceNumber: string; receivedOn: string; method: string; reference: string | null; amount: number };
export type CreditableLine = { lineId: string; label: string; amount: number; alreadyCredited: number; creditable: number; tgcRate: number };

export type OutboxMessage = {
  id: string;
  kind: "FACTURE" | "AVOIR" | "RELANCE";
  invoiceId: string | null;
  invoiceNumber: string | null;
  invoiceTotal: number;
  clientName: string;
  crecheSlug: string | null;
  crecheName: string | null;
  to: string | null;
  cc: string | null;
  subject: string;
  body: string;
  attachmentName: string | null;
  status: "PREPARE" | "ENVOYE" | "ECARTE";
  createdAt: string;
  sentAt: string | null;
  sentBy: string | null;
  lastError: string | null;
  discardedAt: string | null;
  discardReason: string | null;
};

export type InvoiceDetail = {
  summary: InvoiceSummary;
  lines: InvoiceLine[];
  rates: RateTotal[];
  note: string | null;
  client: { name?: string; address?: string | null; ridet?: string | null; crecheName?: string | null };
  issuer: Issuer;
  credits: InvoiceSummary[];
  allocations: Allocation[];
  messages: OutboxMessage[];
  creditable: CreditableLine[];
  pdfSha256: string | null;
  validatedAt: string | null;
  validatedBy: string | null;
};

export type Issuer = {
  name: string | null;
  legalForm: string | null;
  ridet: string | null;
  tgcNumber: string | null;
  address: string | null;
  email: string | null;
  phone: string | null;
  bank: string | null;
  iban: string | null;
  paymentTerms: string | null;
  latePenalties: string | null;
  footer: string | null;
  copyEmail: string | null;
};

export type PriceList = { id: string; label: string; validFrom: string; validTo: string | null; microMax: number; structureMax: number; grandeMax: number; includedDesktops: number; includedTablets: number; engagement3Discount: number; extraStructureDiscount: number };
export type PriceItem = { kind: "FORMULE" | "MODULE" | "EQUIPEMENT" | "SUPPORT" | "PRESTATION"; code: string; tier: "MICRO" | "STRUCTURE" | "GRANDE" | null; label: string; price: number; unit: string | null; includes: string | null; tgcNature: TgcNature };
export type TgcRate = { id: string; nature: TgcNature; rate: number; validFrom: string; validTo: string | null; note: string | null };
export type BillingSettings = { versions: { list: PriceList; items: PriceItem[] }[]; tgcRates: TgcRate[]; issuer: Issuer; missingMentions: string[] };

export type QuoteLine = { code: string; label: string; detail: string; quantity: number; unitPrice: number; amount: number; tgcNature: TgcNature };
export type Quote = { tier: "MICRO" | "STRUCTURE" | "GRANDE"; onQuote: boolean; lines: QuoteLine[]; subtotal: number; total: number };

export type Contract = {
  id: string;
  plan: "ESSENTIEL" | "GESTION" | "COMPLET";
  places: number;
  modules: string[];
  extraDesktops: number;
  extraTablets: number;
  premiumSupport: boolean;
  engagementYears: 1 | 3;
  rhythm: "ANNUEL" | "MENSUEL";
  extraStructure: boolean;
  freeDiscount: number;
  freeDiscountReason: string | null;
  startsOn: string;
  endsOn: string | null;
  signedOn: string;
};

export type ContractView = {
  current: Contract | null;
  history: Contract[];
  priceListLabel: string | null;
  monthly: Quote | null;
  period: Quote | null;
  expected: { plan: string; modules: string[]; places: number; desktops: number; tablets: number; validUntil: string | null; differences: string[] } | null;
  next: { from: string; to: string; preparedFrom: string; due: boolean } | null;
};

export type ContractInput = Omit<Contract, "id" | "endsOn"> & { changeOn: string | null };
export type ContractSaved = { contract: ContractView; complementInvoiceId: string | null; toCredit: number; toCreditInvoiceNumber: string | null; amendment: QuoteLine[] };

export type Payment = { payment: { id: string; clientId: string; receivedOn: string; amount: number; method: string; reference: string | null; note: string | null; operatorLogin: string | null; allocated: number }; clientName: string; allocations: Allocation[] };

export type Board = {
  mrr: number;
  arr: number;
  outstanding: number;
  overdue: number;
  overdueCount: number;
  dso: number | null;
  drafts: number;
  renewalDrafts: number;
  pendingMessages: number;
  months: { month: string; invoiced: number; collected: number }[];
  late: InvoiceSummary[];
  draftList: InvoiceSummary[];
};

export type LineInput = { label: string; detail: string | null; quantity: number; unitPrice: number; tgcNature: TgcNature };
export type DraftInput = { crecheSlug: string; subject: string; periodFrom: string | null; periodTo: string | null; note: string | null; lines: LineInput[] };

export const billingKeys = {
  invoices: ["factures"] as const,
  invoice: (id: string) => ["factures", id] as const,
  outbox: ["envoi"] as const,
  settings: ["bareme"] as const,
  board: ["tableau"] as const,
  payments: ["paiements"] as const,
  contract: (slug: string) => ["contrat", slug] as const,
};

export const useInvoices = () => useQuery({ queryKey: billingKeys.invoices, queryFn: ({ signal }) => api<InvoiceSummary[]>("/factures", { signal }) });
export const useInvoice = (id: string | null) =>
  useQuery({ queryKey: billingKeys.invoice(id ?? ""), queryFn: ({ signal }) => api<InvoiceDetail>(`/factures/${id}`, { signal }), enabled: !!id });
export const useOutbox = () =>
  useQuery({ queryKey: billingKeys.outbox, queryFn: ({ signal }) => api<{ messages: OutboxMessage[]; pending: number }>("/envoi", { signal }), refetchInterval: 60_000 });
export const useBillingSettings = () => useQuery({ queryKey: billingKeys.settings, queryFn: ({ signal }) => api<BillingSettings>("/bareme", { signal }) });
export const useBoard = () => useQuery({ queryKey: billingKeys.board, queryFn: ({ signal }) => api<Board>("/tableau", { signal }), refetchInterval: 60_000 });
export const usePayments = () => useQuery({ queryKey: billingKeys.payments, queryFn: ({ signal }) => api<Payment[]>("/paiements", { signal }) });
export const useContract = (slug: string) =>
  useQuery({ queryKey: billingKeys.contract(slug), queryFn: ({ signal }) => api<ContractView>(`/parc/${encodeURIComponent(slug)}/contrat`, { signal }) });

/** Après toute écriture de facturation : factures, envois, tableau, parc et paiements sont relus. */
export function useBillingWrite<I, O>(write: (input: I) => Promise<O>) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: write,
    onSuccess: () => {
      for (const key of [["factures"], ["envoi"], ["tableau"], ["parc"], ["paiements"], ["contrat"], ["bareme"]]) {
        void client.invalidateQueries({ queryKey: key });
      }
    },
  });
}

export const billingApi = {
  createDraft: (input: DraftInput) => api<InvoiceDetail>("/factures", { method: "POST", body: input }),
  updateDraft: (id: string, input: DraftInput) => api<InvoiceDetail>(`/factures/${id}`, { method: "PUT", body: input }),
  deleteDraft: (id: string) => api<void>(`/factures/${id}`, { method: "DELETE" }),
  validate: (id: string) => api<InvoiceDetail>(`/factures/${id}/valider`, { method: "POST" }),
  validateMany: (ids: string[]) => api<{ id: string; number: string | null; error: string | null }[]>("/factures/valider", { method: "POST", body: { ids } }),
  credit: (id: string, lines: { lineId: string; amount: number }[], reason: string) =>
    api<InvoiceDetail>(`/factures/${id}/avoir`, { method: "POST", body: { lines, reason } }),
  pause: (id: string, paused: boolean) => api<InvoiceDetail>(`/factures/${id}/relances`, { method: "POST", body: { paused } }),
  pay: (input: { crecheSlug: string; receivedOn: string; amount: number; method: string; reference: string | null; note: string | null; allocations: { invoiceId: string; amount: number }[] }) =>
    api<Payment>("/paiements", { method: "POST", body: input }),
  deletePayment: (id: string) => api<void>(`/paiements/${id}`, { method: "DELETE" }),
  quote: (slug: string, input: ContractInput) => api<Quote[]>(`/parc/${encodeURIComponent(slug)}/contrat/devis`, { method: "POST", body: input }),
  saveContract: (slug: string, input: ContractInput) => api<ContractSaved>(`/parc/${encodeURIComponent(slug)}/contrat`, { method: "PUT", body: input }),
  terminate: (slug: string, endsOn: string) => api<ContractView>(`/parc/${encodeURIComponent(slug)}/contrat/resiliation`, { method: "POST", body: { endsOn } }),
  prepareNext: (slug: string) => api<InvoiceDetail>(`/parc/${encodeURIComponent(slug)}/contrat/prochaine-facture`, { method: "POST" }),
  editMessage: (id: string, m: { to: string | null; cc: string | null; subject: string; body: string }) => api<OutboxMessage>(`/envoi/${id}`, { method: "PUT", body: m }),
  send: (ids: string[]) => api<{ id: string; sent: boolean; error: string | null }[]>("/envoi/envoyer", { method: "POST", body: { ids } }),
  discard: (ids: string[], reason: string | null) => api<void>("/envoi/ecarter", { method: "POST", body: { ids, reason } }),
  newPriceList: (input: Omit<PriceList, "id" | "validTo"> & { items: PriceItem[] }) => api<BillingSettings>("/bareme", { method: "POST", body: input }),
  newRate: (input: { nature: TgcNature; rate: number; validFrom: string; note: string | null }) => api<BillingSettings>("/tgc", { method: "POST", body: input }),
  saveIssuer: (issuer: Issuer) => api<BillingSettings>("/editeur", { method: "PUT", body: issuer }),
};
