import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { healthUp, installed, mockApi, renderAt, session, structure } from "@/test/render";
import { normalizeCode, parseQr } from "@/lib/native/annuaire";
import { chooseCreche, currentCreche, listCreches, resetCreches } from "@/lib/native/creches";
import { loadVaultSession } from "@/features/auth/tokens";
import { memoryVault, setVault, vault } from "@/lib/native/vault";
import { restartApp } from "@/lib/native/restart";

// Application Direction simulée (Android), sans plugin natif.
const native = vi.hoisted(() => ({ app: true, version: "1.2.0" }));
vi.mock("@/lib/native/platform", async (original) => {
  const actual = await original<typeof import("@/lib/native/platform")>();
  return {
    ...actual,
    CIBLE: "direction",
    get APP_VERSION() {
      return native.version;
    },
    isNativeApp: () => native.app,
    hasNativePlugins: () => false,
    nativePlatform: () => "android",
    appHeaders: () => ({ "X-Ticagou-App": `direction/${native.version} (android)` }),
  };
});
vi.mock("@/lib/native/restart", () => ({ restartApp: vi.fn() }));

function memoryPrefs() {
  const values = new Map<string, string>();
  return { get: async (k: string) => values.get(k) ?? null, set: async (k: string, v: string) => void values.set(k, v) };
}

const niaoulis = { nom: "Les P’tits Niaoulis", commune: "Dumbéa", adresse: "https://ptits-niaoulis.ticagou.nc", code: "NK7Q2M" };
const compat = { api: 1, minimum: { direction: "0.0.0", pointage: "0.0.0" }, creche: "Les P’tits Niaoulis", code: "NK7Q2M" };

beforeEach(() => {
  native.app = true;
  native.version = "1.2.0";
  setVault(memoryVault());
  resetCreches(memoryPrefs());
  vi.mocked(restartApp).mockClear();
});

afterEach(() => setVault(null));

describe("codes et QR de crèche", () => {
  it("lit un code tapé avec espaces ou minuscules, refuse les caractères ambigus", () => {
    expect(normalizeCode(" nk7 q2m ")).toBe("NK7Q2M");
    expect(normalizeCode("NK7-Q2M")).toBe("NK7Q2M");
    expect(normalizeCode("NK7Q2O")).toBeNull();
    expect(normalizeCode("NK7Q2")).toBeNull();
  });

  it("lit le QR « Ouvrir sur mon téléphone » ou un code", () => {
    expect(parseQr("https://ptits-niaoulis.ticagou.nc/app")).toEqual({ adresse: "https://ptits-niaoulis.ticagou.nc" });
    expect(parseQr("NK7Q2M")).toEqual({ code: "NK7Q2M" });
    expect(parseQr("http://pirate.example/app")).toBeNull();
    expect(parseQr("bonjour")).toBeNull();
  });
});

