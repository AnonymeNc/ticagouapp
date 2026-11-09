import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { connected, mockServer, renderApp } from "@/test/mockServer";
import type { OutboxMessage, ReminderRule, ReminderSettings } from "./api";

const rule = (level: number, label: string, days: number, active: boolean): ReminderRule => ({
  level, label, days, active, subject: `${label} — facture {numero}`, body: "{bonjour}\n\n{factures}\n\n{editeur}", copyToIssuer: level >= 2, attachPdf: level >= 1, proposeSuspension: level === 3,
});

const settings: ReminderSettings = {
  sets: [{ id: "s1", label: "Relances 2026", validFrom: "2026-01-01", validTo: null }],
  rules: { s1: [rule(0, "Rappel courtois", -3, false), rule(1, "Relance 1", 7, true), rule(2, "Relance 2", 15, true), rule(3, "Relance 3", 30, true)] },
  holidays: [{ day: "2099-12-25", label: "Noël" }],
  clients: [
    { clientId: "c1", clientName: "Commune de Païta", crecheSlug: "paita", crecheName: "Crèche municipale de Païta", paymentDays: 45, shiftDays: 15, paused: false, note: "Mandat administratif" },
    { clientId: "c2", clientName: "SARL Lagon Bleu", crecheSlug: "lagon-bleu", crecheName: "Lagon Bleu", paymentDays: 30, shiftDays: 0, paused: false, note: null },
  ],
  variables: ["bonjour", "factures", "total"],
};

const parc = {
  creches: [],
  summary: { total: 0, ok: 0, warn: 0, bad: 0, off: 0, evaluationsEnding: 0, renewals: 0, versionsBehind: 0, latestVersion: null, unpaid: 0 },
  sources: { supervisionAt: null, supervisionAttemptAt: null, supervisionError: null, hostAt: null, hostError: null, unlinkedSites: [], rejectedSites: [], hostDiskFree: null, hostDiskTotal: null },
};

const base = { "GET /auth/state": connected, "GET /parc": () => ({ body: parc }), "GET /envoi": () => ({ body: { messages: [], pending: 0 } }), "GET /relances": () => ({ body: settings }) };

