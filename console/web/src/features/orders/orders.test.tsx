import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { connected, mockServer, renderApp } from "@/test/mockServer";
import type { Row } from "@/features/parc/api";
import type { Order, OrdersBoard } from "./api";

const row = (o: Partial<Row>): Row => ({
  slug: "niaoulis", name: "Les Niaoulis", city: null, clientName: "Les Niaoulis", lifecycle: "CLIENT", health: { level: "OK", reasons: [] }, lastContactAt: null,
  version: "1.4.2", plan: "Gestion", modules: [], places: 24, activeChildren: 20, licenseState: "ACTIVE", validUntil: "2027-08-31", deadline: null, desktops: 1,
  desktopLimit: 1, tablets: 2, tabletLimit: 2, backupAt: null, backupStale: false, diskUsedPercent: 40, container: "healthy", alerts: [], lastCallAt: null,
  balanceDue: 0, daysLate: 0, ...o,
});

const parc = {
  creches: [row({}), row({ slug: "tiare", name: "Crèche Tiare", version: "1.5.0" }), row({ slug: "kids", name: "Kids Club Bourail", lifecycle: "PROSPECT", container: null })],
  summary: { total: 3, ok: 3, warn: 0, bad: 0, off: 0, evaluationsEnding: 0, renewals: 0, versionsBehind: 1, latestVersion: "1.5.0", unpaid: 0 },
  sources: { supervisionAt: null, supervisionAttemptAt: null, supervisionError: null, hostAt: null, hostError: null, unlinkedSites: [], rejectedSites: [], hostDiskFree: null, hostDiskTotal: null },
};

const order = (o: Partial<Order>): Order => ({
  id: "o1", type: "ABONNEMENT", slug: "niaoulis", crecheName: "Les Niaoulis", params: { numero: "C-1A2B", offre: "gestion", places: "24", pc: "1", tablettes: "2", jusqu_au: "2027-08-31", modules: "REPAS" },
  status: "A_CONFIRMER", reason: "Après paiement de F2026-0041", proposedBy: "console", confirmedBy: null, createdAt: "2026-11-09T06:00:00Z", confirmedAt: null,
  startedAt: null, finishedAt: null, output: null, result: null, stale: false, ...o,
});

const board: OrdersBoard = {
  orders: [
    order({}),
    order({ id: "o2", type: "CREER", slug: "tiare", crecheName: "Crèche Tiare", params: {}, status: "FAIT", reason: null, output: "Crèche créée.\n  https://tiare.ticagou.nc/setup?jeton=abc123", result: { setupLink: "https://tiare.ticagou.nc/setup?jeton=abc123" } }),
  ],
  misaligned: [{ slug: "niaoulis", name: "Les Niaoulis", differences: ["Échéance : 31/08/2026 dans l’instance, 31/08/2027 payée au contrat"] }],
  images: ["1.5.0", "1.4.2"],
  hostAt: null,
};

const base = { "GET /auth/state": connected, "GET /parc": () => ({ body: parc }), "GET /envoi": () => ({ body: { messages: [], pending: 0 } }), "GET /ordres": () => ({ body: board }) };

