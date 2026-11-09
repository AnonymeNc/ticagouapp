import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { director, healthUp, installed, mockApi, renderAt, session, structure } from "@/test/render";

function server(extra: Parameters<typeof mockApi>[0] = {}) {
  window.localStorage.setItem("ticagou.refreshToken", "refresh-1");
  return mockApi({
    "GET /setup/status": { body: installed },
    "GET /health": { body: healthUp },
    "POST /auth/refresh": { body: session(director) },
    "GET /structure": { body: structure },
    ...extra,
  });
}

const creche = (over: Record<string, unknown>) => ({
  name: "Les Cagous de Dumbéa", url: "https://cagous.ticagou.nc", activeChildren: 30, places: 36, occupancyPercent: 81,
  invoicedLastMonth: 1250000, outstanding: 85000, ratioBreaches7d: 2, asOf: "2026-11-10", self: false, lastSeenAt: "2026-11-10T01:00:00Z", ...over,
});

describe("vue groupe", () => {
  it("rejoint un groupe avec le consentement de la direction", async () => {
    const { calls } = server({
      "GET /monitoring/group": { body: { joined: false, joinedAt: null, joinedBy: null, creches: [], error: null } },
      "POST /monitoring/group": { body: { joined: true, joinedAt: "2026-11-10T01:00:00Z", joinedBy: "Nathalie Kaloï",
        creches: [creche({}), creche({ name: "Les P’tits Niaoulis", self: true, outstanding: 0, ratioBreaches7d: 0 })], error: null } },
    });
    renderAt("/groupe");
    await userEvent.type(await screen.findByLabelText("Code du groupe"), "groupe-niaoulis-2026");
    const join = screen.getByRole("button", { name: "Rejoindre le groupe" });
    expect(join).toBeDisabled();
    await userEvent.click(screen.getByRole("checkbox", { name: /j’accepte de partager ces chiffres/ }));
    await userEvent.click(join);
    expect(await screen.findByRole("link", { name: /Les Cagous de Dumbéa/ })).toHaveAttribute("href", "https://cagous.ticagou.nc");
    expect(screen.getAllByText("81 %")).toHaveLength(2);
    expect(screen.getByText("(cette crèche)")).toBeInTheDocument();
    await waitFor(() => expect(calls.find((c) => c.key === "POST /monitoring/group")?.body).toMatchObject({ code: "groupe-niaoulis-2026" }));
  });
});