describe("relances", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=jeton-csrf";
  });

  it("paramètres : règles, règle d'un client, jour férié, préparation à la demande", async () => {
    const calls = mockServer({
      ...base,
      "PUT /relances/clients/c2": () => ({ body: settings }),
      "POST /relances/feries": () => ({ status: 201, body: settings }),
      "POST /relances/preparer": () => ({ body: { messages: 2 } }),
    });
    const user = userEvent.setup();
    renderApp("/parametres");
    await user.click(await screen.findByRole("tab", { name: "Relances" }));
    const table = (await screen.findAllByRole("table"))[0];
    expect(within(table).getAllByRole("row").map((r) => r.textContent)).toEqual([
      "NiveauDélaiObjetCopie à moiActif",
      "Rappel courtoisJ-3Rappel courtois — facture {numero}nonnon",
      "Relance 1J+7Relance 1 — facture {numero}nonoui",
      "Relance 2J+15Relance 2 — facture {numero}ouioui",
      "Relance 3propose une suspensionJ+30Relance 3 — facture {numero}ouioui",
    ]);
    expect(screen.getByText("décalées de 15 j")).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText("Ajouter une règle pour un client"), "c2");
    await user.click(screen.getByLabelText("Relances suspendues"));
    await user.type(screen.getByLabelText("Motif"), "Litige en cours");
    await user.click(screen.getByRole("button", { name: "Enregistrer" }));
    await waitFor(() => expect(calls.some((c) => c.path === "/relances/clients/c2")).toBe(true));
    expect(calls.find((c) => c.path === "/relances/clients/c2")!.body).toEqual({ shiftDays: 0, paused: true, note: "Litige en cours" });

    await user.type(screen.getByLabelText("Jour"), "2099-11-11");
    await user.type(screen.getByLabelText("Nom"), "Armistice");
    await user.click(screen.getByRole("button", { name: "Ajouter" }));
    await waitFor(() => expect(calls.find((c) => c.path === "/relances/feries")?.body).toEqual({ day: "2099-11-11", label: "Armistice" }));

    await user.click(screen.getByRole("button", { name: "Préparer maintenant" }));
    expect(await screen.findByText("2 relances préparées dans la boîte d’envoi.")).toBeInTheDocument();
  });

  it("modèles : nouvelle version des règles", async () => {
    const calls = mockServer({ ...base, "POST /relances": () => ({ status: 201, body: settings }) });
    const user = userEvent.setup();
    renderApp("/parametres");
    await user.click(await screen.findByRole("tab", { name: "Modèles d’e-mails" }));
    expect(await screen.findByText("Relance 3 — facture {numero}")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Modifier (nouvelle version)…" }));
    await user.clear(screen.getByLabelText("Jours après l’échéance", { selector: "#r1-j" }));
    await user.type(screen.getByLabelText("Jours après l’échéance", { selector: "#r1-j" }), "10");
    await user.click(screen.getByRole("button", { name: "Enregistrer la version" }));
    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.path === "/relances")).toBe(true));
    const body = calls.find((c) => c.method === "POST" && c.path === "/relances")!.body as { rules: ReminderRule[] };
    expect(body.rules.map((r) => r.days)).toEqual([-3, 10, 15, 30]);
  });

  it("boîte d'envoi : relance groupée, deux pièces jointes, retirée si une facture a été soldée", async () => {
    const message: OutboxMessage = {
      id: "m1", kind: "RELANCE", invoiceId: "f1", invoiceNumber: "F2026-0001", invoiceTotal: 111_000, clientName: "SARL Lagon Bleu", crecheSlug: "lagon-bleu",
      crecheName: "Lagon Bleu", to: "compta@lagon-bleu.nc", cc: "facturation@ticagou.nc", subject: "Deuxième relance — facture F2026-0001, F2026-0002",
      body: "Bonjour,\n\n- facture F2026-0001…", attachmentName: "F2026-0001.pdf", status: "PREPARE", createdAt: "2026-11-09T00:00:00Z", sentAt: null, sentBy: null,
      lastError: null, discardedAt: null, discardReason: null, reminderLevel: 2, attachments: ["F2026-0001.pdf", "F2026-0002.pdf"],
      invoiceNumbers: ["F2026-0001", "F2026-0002"], invoiceIds: ["f1", "f2"],
    };
    mockServer({
      ...base,
      "GET /envoi": () => ({ body: { messages: [message], pending: 1 } }),
      "PUT /envoi/m1": () => ({ body: message }),
      "POST /envoi/envoyer": () => ({ body: [{ id: "m1", sent: false, error: "Soldée entre-temps : F2026-0001. Relance retirée ; les autres factures seront relancées à la prochaine préparation." }] }),
    });
    const user = userEvent.setup();
    renderApp("/envoi");
    const option = await screen.findByRole("option", { name: /Lagon Bleu/ });
    expect(within(option).getByText("Relance 2")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Deuxième relance" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "F2026-0002.pdf" })).toHaveAttribute("href", "/api/v1/factures/f2/pdf");
    await user.click(screen.getByRole("button", { name: "Envoyer" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Soldée entre-temps : F2026-0001");
  });

  it("tableau de bord : suspension à décider après la dernière relance", async () => {
    const late = {
      id: "f1", type: "FACTURE", number: "F2026-0044", status: "VALIDEE", kind: "PRESTATION", state: "RETARD", daysLate: 32, clientId: "c1",
      clientName: "Commune de Païta", crecheSlug: "paita", crecheName: "Crèche municipale de Païta", subject: "PC", periodFrom: null, periodTo: null,
      issuedOn: "2026-09-08", dueOn: "2026-10-08", totalHt: 10_378, totalTgc: 1_142, totalTtc: 11_520, paid: 0, credited: 0, remaining: 11_520,
      sentAt: "2026-09-08T00:00:00Z", remindersPaused: false, correctsId: null, correctsNumber: null,
    };
    mockServer({
      ...base,
      "GET /tableau": () => ({ body: { mrr: 0, arr: 0, outstanding: 11_520, overdue: 11_520, overdueCount: 1, dso: null, drafts: 0, renewalDrafts: 0, pendingMessages: 0, months: [], late: [], draftList: [], suspensions: [late] } }),
    });
    renderApp("/");
    const list = await screen.findByRole("heading", { name: "À faire aujourd’hui" });
    await waitFor(() => expect(within(list.closest("section")!).getByText(/dernière relance partie/)).toBeInTheDocument());
    expect(within(list.closest("section")!).getByRole("link", { name: "Décider" })).toHaveAttribute("href", "/parc/paita");
  });
});
