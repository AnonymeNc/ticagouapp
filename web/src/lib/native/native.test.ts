import { mockApi, session } from "@/test/render";
import { api } from "@/lib/api";
import {
  clearSession,
  configureTokenStorage,
  currentRefreshToken,
  loadVaultSession,
  refreshSession,
  sessionHeaders,
  storeSession,
  vaultSettled,
} from "@/features/auth/tokens";
import { loadNativeWorkstation, workstationHeaders } from "@/lib/workstation";
import { apiBase, chooseCreche, currentCreche, forgetCreche, listCreches, loadCreches, normalizeAddress, resetCreches, switchCreche } from "./creches";
import { compareVersions } from "./platform";
import { memoryVault, setVault, vault } from "./vault";

// L'application simulée : cible « direction » sur Android, sans plugin natif.
const native = vi.hoisted(() => ({ app: true }));
vi.mock("./platform", async (original) => {
  const actual = await original<typeof import("./platform")>();
  return {
    ...actual,
    CIBLE: "direction",
    APP_VERSION: "1.2.0",
    isNativeApp: () => native.app,
    hasNativePlugins: () => false,
    nativePlatform: () => (native.app ? "android" : null),
    appHeaders: () => (native.app ? { "X-Ticagou-App": "direction/1.2.0 (android)" } : {}),
  };
});

function memoryPrefs() {
  const values = new Map<string, string>();
  return { get: async (k: string) => values.get(k) ?? null, set: async (k: string, v: string) => void values.set(k, v) };
}

const niaoulis = { nom: "Les P’tits Niaoulis", commune: "Dumbéa", adresse: "https://ptits-niaoulis.ticagou.nc", code: "NK7Q2M" };
const bulles = { nom: "Les Bulles du Lagon", commune: "Nouméa", adresse: "https://bulles.ticagou.nc", code: null };

beforeEach(() => {
  native.app = true;
  setVault(memoryVault());
  resetCreches(memoryPrefs());
});

afterEach(() => {
  clearSession();
  setVault(null);
});

describe("adresse d’une crèche", () => {
  it("ajoute https et retire le chemin", () => {
    expect(normalizeAddress("ptits-niaoulis.ticagou.nc/")).toBe("https://ptits-niaoulis.ticagou.nc");
    expect(normalizeAddress(" https://ptits-niaoulis.ticagou.nc/app?x=1 ")).toBe("https://ptits-niaoulis.ticagou.nc");
  });

  it("refuse http, sauf vers le poste local en développement", () => {
    expect(normalizeAddress("http://creche.nc", false)).toBeNull();
    expect(normalizeAddress("http://localhost:8093", false)).toBeNull();
    expect(normalizeAddress("http://localhost:8093", true)).toBe("http://localhost:8093");
    expect(normalizeAddress("https://moi:secret@creche.nc")).toBeNull();
    expect(normalizeAddress("")).toBeNull();
  });
});

describe("crèches connues", () => {
  it("retrouve une crèche par son adresse et la rend courante", async () => {
    const first = await chooseCreche(niaoulis);
    await chooseCreche(bulles);
    expect(currentCreche()?.nom).toBe("Les Bulles du Lagon");

    const again = await chooseCreche({ ...niaoulis, code: null });
    expect(again.id).toBe(first.id);
    expect(again.code).toBe("NK7Q2M");
    expect(listCreches()).toHaveLength(2);
    expect(apiBase()).toBe("https://ptits-niaoulis.ticagou.nc");
  });

  it("survit au redémarrage de l’application", async () => {
    const prefs = memoryPrefs();
    resetCreches(prefs);
    const creche = await chooseCreche(niaoulis);
    resetCreches(prefs);
    await loadCreches();
    expect(currentCreche()?.id).toBe(creche.id);
  });

  it("oublier une crèche efface ses secrets, pas ceux de l’autre", async () => {
    const a = await chooseCreche(niaoulis);
    const b = await chooseCreche(bulles);
    await vault().set(`${a.id}.session`, "jeton-a");
    await vault().set(`${a.id}.poste`, "poste-a");
    await vault().set(`${b.id}.session`, "jeton-b");

    await forgetCreche(a.id);

    expect(await vault().get(`${a.id}.session`)).toBeNull();
    expect(await vault().get(`${a.id}.poste`)).toBeNull();
    expect(await vault().get(`${b.id}.session`)).toBe("jeton-b");
    expect(listCreches().map((c) => c.nom)).toEqual(["Les Bulles du Lagon"]);
  });

  it("dans le navigateur, l’API reste sur la même origine", async () => {
    await chooseCreche(niaoulis);
    native.app = false;
    expect(apiBase()).toBe("");
  });
});

