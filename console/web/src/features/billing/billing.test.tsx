import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { connected, mockServer, renderApp } from "@/test/mockServer";
import type { Parc, Row } from "@/features/parc/api";
import type { BillingSettings, Board, ContractView, InvoiceDetail, InvoiceSummary, OutboxMessage } from "./api";

const row = (slug: string, name: string): Row => ({
  slug, name, city: null, clientName: name, lifecycle: "CLIENT", health: { level: "OK", reasons: [] }, lastContactAt: null, version: "1.5.0",
  plan: "Gestion", modules: [], places: 12, activeChildren: 10, licenseState: "ACTIVE", validUntil: "2027-10-31", deadline: null, desktops: 1,
  desktopLimit: 1, tablets: 2, tabletLimit: 2, backupAt: null, backupStale: false, diskUsedPercent: 30, container: "healthy", alerts: [],
  lastCallAt: null, balanceDue: 0, daysLate: 0,
});

const parc: Parc = {
  creches: [row("lagon-bleu", "Lagon Bleu"), row("tiare", "Crèche Tiare")],
  summary: { total: 2, ok: 2, warn: 0, bad: 0, off: 0, evaluationsEnding: 0, renewals: 0, versionsBehind: 0, latestVersion: "1.5.0", unpaid: 0 },
  sources: { supervisionAt: null, supervisionAttemptAt: null, supervisionError: null, hostAt: null, hostError: null, unlinkedSites: [], rejectedSites: [], hostDiskFree: null, hostDiskTotal: null },
};

function summary(o: Partial<InvoiceSummary>): InvoiceSummary {
  return {
    id: "f1", type: "FACTURE", number: null, status: "BROUILLON", kind: "ABONNEMENT", state: "BROUILLON", daysLate: 0, clientId: "c1",
    clientName: "SARL Lagon Bleu", crecheSlug: "lagon-bleu", crecheName: "Lagon Bleu", subject: "Abonnement 11/2026 – 10/2027",
    periodFrom: "2026-11-01", periodTo: "2027-10-31", issuedOn: null, dueOn: null, totalHt: 162_000, totalTgc: 17_820, totalTtc: 179_820,
    paid: 0, credited: 0, remaining: 0, sentAt: null, remindersPaused: false, correctsId: null, correctsNumber: null, ...o,
  };
}

const issuer = { name: "Ti'Cagou", legalForm: null, ridet: "1234567.001", tgcNumber: null, address: "Nouméa", email: null, phone: null, bank: "BCI", iban: "NC00 1", paymentTerms: null, latePenalties: null, footer: null, copyEmail: null };

function detail(s: InvoiceSummary): InvoiceDetail {
  return {
    summary: s,
    lines: [
      { id: "l1", position: 1, label: "Gestion Micro", detail: "01/11/2026 – 31/10/2027", quantity: 12, unitPrice: 11_500, amount: 138_000, tgcNature: "ABONNEMENT", tgcRate: 110, creditsLineId: null },
      { id: "l2", position: 2, label: "Module Repas", detail: null, quantity: 12, unitPrice: 2_000, amount: 24_000, tgcNature: "ABONNEMENT", tgcRate: 110, creditsLineId: null },
    ],
    rates: [{ rate: 110, base: 162_000, tgc: 17_820 }],
    note: null,
    client: { name: "SARL Lagon Bleu", crecheName: "Lagon Bleu" },
    issuer,
    credits: [],
    allocations: [],
    messages: [],
    creditable: s.status === "VALIDEE" ? [{ lineId: "l1", label: "Gestion Micro", amount: 138_000, alreadyCredited: 0, creditable: 138_000, tgcRate: 110 }, { lineId: "l2", label: "Module Repas", amount: 24_000, alreadyCredited: 0, creditable: 24_000, tgcRate: 110 }] : [],
    pdfSha256: s.status === "VALIDEE" ? "abcdef0123456789" : null,
    validatedAt: null,
    validatedBy: null,
    reminders: [],
    nextReminderOn: null,
  };
}