describe("choix de la crèche (maquettes A1 à A3)", () => {
  it("sans crèche, l’application demande d’abord laquelle", async () => {
    mockApi({});
    renderAt("/");
    expect(await screen.findByRole("heading", { name: "Bienvenue" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Taper le code de la crèche/ })).toBeInTheDocument();
    expect(screen.getByText(/Ti’Cagou Parents/)).toBeInTheDocument();
  });

  it("trouve la crèche par son code, la fait confirmer, puis ouvre la connexion", async () => {
    const { calls } = mockApi({ "GET /v1/NK7Q2M.json": { body: { nom: "P’tits Niaoulis", commune: "Dumbéa", adresse: "https://ptits-niaoulis.ticagou.nc" } }, "GET /app/compat": { body: compat } });
    const user = userEvent.setup();
    renderAt("/creche");

    await user.click(await screen.findByRole("button", { name: /Taper le code/ }));
    await user.type(screen.getByRole("textbox"), "nk7q2o");
    await user.click(screen.getByRole("button", { name: "Rechercher" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("sans 0, O, 1, I ni L");

    await user.clear(screen.getByRole("textbox"));
    await user.type(screen.getByRole("textbox"), "nk7q2m");
    await user.click(screen.getByRole("button", { name: "Rechercher" }));

    // Le nom affiché est celui que donne l’instance, pas l’annuaire.
    expect(await screen.findByText("Les P’tits Niaoulis")).toBeInTheDocument();
    expect(screen.getByText(/Dumbéa · ptits-niaoulis.ticagou.nc/)).toBeInTheDocument();
    expect(calls.map((c) => c.key)).toEqual(["GET /v1/NK7Q2M.json", "GET /app/compat"]);

    await user.click(screen.getByRole("button", { name: "Continuer" }));
    await waitFor(() => expect(restartApp).toHaveBeenCalledWith("/login"));
    expect(currentCreche()).toMatchObject({ nom: "Les P’tits Niaoulis", adresse: "https://ptits-niaoulis.ticagou.nc", code: "NK7Q2M" });
  });

  it("dit clairement qu’un code est inconnu", async () => {
    mockApi({});
    const user = userEvent.setup();
    renderAt("/creche");
    await user.click(await screen.findByRole("button", { name: /Taper le code/ }));
    await user.type(screen.getByRole("textbox"), "ZZZZZZ");
    await user.click(screen.getByRole("button", { name: "Rechercher" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("aucune crèche");
    expect(listCreches()).toHaveLength(0);
  });

  it("par l’adresse : https obligatoire, et l’instance doit être une crèche Ti’Cagou", async () => {
    mockApi({});
    const user = userEvent.setup();
    renderAt("/creche");
    await user.click(await screen.findByRole("button", { name: /Autre adresse/ }));
    await user.type(screen.getByRole("textbox"), "http://ma-creche.nc");
    await user.click(screen.getByRole("button", { name: "Rechercher" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("https://");

    await user.clear(screen.getByRole("textbox"));
    await user.type(screen.getByRole("textbox"), "ma-creche.nc");
    await user.click(screen.getByRole("button", { name: "Rechercher" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("n’est pas celle d’une crèche Ti’Cagou");
  });
});

describe("crèche choisie", () => {
  const server = (extra = {}) => ({
    "GET /app/compat": { body: compat },
    "GET /setup/status": { body: installed },
    "GET /health": { body: healthUp },
    "POST /auth/refresh": { body: session() },
    "GET /structure": { body: structure },
    ...extra,
  });

  it("rappelle la crèche à la connexion, avec « Changer » (A4)", async () => {
    await chooseCreche(niaoulis);
    mockApi(server({ "POST /auth/refresh": { status: 401, body: { code: "session_expired" } } }));
    renderAt("/login");
    expect(await screen.findByRole("heading", { name: "Connexion" })).toBeInTheDocument();
    expect(screen.getAllByText("Les P’tits Niaoulis").length).toBeGreaterThan(0);
    expect(screen.getByRole("link", { name: "Changer" })).toHaveAttribute("href", "/creche/mes-creches");
  });

  it("verrouille l’application à l’ouverture, jusqu’au déverrouillage (ADR 0024)", async () => {
    const creche = await chooseCreche(niaoulis);
    await vault().set(`${creche.id}.session`, "refresh-1");
    await loadVaultSession();
    mockApi(server());
    renderAt("/");
    expect(await screen.findByText("Application verrouillée")).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Navigation rapide" })).toBeNull();

    await userEvent.setup().click(screen.getByRole("button", { name: "Déverrouiller" }));
    expect(await screen.findByRole("navigation", { name: "Navigation rapide" })).toBeInTheDocument();
  });

  it("ne verrouille pas juste après une connexion par mot de passe, et garde la session dans le coffre", async () => {
    const creche = await chooseCreche(niaoulis);
    await loadVaultSession();
    const { calls } = mockApi(server({ "POST /auth/login": { body: session(undefined, "refresh-app") } }));
    const user = userEvent.setup();
    renderAt("/login");
    await user.type(await screen.findByLabelText("Identifiant"), "nathalie.kaloi");
    await user.type(screen.getByLabelText("Mot de passe"), "Niaouli-2026!");
    await user.click(screen.getByRole("button", { name: "Se connecter" }));

    expect(await screen.findByRole("navigation", { name: "Navigation rapide" })).toBeInTheDocument();
    expect(screen.queryByText("Application verrouillée")).toBeNull();
    const login = calls.find((c) => c.key === "POST /auth/login")!;
    expect(login.headers["X-Ticagou-Session"]).toBeUndefined();
    expect(login.headers["X-Ticagou-App"]).toBe("direction/1.2.0 (android)");
    await waitFor(async () => expect(await vault().get(`${creche.id}.session`)).toBe("refresh-app"));
  });

  it("demande la mise à jour quand l’instance exige une version plus récente (C9)", async () => {
    native.version = "1.0.0";
    await chooseCreche(niaoulis);
    mockApi(server({ "GET /app/compat": { body: { ...compat, minimum: { direction: "1.2.0", pointage: "1.0.0" } } } }));
    renderAt("/login");
    expect(await screen.findByRole("heading", { name: "Mettez à jour Ti’Cagou" })).toBeInTheDocument();
    expect(screen.getByText(/Cette application \(1.0.0\)/)).toBeInTheDocument();
  });

  it("oublie une crèche et efface ses secrets (C8)", async () => {
    const creche = await chooseCreche(niaoulis);
    await vault().set(`${creche.id}.session`, "refresh-1");
    mockApi(server());
    const user = userEvent.setup();
    renderAt("/creche/mes-creches");
    await user.click(await screen.findByRole("button", { name: "Oublier Les P’tits Niaoulis" }));
    await user.click(screen.getByRole("button", { name: /Oublier cette crèche/ }));
    await waitFor(() => expect(restartApp).toHaveBeenCalledWith("/creche"));
    expect(listCreches()).toHaveLength(0);
    expect(await vault().get(`${creche.id}.session`)).toBeNull();
  });
});

describe("dans le navigateur", () => {
  it("aucun choix de crèche : la page est celle de l’instance", async () => {
    native.app = false;
    mockApi({ "GET /setup/status": { body: installed }, "GET /health": { body: healthUp } });
    renderAt("/");
    expect(await screen.findByRole("heading", { name: "Connexion" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Changer" })).toBeNull();
  });

  it("« /app » invite à installer l’application et donne le code", async () => {
    native.app = false;
    mockApi({ "GET /app/compat": { body: compat } });
    renderAt("/app");
    expect(await screen.findByRole("heading", { name: "Ti’Cagou dans votre poche" })).toBeInTheDocument();
    expect(await screen.findByText("NK7Q2M")).toBeInTheDocument();
  });
});
