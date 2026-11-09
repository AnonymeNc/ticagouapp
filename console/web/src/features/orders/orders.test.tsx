import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ConfirmProvider } from "@/features/auth/Confirm";
import { connected, mockServer, renderApp } from "@/test/mockServer";
import { DeleteCreche } from "./DeleteCreche";
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

describe("supprimer une crèche depuis la console", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=jeton-csrf";
    URL.createObjectURL = vi.fn(() => "blob:export");
    URL.revokeObjectURL = vi.fn();
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
  });

  it("deux mises en garde, export téléchargé tout seul, puis suppression transmise", async () => {
    let exported = false;
    const done = order({ id: "e1", type: "EXPORTER", params: { suite: "supprimer" }, status: "FAIT", reason: "Suppression de la crèche",
      result: { exportFile: "niaoulis-20261109-1030.tar.gz.enc", size: 6 } });
    const calls = mockServer({
      "GET /parc/niaoulis/ordres": () => ({ body: exported ? [done] : [] }),
      "POST /ordres": () => {
        exported = true;
        return { status: 201, body: { ...done, status: "EN_ATTENTE", result: null } };
      },
      "GET /ordres/e1/export": () => ({ body: "abcd" }),
      "POST /ordres/e1/export/recu": () => ({ body: { ...done, result: { ...done.result, receivedAt: "2026-11-09T00:00:00Z", deletionOrder: "s1" } } }),
    });
    const user = userEvent.setup();
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <ConfirmProvider>
          <DeleteCreche row={row({ balanceDue: 45_000, activeChildren: 20 })} />
        </ConfirmProvider>
      </QueryClientProvider>,
    );
    await user.click(screen.getByRole("button", { name: "Supprimer la crèche…" }));
    const dialog = screen.getByRole("dialog");
    expect(dialog).toHaveTextContent("Supprimer Les Niaoulis ?");
    expect(dialog).toHaveTextContent(/Solde dû : 45\s000/);
    expect(dialog).toHaveTextContent("20 enfants encore inscrits");
    await user.click(within(dialog).getByRole("button", { name: "Continuer" }));

    const submit = screen.getByRole("button", { name: "Exporter puis supprimer" });
    await user.type(screen.getByLabelText("Identifiant de la crèche"), "niaouli");
    expect(submit).toBeDisabled();
    await user.type(screen.getByLabelText("Identifiant de la crèche"), "s");
    await user.click(submit);
    expect(calls.find((c) => c.method === "POST" && c.path === "/ordres")?.body).toEqual({
      type: "EXPORTER", slug: "niaoulis", params: { suite: "supprimer", confirmation: "niaoulis" }, reason: "Suppression de la crèche",
    });

    expect(await screen.findByRole("status")).toHaveTextContent("Export enregistré sur cet ordinateur : niaoulis-20261109-1030.tar.gz.enc");
    const paths = calls.map((c) => `${c.method} ${c.path}`);
    expect(paths.indexOf("GET /ordres/e1/export")).toBeLessThan(paths.indexOf("POST /ordres/e1/export/recu"));
    expect(paths.filter((p) => p === "POST /ordres/e1/export/recu")).toHaveLength(1);
  });

  it("export incomplet : rien n'est signalé reçu, donc rien n'est supprimé", async () => {
    const done = order({ id: "e1", type: "EXPORTER", params: { suite: "supprimer" }, status: "FAIT", result: { exportFile: "niaoulis-20261109-1030.tar.gz.enc", size: 999 } });
    const calls = mockServer({ ...base, "GET /ordres/e1/export": () => ({ body: "abcd" }), "GET /ordres": () => ({ body: { ...board, orders: [done] } }) });
    const user = userEvent.setup();
    renderApp("/ordres");
    await user.click(await screen.findByRole("button", { name: "Télécharger puis supprimer" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Export incomplet");
    expect(calls.some((c) => c.path === "/ordres/e1/export/recu")).toBe(false);
  });
});
