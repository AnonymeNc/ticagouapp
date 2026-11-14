import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { healthUp, installed, mockApi, renderAt } from "@/test/render";
import { currentCreche, resetCreches } from "@/lib/native/creches";
import { memoryVault, setVault } from "@/lib/native/vault";

// Application Ti’Cagou Pointage simulée (sans plugin natif).
vi.mock("@/lib/native/platform", async (original) => {
  const actual = await original<typeof import("@/lib/native/platform")>();
  return { ...actual, CIBLE: "pointage", isNativeApp: () => true, hasNativePlugins: () => false, nativePlatform: () => "android",
    appHeaders: () => ({ "X-Ticagou-App": "pointage/1.0.0 (android)" }) };
});

function memoryPrefs() {
  const values = new Map<string, string>();
  return { get: async (k: string) => values.get(k) ?? null, set: async (k: string, v: string) => void values.set(k, v) };
}

beforeEach(() => {
  setVault(memoryVault());
  resetCreches(memoryPrefs());
});

afterEach(() => setVault(null));

describe("appairage dans l’application (maquette P1)", () => {
  it("à la main : l’adresse de la crèche puis le code ; la tablette parle ensuite à cette crèche", async () => {
    const fetch = vi.spyOn(globalThis, "fetch");
    mockApi({
      "GET /setup/status": { body: installed },
      "GET /health": { body: healthUp },
      "POST /devices/pair": { body: { deviceId: "d-1", secret: "secret", name: "Accueil", structureName: "Les P’tits Niaoulis" } },
      "POST /devices/session": { body: { accessToken: "device-1", expiresIn: 900, deviceId: "d-1", name: "Accueil", structureId: "s-1", structureName: "Les P’tits Niaoulis" } },
    });
    const user = userEvent.setup();
    renderAt("/kiosk");
    expect(await screen.findByRole("heading", { name: "Appairer cette tablette" })).toBeInTheDocument();

    const pad = screen.getByRole("group", { name: "Code d’appairage" });
    for (const d of "482915") await user.click(within(pad).getByRole("button", { name: d }));
    expect(await screen.findByText(/Tapez d’abord l’adresse de la crèche/)).toBeInTheDocument();

    await user.type(screen.getByLabelText("Adresse de la crèche"), "ptits-niaoulis.ticagou.nc");
    for (const d of "482915") await user.click(within(pad).getByRole("button", { name: d }));
    await waitFor(() => expect(currentCreche()?.nom).toBe("Les P’tits Niaoulis"));
    const pair = fetch.mock.calls.find(([url]) => String(url).endsWith("/devices/pair"));
    expect(String(pair?.[0])).toBe("https://ptits-niaoulis.ticagou.nc/api/v1/devices/pair");
    expect(currentCreche()?.adresse).toBe("https://ptits-niaoulis.ticagou.nc");
  });
});