const settings: BillingSettings = {
  versions: [{
    list: { id: "b1", label: "Barème 2026", validFrom: "2026-01-01", validTo: null, microMax: 15, structureMax: 40, grandeMax: 80, includedDesktops: 1, includedTablets: 2, engagement3Discount: 100, extraStructureDiscount: 150 },
    items: [
      { kind: "FORMULE", code: "GESTION", tier: "MICRO", label: "Gestion Micro", price: 11_500, unit: null, includes: "INSCRIPTIONS,FACTURATION,PORTAIL_PARENTS", tgcNature: "ABONNEMENT" },
      { kind: "PRESTATION", code: "FORMATION_EQUIPE", tier: null, label: "Formation équipe (1 h, 8 personnes)", price: 15_000, unit: "séance", includes: null, tgcNature: "PRESTATION" },
    ],
  }],
  tgcRates: [{ id: "t1", nature: "ABONNEMENT", rate: 110, validFrom: "2026-01-01", validTo: null, note: "À confirmer" }],
  issuer,
  missingMentions: [],
};

const base = { "GET /auth/state": connected, "GET /parc": () => ({ body: parc }), "GET /envoi": () => ({ body: { messages: [], pending: 0 } }), "GET /bareme": () => ({ body: settings }) };

describe("facturation de l'éditeur", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=jeton-csrf";
  });

  it("valider un brouillon redemande le code, puis rejoue la validation", async () => {
    let confirmed = false;
    let validated = false;
    const calls = mockServer({
      ...base,
      "GET /factures": () => ({ body: [validated ? summary({ status: "VALIDEE", state: "A_ENVOYER", number: "F2026-0001", issuedOn: "2026-11-08", dueOn: "2026-12-08", remaining: 179_820 }) : summary({})] }),
      "GET /factures/f1": () => ({ body: detail(validated ? summary({ status: "VALIDEE", state: "A_ENVOYER", number: "F2026-0001", remaining: 179_820 }) : summary({})) }),
      "POST /factures/f1/valider": () => {
        if (!confirmed) {
          return { status: 403, body: { code: "confirmation_required", detail: "Saisissez votre code." } };
        }
        validated = true;
        return { body: detail(summary({ status: "VALIDEE", state: "A_ENVOYER", number: "F2026-0001", remaining: 179_820 })) };
      },
      "POST /auth/confirm": () => {
        confirmed = true;
        return { status: 204 };
      },
    });
    const user = userEvent.setup();
    renderApp("/factures?id=f1");

    expect(await screen.findByRole("article", { name: "Aperçu de la facture brouillon" })).toHaveTextContent("179 820 F");
    expect(screen.getByRole("status")).toHaveTextContent("1 brouillon attend votre validation");
    await user.click(screen.getByRole("button", { name: "Valider la facture" }));
    const dialog = await screen.findByRole("dialog", { name: "Confirmez avec votre code" });
    await user.type(within(dialog).getByLabelText("Code à 6 chiffres"), "123456");
    await user.click(within(dialog).getByRole("button", { name: "Confirmer" }));
    expect(await screen.findByRole("article", { name: "Aperçu de la facture F2026-0001" })).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(calls.filter((c) => c.path === "/factures/f1/valider")).toHaveLength(2);
    expect(calls.find((c) => c.path === "/auth/confirm")!.body).toEqual({ code: "123456" });
    expect(screen.getByRole("link", { name: "PDF" })).toHaveAttribute("href", "/api/v1/factures/f1/pdf");
  });

  it("nouvelle facture : prestation du barème, brouillon enregistré", async () => {
    const calls = mockServer({
      ...base,
      "GET /factures": () => ({ body: [] }),
      "POST /factures": () => ({ status: 201, body: detail(summary({ id: "f9", kind: "PRESTATION", subject: "Formation" })) }),
      "GET /factures/f9": () => ({ body: detail(summary({ id: "f9", kind: "PRESTATION", subject: "Formation" })) }),
    });
    const user = userEvent.setup();
    renderApp("/factures?nouvelle=lagon-bleu");
    await screen.findByRole("heading", { name: "Nouvelle facture" });
    await waitFor(() => expect(screen.getByLabelText("Crèche")).toHaveValue("lagon-bleu"));
    await user.type(screen.getByLabelText("Objet"), "Formation");
    await user.selectOptions(screen.getByLabelText("Ajouter une prestation du barème"), "FORMATION_EQUIPE");
    await user.clear(screen.getByLabelText("Quantité de la ligne 1"));
    await user.type(screen.getByLabelText("Quantité de la ligne 1"), "2");
    expect(screen.getByText("Total HT : 30 000 F", { normalizer: (t) => t.replace(/\s+/g, " ") })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Enregistrer le brouillon" }));
    await screen.findByRole("article", { name: "Aperçu de la facture brouillon" });
    expect(calls.find((c) => c.method === "POST" && c.path === "/factures")!.body).toEqual({
      crecheSlug: "lagon-bleu", subject: "Formation", periodFrom: null, periodTo: null, note: null,
      lines: [{ label: "Formation équipe (1 h, 8 personnes)", detail: null, quantity: 2, unitPrice: 15_000, tgcNature: "PRESTATION" }],
    });
  });

  it("paiement affecté facture par facture, puis avoir partiel confirmé par code", async () => {
    const open = summary({ status: "VALIDEE", state: "RETARD", daysLate: 16, number: "F2026-0001", issuedOn: "2026-10-17", dueOn: "2026-10-24", remaining: 179_820 });
    const calls = mockServer({
      ...base,
      "GET /factures": () => ({ body: [open] }),
      "GET /factures/f1": () => ({ body: detail(open) }),
      "POST /paiements": () => ({ status: 201, body: {} }),
      "POST /factures/f1/avoir": () => ({ status: 201, body: detail(summary({ id: "a1", type: "AVOIR", kind: "AVOIR", status: "VALIDEE", state: "AVOIR", number: "AV2026-0001" })) }),
      "GET /factures/a1": () => ({ body: detail(summary({ id: "a1", type: "AVOIR", kind: "AVOIR", status: "VALIDEE", state: "AVOIR", number: "AV2026-0001" })) }),
    });
    const user = userEvent.setup();
    renderApp("/factures?id=f1");
    expect(await screen.findByText("Retard 16 j")).toBeInTheDocument();

    await user.click(within(screen.getByRole("article").parentElement!).getByRole("button", { name: "Saisir un paiement" }));
    await user.type(screen.getByLabelText("Montant (F)"), "4860");
    await user.type(screen.getByLabelText("Référence"), "VIR 4860");
    await user.click(screen.getByLabelText("Facture par facture"));
    await user.type(screen.getByLabelText(/F2026-0001 · Abonnement/), "4860");
    await user.click(screen.getByRole("button", { name: "Enregistrer le paiement" }));
    await waitFor(() => expect(calls.some((c) => c.path === "/paiements")).toBe(true));
    expect(calls.find((c) => c.path === "/paiements")!.body).toMatchObject({
      crecheSlug: "lagon-bleu", amount: 4860, method: "VIREMENT", reference: "VIR 4860", allocations: [{ invoiceId: "f1", amount: 4860 }],
    });

    await user.click(await screen.findByRole("button", { name: "Avoir…" }));
    await user.type(screen.getByLabelText(/Module Repas/), "24000");
    await user.type(screen.getByLabelText("Motif"), "Repas jamais utilisé");
    await user.click(screen.getByRole("button", { name: "Émettre l’avoir" }));
    expect(await screen.findByRole("article", { name: "Aperçu de l’avoir AV2026-0001" })).toBeInTheDocument();
    expect(calls.find((c) => c.path === "/factures/f1/avoir")!.body).toEqual({ lines: [{ lineId: "l2", amount: 24_000 }], reason: "Repas jamais utilisé" });
  });

  it("boîte d'envoi : relire, corriger puis envoyer ; un refus de la messagerie s'affiche", async () => {
    const message: OutboxMessage = {
      id: "m1", kind: "FACTURE", invoiceId: "f1", invoiceNumber: "F2026-0001", invoiceTotal: 179_820, clientName: "SARL Lagon Bleu", crecheSlug: "lagon-bleu",
      crecheName: "Lagon Bleu", to: "compta@lagon-bleu.nc", cc: "facturation@ticagou.nc", subject: "Votre facture F2026-0001", body: "Bonjour,\n\nVoici la facture.",
      attachmentName: "F2026-0001.pdf", status: "PREPARE", createdAt: "2026-11-08T00:00:00Z", sentAt: null, sentBy: null, lastError: null, discardedAt: null, discardReason: null,
      reminderLevel: null, attachments: ["F2026-0001.pdf"], invoiceNumbers: ["F2026-0001"], invoiceIds: ["f1"],
    };
    let refuse = true;
    const calls = mockServer({
      ...base,
      "GET /envoi": () => ({ body: { messages: [message], pending: 1 } }),
      "PUT /envoi/m1": (call) => ({ body: { ...message, ...(call.body as object) } }),
      "POST /envoi/envoyer": () => {
        const result = refuse ? { id: "m1", sent: false, error: "Le serveur de messagerie a refusé l’envoi." } : { id: "m1", sent: true, error: null };
        refuse = false;
        return { body: [result] };
      },
    });
    const user = userEvent.setup();
    renderApp("/envoi");
    expect(await screen.findByRole("option", { name: /Lagon Bleu/ })).toBeInTheDocument();
    expect(screen.getByLabelText("1 en attente")).toBeInTheDocument();
    await user.clear(screen.getByLabelText("Objet"));
    await user.type(screen.getByLabelText("Objet"), "Facture F2026-0001 — abonnement");
    await user.click(screen.getByRole("button", { name: "Envoyer" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("refusé");
    await user.click(screen.getByRole("button", { name: "Envoyer" }));
    await waitFor(() => expect(calls.filter((c) => c.path === "/envoi/envoyer")).toHaveLength(2));
    expect(calls.find((c) => c.method === "PUT")!.body).toMatchObject({ subject: "Facture F2026-0001 — abonnement", to: "compta@lagon-bleu.nc" });

    await user.click(screen.getByRole("checkbox", { name: /Sélectionner/ }));
    expect(screen.getByRole("button", { name: "Envoyer la sélection (1)" })).toBeEnabled();
  });

  it("contrat : devis en direct puis enregistrement", async () => {
    const empty: ContractView = { current: null, history: [], priceListLabel: null, monthly: null, period: null, expected: null, next: null };
    const quote = { tier: "MICRO", onQuote: false, subtotal: 162_000, total: 162_000, lines: [{ code: "GESTION", label: "Gestion Micro", detail: "12 mois à 11 500 F", quantity: 12, unitPrice: 11_500, amount: 138_000, tgcNature: "ABONNEMENT" }, { code: "REPAS", label: "Module Repas", detail: "12 mois à 2 000 F", quantity: 12, unitPrice: 2_000, amount: 24_000, tgcNature: "ABONNEMENT" }] };
    const calls = mockServer({
      ...base,
      "GET /parc/lagon-bleu": () => ({ body: { row: row("lagon-bleu", "Lagon Bleu"), domain: null, clientSince: null, createdAt: "2026-01-01T00:00:00Z", supervisionName: null, hostSeenAt: null, client: { id: "c1", name: "Lagon Bleu", kind: "PRIVE", ridet: null, address: null, directorName: null, directorEmail: null, directorPhone: null, billingEmail: null, reminderEmail: null, paymentDays: 30 }, status: null, subscription: null, history: [], points: [], notes: [], journal: [] } }),
      "GET /parc/lagon-bleu/contrat": () => ({ body: empty }),
      "POST /parc/lagon-bleu/contrat/devis": () => ({ body: [{ ...quote, total: 13_500 }, quote] }),
      "PUT /parc/lagon-bleu/contrat": () => ({ body: { contract: empty, complementInvoiceId: null, toCredit: 0, toCreditInvoiceNumber: null, amendment: [] } }),
    });
    const user = userEvent.setup();
    renderApp("/parc/lagon-bleu");
    await user.click(await screen.findByRole("tab", { name: "Abonnement" }));
    await user.click(await screen.findByRole("button", { name: "Saisir le contrat" }));
    await user.click(screen.getByRole("checkbox", { name: "Repas" }));
    expect(await screen.findByText("Total HT par an")).toBeInTheDocument();
    expect(screen.getByText("Soit 13 500 F HT par mois, TGC en sus.", { normalizer: (t) => t.replace(/\s+/g, " ") })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Enregistrer le contrat" }));
    await waitFor(() => expect(calls.some((c) => c.method === "PUT")).toBe(true));
    expect(calls.find((c) => c.method === "PUT")!.body).toMatchObject({ plan: "GESTION", places: 12, modules: ["REPAS"], rhythm: "ANNUEL", engagementYears: 1 });
  });

  it("paramètres : mentions manquantes signalées puis enregistrées", async () => {
    const calls = mockServer({
      ...base,
      "GET /bareme": () => ({ body: { ...settings, issuer: { ...issuer, ridet: null }, missingMentions: ["RIDET"] } }),
      "PUT /editeur": () => ({ body: settings }),
    });
    const user = userEvent.setup();
    renderApp("/parametres");
    expect(await screen.findByRole("heading", { name: "Barème 2026" })).toBeInTheDocument();
    await user.click(screen.getByRole("tab", { name: "Éditeur et TGC" }));
    expect(await screen.findByText("Aucune facture ne peut être validée sans : RIDET.")).toBeInTheDocument();
    await user.type(screen.getByLabelText("RIDET"), "1234567.001");
    await user.click(screen.getByRole("button", { name: "Enregistrer" }));
    expect(await screen.findByText("Mentions enregistrées.")).toBeInTheDocument();
    expect(calls.find((c) => c.path === "/editeur")!.body).toMatchObject({ ridet: "1234567.001", name: "Ti'Cagou" });
  });

  it("tableau de bord : indicateurs de facturation et envois en tête de la liste", async () => {
    const board: Board = {
      mrr: 171_000, arr: 2_052_000, outstanding: 412_650, overdue: 186_480, overdueCount: 3, dso: 27, drafts: 2, renewalDrafts: 2, pendingMessages: 4,
      months: ["2026-06", "2026-07", "2026-08", "2026-09", "2026-10", "2026-11"].map((month) => ({ month, invoiced: 100_000, collected: 80_000 })),
      late: [summary({ status: "VALIDEE", state: "RETARD", daysLate: 16, number: "F2026-0047", remaining: 174_960, dueOn: "2026-10-24" })],
      draftList: [summary({}), summary({ id: "f2", crecheName: "Crèche Tiare" })],
      suspensions: [],
    };
    mockServer({ ...base, "GET /tableau": () => ({ body: board }) });
    renderApp("/");
    expect(await screen.findByText("171 000 F", { normalizer: (t) => t.replace(/\s+/g, " ") })).toBeInTheDocument();
    expect(screen.getByText("27 j")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Boîte d’envoi (4)" })).toBeInTheDocument();
    const list = within(screen.getByRole("heading", { name: "À faire aujourd’hui" }).closest("section")!).getAllByRole("listitem");
    expect(list[0]).toHaveTextContent("4 messages préparés");
    expect(list[1]).toHaveTextContent("2 brouillons à valider : Lagon Bleu, Crèche Tiare");
    expect(list[2]).toHaveTextContent("facture F2026-0047 en retard de 16 jours");
    expect(screen.getByRole("img", { name: /Facturé et encaissé par mois/ })).toBeInTheDocument();
  });
});