describe("ordres aux instances", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=jeton-csrf";
  });

  it("confirmer une proposition redemande le code ; lien de démarrage et sortie d'un ordre fait", async () => {
    let confirmed = false;
    const calls = mockServer({
      ...base,
      "POST /ordres/o1/confirmer": () => (confirmed ? { body: order({ status: "EN_ATTENTE" }) } : { status: 403, body: { code: "confirmation_required", detail: "Code" } }),
      "POST /auth/confirm": () => {
        confirmed = true;
        return { status: 204 };
      },
    });
    const user = userEvent.setup();
    renderApp("/ordres");
    const table = await screen.findByRole("table");
    const rows = within(table).getAllByRole("row");
    expect(rows[1]).toHaveTextContent("Gestion, 24 places, 1 PC, 2 tablettes, jusqu’au 31/08/2027, + repas");
    expect(rows[1]).toHaveTextContent("À confirmer");
    expect(rows[2].textContent).toContain("Lien de démarrage :https://tiare.ticagou.nc/setup?jeton=abc123");
    expect(screen.getByText(/Abonnement non aligné sur le contrat/)).toBeInTheDocument();

    await user.click(within(rows[2]).getByRole("button", { name: "Voir la sortie" }));
    expect(screen.getByText(/Crèche créée\./)).toBeInTheDocument();

    await user.click(within(rows[1]).getByRole("button", { name: "Confirmer (code)" }));
    const dialog = await screen.findByRole("dialog");
    await user.type(within(dialog).getByLabelText("Code à 6 chiffres"), "123456");
    await user.click(within(dialog).getByRole("button", { name: "Confirmer" }));
    await waitFor(() => expect(calls.filter((c) => c.path === "/ordres/o1/confirmer")).toHaveLength(2));
  });

  it("nouvelle crèche et démonstration depuis la console", async () => {
    const calls = mockServer({ ...base, "POST /ordres": (call) => ({ status: 201, body: order({ id: "o9", status: "EN_ATTENTE", type: (call.body as { type: Order["type"] }).type }) }) });
    const user = userEvent.setup();
    renderApp("/ordres");
    await user.click(await screen.findByRole("button", { name: "Nouvelle crèche" }));
    await user.click(screen.getByRole("button", { name: "Kids Club Bourail" }));
    expect(screen.getByLabelText("Identifiant")).toHaveValue("kids");
    await user.click(screen.getByRole("button", { name: "Créer l’instance" }));
    await waitFor(() => expect(calls.find((c) => c.method === "POST" && c.path === "/ordres")?.body).toEqual({ type: "CREER", slug: "kids", params: { nom: "Kids Club Bourail" } }));

    await user.click(screen.getByRole("button", { name: "Démonstration…" }));
    await user.selectOptions(screen.getByLabelText("Formule montrée"), "Gestion");
    await user.click(screen.getByRole("button", { name: "Lancer la démonstration" }));
    await waitFor(() => expect(calls.filter((c) => c.method === "POST" && c.path === "/ordres")[1]?.body).toEqual({ type: "DEMO", params: { formule: "Gestion" } }));
  });

  it("mise à jour : la témoin d'abord", async () => {
    const calls = mockServer({ ...base, "POST /ordres": () => ({ status: 201, body: order({ type: "METTRE_A_JOUR", status: "EN_ATTENTE" }) }) });
    const user = userEvent.setup();
    renderApp("/ordres");
    await user.click(await screen.findByRole("button", { name: "Mise à jour…" }));
    expect(within(screen.getByLabelText("Crèche témoin")).getAllByRole("option").map((o) => o.textContent)).toEqual(["Choisir…", "Les Niaoulis (1.4.2)"]);
    await user.selectOptions(screen.getByLabelText("Crèche témoin"), "niaoulis");
    await user.click(screen.getByRole("button", { name: "Mettre à jour la témoin" }));
    await waitFor(() => expect(calls.find((c) => c.method === "POST")?.body).toEqual({ type: "METTRE_A_JOUR", slug: "niaoulis", params: { version: "1.5.0" } }));
  });

  it("tableau de bord : ordre à confirmer et abonnement non aligné", async () => {
    mockServer({ ...base, "GET /tableau": () => ({ body: { mrr: 0, arr: 0, outstanding: 0, overdue: 0, overdueCount: 0, dso: null, drafts: 0, renewalDrafts: 0, pendingMessages: 0, months: [], late: [], draftList: [], suspensions: [] } }) });
    renderApp("/");
    const list = await screen.findByRole("heading", { name: "À faire aujourd’hui" });
    await waitFor(() => expect(within(list.closest("section")!).getByText(/1 ordre à confirmer/)).toBeInTheDocument());
    expect(within(list.closest("section")!).getByText(/non aligné sur le contrat/)).toBeInTheDocument();
  });
});
