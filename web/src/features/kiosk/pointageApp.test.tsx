import { render, screen, waitFor, within, fireEvent, act } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { mockApi } from "@/test/render";
import { resetCreches } from "@/lib/native/creches";
import { memoryVault, setVault } from "@/lib/native/vault";
import { parsePairingQr } from "./PairingPage";
import { KioskModeControl } from "./KioskModeControl";

// Application Ti’Cagou Pointage simulée (sans plugin natif).
const native = vi.hoisted(() => ({ app: true }));
vi.mock("@/lib/native/platform", async (original) => {
  const actual = await original<typeof import("@/lib/native/platform")>();
  return { ...actual, CIBLE: "pointage", isNativeApp: () => native.app, hasNativePlugins: () => false, nativePlatform: () => "android",
    appHeaders: () => ({ "X-Ticagou-App": "pointage/1.0.0 (android)" }) };
});

const kioskMode = vi.hoisted(() => ({ state: { enabled: false, locked: false, managed: false }, enable: vi.fn(), disable: vi.fn() }));
vi.mock("@/lib/native/kiosk", () => ({
  kioskModeAvailable: () => true,
  kioskStatus: async () => kioskMode.state,
  enableKioskMode: async () => {
    kioskMode.enable();
    return { ...kioskMode.state, enabled: true, locked: true };
  },
  disableKioskMode: async () => {
    kioskMode.disable();
    return { ...kioskMode.state, enabled: false, locked: false };
  },
}));

const kiosk = vi.hoisted(() => ({ online: true }));
vi.mock("./KioskContext", async (original) => {
  const actual = await original<typeof import("./KioskContext")>();
  return {
    ...actual,
    useKiosk: () => ({ device: { deviceId: "d-1" }, online: kiosk.online, today: { directors: [{ userId: "u-dir", displayName: "Nathalie K." }] } }),
  };
});

function memoryPrefs() {
  const values = new Map<string, string>();
  return { get: async (k: string) => values.get(k) ?? null, set: async (k: string, v: string) => void values.set(k, v) };
}

beforeEach(() => {
  native.app = true;
  kiosk.online = true;
  setVault(memoryVault());
  resetCreches(memoryPrefs());
  kioskMode.state = { enabled: false, locked: false, managed: false };
  kioskMode.enable.mockClear();
  kioskMode.disable.mockClear();
});

afterEach(() => setVault(null));

describe("QR d’appairage (maquette P2)", () => {
  it("donne l’adresse de la crèche et le code", () => {
    expect(parsePairingQr("https://ptits-niaoulis.ticagou.nc/kiosk?pair=482915")).toEqual({ adresse: "https://ptits-niaoulis.ticagou.nc", code: "482915" });
    expect(parsePairingQr("https://ptits-niaoulis.ticagou.nc/app")).toBeNull();
    expect(parsePairingQr("http://pirate.example/kiosk?pair=482915")).toBeNull();
    expect(parsePairingQr("482915")).toBeNull();
  });
});

describe("mode kiosque (maquettes P3 à P5)", () => {
  it("s’active depuis la barre de la tablette", async () => {
    render(<KioskModeControl />);
    await userEvent.setup().click(await screen.findByRole("button", { name: "Activer le mode kiosque" }));
    expect(kioskMode.enable).toHaveBeenCalled();
    expect(await screen.findByRole("button", { name: /Mode kiosque actif/ })).toBeInTheDocument();
  });

  it("ne se quitte qu’avec le code d’une personne de la direction, vérifié par le serveur", async () => {
    kioskMode.state = { enabled: true, locked: true, managed: false };
    const { calls } = mockApi({
      "POST /auth/pin": (body) => ((body as { pin: string }).pin === "2580"
        ? { body: { accessToken: "a", expiresIn: 900, refreshToken: null, user: {} } }
        : { status: 401, body: { code: "bad_pin", detail: "Code PIN incorrect." } }),
    });
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      render(<KioskModeControl />);
      const pill = await screen.findByRole("button", { name: /Mode kiosque actif/ });
      fireEvent.pointerDown(pill);
      act(() => vi.advanceTimersByTime(5_100));
      const dialog = await screen.findByRole("dialog", { name: "Sortir du mode kiosque" });
      const pad = within(dialog).getByRole("group", { name: /Code personnel de Nathalie K./ });
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
      for (const d of "1111") await user.click(within(pad).getByRole("button", { name: d }));
      expect(await within(dialog).findByText("Code PIN incorrect.")).toBeInTheDocument();
      expect(kioskMode.disable).not.toHaveBeenCalled();
      for (const d of "2580") await user.click(within(pad).getByRole("button", { name: d }));
      await waitFor(() => expect(kioskMode.disable).toHaveBeenCalled());
      expect(calls.filter((c) => c.key === "POST /auth/pin").map((c) => c.body)).toEqual([
        { userId: "u-dir", pin: "1111" }, { userId: "u-dir", pin: "2580" },
      ]);
    } finally {
      vi.useRealTimers();
    }
  });
});

