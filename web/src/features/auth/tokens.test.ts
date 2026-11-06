import { mockApi, session } from "@/test/render";
import { clearSession, configureTokenStorage, currentRefreshToken, getAccessToken, refreshSession, storeSession } from "./tokens";

describe("jetons de session", () => {
  afterEach(() => clearSession());

  it("partage un seul rafraîchissement entre appels simultanés", async () => {
    const { calls } = mockApi({ "POST /auth/refresh": { body: { ...session(), refreshToken: null } } });
    configureTokenStorage({ persist: true });

    const [a, b] = await Promise.all([refreshSession(), refreshSession()]);

    expect(a).toBe(b);
    expect(calls.filter((c) => c.key === "POST /auth/refresh")).toHaveLength(1);
    expect(getAccessToken()).toBe("access-refresh-2");
  });

  it("au bureau, ne garde jamais le jeton de rafraîchissement dans la page", async () => {
    mockApi({ "POST /auth/refresh": { body: { ...session(), refreshToken: null } } });
    configureTokenStorage({ persist: true });

    await refreshSession();

    expect(currentRefreshToken()).toBeNull();
    expect(window.localStorage.getItem("ticagou.refreshToken")).toBeNull();
  });

  it("efface la session si le serveur refuse le rafraîchissement", async () => {
    mockApi({ "POST /auth/refresh": { status: 401, body: { code: "session_expired" } } });
    configureTokenStorage({ persist: true });
    storeSession({ ...session(), refreshToken: null });

    expect(await refreshSession()).toBeNull();
    expect(getAccessToken()).toBeNull();
  });

  it("sur la tablette, renvoie le jeton de la session en mémoire et ne stocke rien", async () => {
    const { calls } = mockApi({ "POST /auth/refresh": { body: session() } });
    configureTokenStorage({ persist: false });
    storeSession(session(undefined, "refresh-1"));

    await refreshSession();

    expect(calls.find((c) => c.key === "POST /auth/refresh")?.body).toEqual({ refreshToken: "refresh-1" });
    expect(window.localStorage.getItem("ticagou.refreshToken")).toBeNull();
  });
});
