import { screen, within } from "@testing-library/react";
import { director, healthUp, installed, mockApi, renderAt, session, structure } from "@/test/render";
import { formatHours, formatPerMille, periodQuery } from "./api";

const indicators = { children: 21, realizedMinutes: 180_000, plannedMinutes: 192_000, capacityMinutes: 253_440, occupancy: 710, realization: 938 };
const report = {
  label: "octobre 2026", from: "2026-10-01", to: "2026-10-31", structureName: "Les P’tits Niaoulis", capacity: 48, openDays: 22,
  enrolled: 24, total: indicators,
  sections: [{ sectionId: "s-1", name: "Bébés", capacity: 12, indicators: { ...indicators, children: 7, occupancy: 655 } }],
  months: [], absences: [{ reason: "Maladie", deductible: true, days: 6 }],
  funders: [{ name: "Province Sud — aide à la garde", category: "PROVINCE", children: 4, amount: 74_400 }],
  money: { invoices: 18, invoiced: 1_500_000, tgc: 0, aids: 120_000, families: 1_380_000, credited: 2_500, remaining: 260_000 },
};

describe("statistiques", () => {
  it("formate heures, taux et période", () => {
    expect(formatHours(90)).toBe("2 h");
    expect(formatPerMille(712)).toBe("71,2 %");
    expect(periodQuery("2026-03")).toBe("year=2026&month=3");
    expect(periodQuery("2026")).toBe("year=2026");
  });

  it("montre les indicateurs du mois, les aides et la facturation", async () => {
    window.localStorage.setItem("ticagou.refreshToken", "refresh-1");
    mockApi({
      "GET /setup/status": { body: installed },
      "GET /health": { body: healthUp },
      "POST /auth/refresh": { body: session(director) },
      "GET /structure": { body: structure },
      "GET /sections": { body: [] },
      "GET /reporting": { body: report },
    });
    renderAt("/statistiques");
    const occupancy = await screen.findByRole("region", { name: "Taux d’occupation" });
    expect(within(occupancy).getByText("71,0 %")).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Heures réalisées" })).toHaveTextContent("3 000 h");
    expect(screen.getByText("Province Sud — aide à la garde")).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Facturation des mois de la période" })).toHaveTextContent("Reste dû aujourd’hui");
    expect(screen.getByRole("link", { name: /Statistiques/ })).toBeInTheDocument();
  });
});
