import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { director, healthUp, installed, mockApi, renderAt, session, structure } from "@/test/render";
import { periodBounds } from "./api";

function server(check: object) {
  window.localStorage.setItem("ticagou.refreshToken", "refresh-1");
  return mockApi({
    "GET /setup/status": { body: installed },
    "GET /health": { body: healthUp },
    "POST /auth/refresh": { body: session(director) },
    "GET /structure": { body: structure },
    "GET /sections": { body: [] },
    "GET /accounting/check": { body: check },
  });
}

const balanced = {
  from: "2026-10-01", to: "2026-10-31", debit: 1_520_000, credit: 1_520_000, anomalies: [],
  journals: [
    { journal: "VE", label: "Ventes", pieces: 21, debit: 1_200_000, credit: 1_200_000 },
    { journal: "BQ", label: "Banque", pieces: 15, debit: 320_000, credit: 320_000 },
  ],
};

describe("export comptable", () => {
  it("borne un mois ou une année civile", () => {
    expect(periodBounds("2026-02")).toEqual({ from: "2026-02-01", to: "2026-02-28" });
    expect(periodBounds("2024-02")).toEqual({ from: "2024-02-01", to: "2024-02-29" });
    expect(periodBounds("2026")).toEqual({ from: "2026-01-01", to: "2026-12-31" });
  });

  it("montre les totaux par journal et permet l’export d’écritures équilibrées", async () => {
    server(balanced);
    renderAt("/facturation/comptabilite");
    expect(await screen.findByText("Écritures équilibrées, tous les comptes sont renseignés.")).toBeInTheDocument();
    expect(screen.getByText("Ventes")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Exporter en CSV" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Fichier des écritures (FEC)" })).toBeEnabled();
  });

  it("bloque l’export tant qu’un compte manque", async () => {
    const api = server({ ...balanced, anomalies: ["Aucun compte « TGC collectée » en vigueur le 03/11/2026 (Paramètres › Comptabilité)."] });
    renderAt("/facturation/comptabilite");
    expect(await screen.findByRole("alert")).toHaveTextContent("TGC collectée");
    expect(screen.getByRole("button", { name: "Exporter en CSV" })).toBeDisabled();
    const user = userEvent.setup();
    await user.selectOptions(screen.getByLabelText(/Période/), "2026");
    await waitFor(() => expect(api.calls.some((c) => c.key === "GET /accounting/check")).toBe(true));
  });
});
