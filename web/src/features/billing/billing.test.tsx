import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { director, healthUp, installed, mockApi, renderAt, session, structure } from "@/test/render";
import type { Me } from "@/features/auth/types";
import { todayIso } from "@/lib/age";
import { formatRate, parseRate, type Collections, type FunderRequest, type Invoice, type InvoiceSummary, type MonthPreview } from "./api";
import { billableMonths, previousMonth } from "./components";

const month = previousMonth();

const row = (over: Partial<MonthPreview["families"][number]>): MonthPreview["families"][number] => ({
  familyId: "f-1", familyLabel: "Famille Dupré", payerName: "Clémence Dupré", children: ["Maëlys"], forfait: 68_000,
  extraHours: 0, overtime: 2_000, absences: 4_400, other: 0, tgc: 0, careTotal: 65_600, aids: { cafat: 12_000 },
  aidTotal: 12_000, familyTotal: 53_600, status: "PRETE", anomalyCount: 0, invoiceId: null, invoiceNumber: null, ...over,
});

const preview: MonthPreview = {
  month, generationAllowed: true,
  funders: [{ id: "cafat", name: "CAFAT" }],
  families: [
    row({}),
    row({ familyId: "f-2", familyLabel: "Famille Tehei", payerName: "Moana Tehei", children: ["Hina"], aids: {}, aidTotal: 0,
      familyTotal: 70_000, status: "ANOMALIE", anomalyCount: 1 }),
  ],
  anomalies: [{
    familyId: "f-2", familyLabel: "Famille Tehei", childId: "c-2", childName: "Hina Tehei", type: "DEPART_MANQUANT",
    message: "Hina Tehei — pas d’heure de départ le 14/10.", days: [`${month}-14`],
  }],
  totals: { careTotal: 135_600, familyTotal: 123_600, aidTotal: 12_000, aidByFunder: { cafat: 12_000 }, families: 2, ready: 1, withAnomalies: 1, invoiced: 0 },
};

const summary: InvoiceSummary = {
  id: "inv-1", number: "FA-2026-0147", familyId: "f-1", familyLabel: "Famille Dupré", payerName: "Clémence Dupré", month,
  issuedOn: todayIso(), dueOn: "2026-11-10", familyTotal: 53_600, aidTotal: 12_000, credited: 0, paid: 0, remaining: 53_600,
  status: "EMISE", sentOn: null, reminderLevel: 0, daysLate: 0,
};

const invoice: Invoice = {
  summary, payerAddress: "12 rue Jules Ferry\n98800 Nouméa", careTotal: 65_600, tgcTotal: 0, rounding: 0,
  contractText: "5 jours par semaine, 07:00 à 17:00", issuedBy: "Nathalie Kaloï",
  lines: [
    { childId: "c-1", childName: "Maëlys", kind: "FORFAIT", label: "Accueil d’octobre (forfait mensuel)", detail: "Contrat n° 2026-012", quantity: null, unitPrice: null, amount: 68_000, tgcRate: 0, tgcAmount: 0, total: 68_000, funderId: null },
    { childId: "c-1", childName: "Maëlys", kind: "DEPASSEMENT", label: "Départs après la fermeture", detail: "le 14/10 à 17:48 · 2 × 1 000 F", quantity: "2", unitPrice: 1000, amount: 2_000, tgcRate: 0, tgcAmount: 0, total: 2_000, funderId: null },
    { childId: "c-1", childName: "Maëlys", kind: "AIDE", label: "CAFAT", detail: "Montant mensuel", quantity: null, unitPrice: null, amount: -12_000, tgcRate: 0, tgcAmount: 0, total: -12_000, funderId: "cafat" },
  ],
  creditNotes: [], payments: [],
  events: [{ kind: "GENEREE", label: "Facture générée (octobre 2026)", author: "Nathalie Kaloï", at: new Date().toISOString() }],
};