describe("session de l’application Direction", () => {
  it("garde le jeton de rafraîchissement dans le coffre de la crèche, jamais dans le stockage local", async () => {
    const creche = await chooseCreche(niaoulis);
    configureTokenStorage({ persist: true });
    expect(sessionHeaders()).toEqual({});

    storeSession(session(undefined, "refresh-app"));
    await vaultSettled();

    expect(await vault().get(`${creche.id}.session`)).toBe("refresh-app");
    expect(JSON.stringify({ ...window.localStorage })).not.toContain("refresh-app");

    clearSession();
    await vaultSettled();
    expect(await vault().get(`${creche.id}.session`)).toBeNull();
  });

  it("reprend la session au démarrage et l’envoie dans le corps de la requête", async () => {
    const creche = await chooseCreche(niaoulis);
    await vault().set(`${creche.id}.session`, "refresh-gardé");
    await loadVaultSession();
    configureTokenStorage({ persist: true });
    const { calls } = mockApi({ "POST /auth/refresh": { body: session(undefined, "refresh-3") } });

    await refreshSession();

    expect(calls[0].body).toEqual({ refreshToken: "refresh-gardé" });
    expect(calls[0].headers["X-Ticagou-App"]).toBe("direction/1.2.0 (android)");
    await vaultSettled();
    expect(await vault().get(`${creche.id}.session`)).toBe("refresh-3");
  });

  it("une coupure réseau ne fait pas perdre la session gardée", async () => {
    const creche = await chooseCreche(niaoulis);
    await vault().set(`${creche.id}.session`, "refresh-gardé");
    await loadVaultSession();
    configureTokenStorage({ persist: true });
    vi.spyOn(globalThis, "fetch").mockRejectedValue(new TypeError("Failed to fetch"));

    expect(await refreshSession()).toBeNull();
    await vaultSettled();
    expect(currentRefreshToken()).toBe("refresh-gardé");
    expect(await vault().get(`${creche.id}.session`)).toBe("refresh-gardé");
  });

  it("un refus du serveur efface la session du coffre", async () => {
    const creche = await chooseCreche(niaoulis);
    await vault().set(`${creche.id}.session`, "refresh-révoqué");
    await loadVaultSession();
    configureTokenStorage({ persist: true });
    mockApi({ "POST /auth/refresh": { status: 401, body: { code: "session_expired" } } });

    expect(await refreshSession()).toBeNull();
    await vaultSettled();
    expect(await vault().get(`${creche.id}.session`)).toBeNull();
  });
});

describe("appels à l’API depuis l’application", () => {
  it("vise l’adresse de la crèche avec l’identifiant de poste du coffre", async () => {
    const a = await chooseCreche(niaoulis);
    await loadNativeWorkstation();
    const posteA = workstationHeaders()["X-Ticagou-Poste"];
    expect(await vault().get(`${a.id}.poste`)).toBe(posteA);
    expect(workstationHeaders()["X-Ticagou-Poste-Nom"]).toMatch(/Android/);

    const fetch = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("{}", { status: 200 }));
    await api("/health", { auth: false });
    expect(String(fetch.mock.calls[0][0])).toBe("https://ptits-niaoulis.ticagou.nc/api/v1/health");

    // Autre crèche : autre identifiant de poste.
    const b = await chooseCreche(bulles);
    await loadNativeWorkstation();
    expect(workstationHeaders()["X-Ticagou-Poste"]).not.toBe(posteA);
    await switchCreche(a.id);
    await loadNativeWorkstation();
    expect(workstationHeaders()["X-Ticagou-Poste"]).toBe(posteA);
    expect(b.id).not.toBe(a.id);
  });
});

describe("versions", () => {
  it("compare numériquement", () => {
    expect(compareVersions("1.10.0", "1.9.0")).toBeGreaterThan(0);
    expect(compareVersions("1.2.0", "1.2.0")).toBe(0);
    expect(compareVersions("1.2.0", "1.2.1")).toBeLessThan(0);
  });
});
