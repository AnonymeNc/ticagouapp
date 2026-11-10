import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { mrrHint } from "@/features/dashboard/DashboardPage";
import { connected, mockServer, renderApp } from "@/test/mockServer";
import { exportHref, presetRange, type Report } from "./api";

const report: Report = {
  filter: { from: "2026-01-01", to: "2026-11-09", clientId: null },
  kpis: { revenueHt: 1_846_000, subscriptionsHt: 1_513_720, servicesHt: 332_280, collected: 1_638_250, tgc: 203_060, rates: [110], mrr: 171_000,
    departures: 0, churnTenths: 0 },
  months: [{ month: "2026-10", ht: 200_000, tgc: 22_000, ttc: 222_000, collected: 150_000 }, { month: "2026-11", ht: 100_000, tgc: 11_000, ttc: 111_000, collected: 0 }],
  tgc: [{ rate: 110, base: 1_846_000, tgc: 203_060 }],
  aging: [
    { clientId: "c1", client: "Lagon Bleu", notDue: 0, d30: 174_960, d60: 0, d90: 0, over: 0, total: 174_960 },
    { clientId: "c2", client: "Kids Club Bourail", notDue: 66_600, d30: 0, d60: 0, d90: 0, over: 0, total: 66_600 },
  ],
  agingTotal: { clientId: null, client: "Total", notDue: 66_600, d30: 174_960, d60: 0, d90: 0, over: 0, total: 241_560 },
  mrrByPlan: [{ key: "FORMULE", label: "Formule Ti’Cagou", monthly: 89_500 }, { key: "OPTIONS", label: "PC, tablettes et support", monthly: 9_000 }],
  upsell: [{ slug: "paita", name: "Païta", text: "3 PC utilisés pour 2 au contrat", monthly: 1_500 }],
  risks: [{ slug: "bancouliers", name: "Les Petits Bancouliers", kind: "CONNEXION", text: "aucune connexion direction depuis 19 jours" }],
  profitability: [{ slug: "cagous", name: "Les P’tits Cagous", revenue: 9_500, supportMinutes: 90, cost: 14_700, margin: -5_200 }],
  profitMonths: 11,
  settings: { supportHourlyRate: 9_000, hostingPerCreche: 1_200 },
  clients: [{ id: "c1", name: "Lagon Bleu" }, { id: "c2", name: "Kids Club Bourail" }],
};

describe("reporting", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=jeton-csrf";
  });

  it("indicateurs, balance âgée, risques, rentabilité ; filtres et exports suivent la période", async () => {
    const calls = mockServer({ "GET /auth/state": connected, "GET /reporting": () => ({ body: report }),
      "PUT /reporting/reglages": () => ({ body: { supportHourlyRate: 10_000, hostingPerCreche: 1_200 } }) });
    const user = userEvent.setup();
    renderApp("/reporting");
    expect(await screen.findByText("TGC collectée")).toBeInTheDocument();
    expect(screen.getByText("abonnements 82 % · prestations 18 %")).toBeInTheDocument();
    expect(screen.getByText("à déclarer · 11 %")).toBeInTheDocument();
    expect(screen.getByText("0 résiliation sur 12 mois")).toBeInTheDocument();

    const aging = screen.getByRole("heading", { name: "Balance âgée" }).closest("section")!;
    const rows = within(aging).getAllByRole("row");
    expect(rows[1]).toHaveTextContent(/Lagon Bleu—174\s960———/);
    expect(rows[3]).toHaveTextContent(/Total66\s600174\s960000/);
    expect(screen.getByText(/Vente additionnelle possible/)).toHaveTextContent(/Païta — 3 PC utilisés pour 2 au contrat \(\+ 1\s500 F\/mois\)\./);
    expect(screen.getByText(/aucune connexion direction depuis 19 jours/)).toBeInTheDocument();
    const profit = screen.getByRole("heading", { name: "Rentabilité par crèche" }).closest("section")!;
    expect(within(profit).getAllByRole("row")[1]).toHaveTextContent(/Les P’tits Cagous9\s500 F1,5 h− 5\s200 F/);

    const excel = screen.getByRole("link", { name: "Excel" });
    expect(excel.getAttribute("href")).toMatch(/^\/api\/v1\/reporting\/reporting\.xlsx\?du=\d{4}-01-01&au=/);
    await user.selectOptions(screen.getByLabelText("Client"), "c1");
    expect(screen.queryByLabelText("Formule")).not.toBeInTheDocument();
    await waitFor(() => expect(calls.at(-1)?.path).toMatch(/client=c1$/));
    expect(screen.getByRole("link", { name: "Journal des ventes" }).getAttribute("href")).toMatch(/journal-ventes\.csv\?.*client=c1$/);

    await user.click(within(profit).getByRole("button", { name: "Modifier" }));
    await user.clear(screen.getByLabelText("Heure de support (F)"));
    await user.type(screen.getByLabelText("Heure de support (F)"), "10000");
    await user.click(screen.getByRole("button", { name: "Enregistrer" }));
    await waitFor(() => expect(calls.find((c) => c.method === "PUT")?.body).toEqual({ supportHourlyRate: 10_000, hostingPerCreche: 1_200 }));
  });

  it("périodes : exercice, 12 derniers mois, trimestre ; liens d'export", () => {
    const today = new Date("2026-11-09T02:00:00Z");
    expect(presetRange("exercice", today)).toEqual({ from: "2026-01-01", to: "2026-11-09" });
    expect(presetRange("douze", today)).toEqual({ from: "2025-12-01", to: "2026-11-09" });
    expect(presetRange("trimestre", today)).toEqual({ from: "2026-10-01", to: "2026-11-09" });
    expect(exportHref("encaissements.csv", { from: "2026-01-01", to: "2026-03-31", clientId: "" })).toBe(
      "/api/v1/reporting/encaissements.csv?du=2026-01-01&au=2026-03-31",
    );
  });

  it("tableau de bord : variation du revenu récurrent depuis le mois précédent", () => {
    const now = new Date("2026-11-09T02:00:00Z");
    expect(mrrHint({ mrr: 171_000, mrrPrevious: 157_500, arr: 2_052_000 }, now)).toMatch(/^\+ 13\s500 F depuis octobre · ARR 2\s052\s000 F$/);
    expect(mrrHint({ mrr: 150_000, mrrPrevious: 157_500, arr: 1_800_000 }, now)).toMatch(/^− 7\s500 F depuis octobre/);
    expect(mrrHint({ mrr: 171_000, mrrPrevious: 171_000, arr: 2_052_000 }, now)).toMatch(/^ARR 2\s052\s000 F · contrats en cours, HT$/);
  });
});