const overdue: InvoiceSummary = { ...summary, id: "inv-0", number: "FA-2026-0101", dueOn: "2026-09-10", status: "EN_RETARD", daysLate: 45, remaining: 43_000, familyTotal: 43_000 };
const collections: Collections = {
  collectedThisMonth: 412_400, collectedCount: 9, upcoming: 53_600, upcomingCount: 1, nextDueOn: "2026-11-10",
  overdue: 43_000, overdueCount: 1, overdueOver30: 1, unpaid: [overdue, summary],
  recentPayments: [{
    id: "p-1", payerName: "Paul Boiguivie", familyId: "f-3", funderId: null, paidOn: "2026-10-25", amount: 45_200, method: "ESPECES",
    reference: null, bank: null, receiptNumber: "R-2026-0118", recordedBy: "Nathalie Kaloï", cancelled: false, cancelReason: null,
    allocations: [{ invoiceId: "inv-9", funderRequestId: null, number: "FA-2026-0099", amount: 45_200 }],
  }],
};

const request: FunderRequest = {
  id: "r-1", number: "DF-2026-0012", funderId: "cafat", funderName: "CAFAT", conventionRef: "2024-118", month, total: 12_000,
  received: 0, remaining: 12_000, sentOn: null, sentBy: null, status: "A_ENVOYER", version: 0,
  lines: [{ childId: "c-1", childName: "Maëlys Dupré", beneficiary: "Clémence Dupré", invoiceId: "inv-1", invoiceNumber: "FA-2026-0147", attendanceDays: 20, amount: 12_000 }],
};

function server(user: Me = director, extra: Record<string, Parameters<typeof mockApi>[0][string]> = {}) {
  window.localStorage.setItem("ticagou.refreshToken", "refresh-1");
  return mockApi({
    "GET /setup/status": { body: installed },
    "GET /health": { body: healthUp },
    "POST /auth/refresh": { body: session(user) },
    "GET /structure": { body: structure },
    [`GET /billing/months/${month}/preview`]: { body: preview },
    "GET /billing/invoices/inv-1": { body: invoice },
    "GET /billing/collections": { body: collections },
    "GET /billing/invoices": { body: [summary] },
    "GET /billing/funder-requests": { body: [request] },
    ...extra,
  });
}

describe("facturer le mois", () => {
  it("présente les anomalies et génère les factures des familles prêtes", async () => {
    const { calls } = server(director, {
      [`POST /billing/months/${month}/generate`]: {
        status: 201,
        body: { month, invoices: 1, firstNumber: "FA-2026-0147", lastNumber: "FA-2026-0147", familyTotal: 53_600, aidTotal: 12_000,
          skipped: ["Famille Tehei — anomalie à corriger"], funderRequests: [{ id: "r-1", number: "DF-2026-0012", funderName: "CAFAT", total: 12_000 }] },
      },
    });
    renderAt("/facturation");
    const user = userEvent.setup();

    expect(await screen.findByText("1 famille à vérifier avant de facturer")).toBeInTheDocument();
    expect(screen.getByText("Hina Tehei — pas d’heure de départ le 14/10.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Compléter le pointage" })).toHaveAttribute("href", `/presences?date=${month}-14`);
    expect(screen.getByText("1 sur 2")).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "CAFAT" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Générer les factures" }));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText(/Non facturées pour l’instant : Famille Tehei/)).toBeInTheDocument();
    expect(within(dialog).getByText("CAFAT · 1 famille")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Générer 1 facture" }));

    expect(await screen.findByText(/1 facture générée : FA-2026-0147. 1 demande de règlement préparée/)).toBeInTheDocument();
    expect(calls.some((c) => c.key === `POST /billing/months/${month}/generate`)).toBe(true);
  });

  it("n’apparaît pas dans la navigation de l’éducatrice", async () => {
    server({ ...director, role: "EDUCATRICE" }, { "GET /attendance": { body: { day: todayIso(), children: [], notArrived: [], staff: [], expected: 0 } } });
    renderAt("/");
    const nav = await screen.findByRole("navigation", { name: /principale/i });
    expect(within(nav).queryByRole("link", { name: "Facturation" })).not.toBeInTheDocument();
  });
});

describe("facture", () => {
  it("affiche frais, aides, reste à charge et enregistre un paiement partiel par chèque", async () => {
    const { calls } = server(director, {
      "POST /billing/payments": { status: 201, body: { ...collections.recentPayments[0], id: "p-2", method: "CHEQUE", receiptNumber: null } },
    });
    renderAt("/facturation/factures/inv-1");
    const user = userEvent.setup();

    expect(await screen.findByRole("heading", { name: "Facture FA-2026-0147" })).toBeInTheDocument();
    expect(screen.getByText("En attente de paiement")).toBeInTheDocument();
    expect(screen.getByText("5 jours par semaine, 07:00 à 17:00")).toBeInTheDocument();
    expect(screen.getByText("Départs après la fermeture")).toBeInTheDocument();
    expect(screen.getByText("Aides qui réduisent la facture")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Enregistrer un paiement" }));
    const dialog = screen.getByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Chèque" }));
    const amount = within(dialog).getByLabelText("Montant (F)");
    await user.clear(amount);
    await user.type(amount, "20000");
    expect(within(dialog).getByText(/Paiement partiel : il restera 33 600 F à régler/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Enregistrer le paiement" }));
    expect(within(dialog).getByText("Indiquez le numéro du chèque.")).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText("N° du chèque"), "0004521");
    await user.type(within(dialog).getByLabelText("Banque émettrice"), "BCI");
    await user.click(within(dialog).getByRole("button", { name: "Enregistrer le paiement" }));

    await waitFor(() =>
      expect(calls.find((c) => c.key === "POST /billing/payments")?.body).toEqual({
        invoiceId: "inv-1", paidOn: todayIso(), amount: 20_000, method: "CHEQUE", reference: "0004521", bank: "BCI",
      }),
    );
  });
});

describe("encaissements et relances", () => {
  it("montre les impayés échus et prépare la lettre de relance", async () => {
    const { calls } = server(director, {
      "POST /billing/invoices/inv-0/reminders": { status: 201, body: { id: "rem-1", invoiceId: "inv-0", invoiceNumber: "FA-2026-0101", level: 1, createdOn: todayIso(), sentOn: null, author: "Nathalie Kaloï" } },
      "POST /billing/reminders/rem-1/sent": { body: {} },
    });
    renderAt("/facturation/encaissements");
    const user = userEvent.setup();

    expect(await screen.findByText("412 400 F")).toBeInTheDocument();
    expect(screen.getByText("1 facture, dont 1 de plus de 30 jours")).toBeInTheDocument();
    const unpaid = screen.getAllByRole("table")[0];
    expect(within(unpaid).getByText("45 jours")).toBeInTheDocument();
    expect(screen.getByText(/reçu R-2026-0118/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Relancer" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/la facture FA-2026-0101 .* n’est pas encore réglée/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Noter comme envoyée" }));
    expect(await within(dialog).findByText("Envoi noté.")).toBeInTheDocument();
    expect(calls.find((c) => c.key === "POST /billing/reminders/rem-1/sent")?.body).toEqual({ sentOn: todayIso() });
  });
});

describe("demandes aux financeurs", () => {
  it("détaille la demande par enfant et note son envoi", async () => {
    const { calls } = server(director, { "POST /billing/funder-requests/r-1/sent": { body: { ...request, sentOn: todayIso(), status: "ENVOYEE" } } });
    renderAt("/facturation/financeurs");
    const user = userEvent.setup();

    expect(await screen.findByRole("heading", { name: /CAFAT ·/ })).toBeInTheDocument();
    expect(screen.getByText("Clémence Dupré")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "FA-2026-0147" })).toHaveAttribute("href", "/facturation/factures/inv-1");
    await user.click(screen.getByRole("button", { name: "Marquer comme envoyée" }));
    await waitFor(() => expect(calls.find((c) => c.key === "POST /billing/funder-requests/r-1/sent")?.body).toEqual({ sentOn: todayIso() }));
  });
});

describe("outils", () => {
  it("convertit les taux en dixièmes de pour cent", () => {
    expect(parseRate("20")).toBe(200);
    expect(parseRate("5,5 %")).toBe(55);
    expect(parseRate("120")).toBeNull();
    expect(formatRate(55)).toBe("5,5 %");
  });

  it("propose le mois écoulé puis les précédents", () => {
    expect(billableMonths(2, "2027-01-15")).toEqual(["2027-01", "2026-12", "2026-11"]);
    expect(previousMonth("2027-01-15")).toBe("2026-12");
  });
});
