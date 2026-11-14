import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider, createMemoryRouter } from "react-router";
import { routes } from "./App";
import { childPhotoUrl, portal } from "./api";
import { getSessionToken, storeAppSession } from "./native";

// Application Ti’Cagou Parents simulée : adresse du portail, jeton porteur, coffre en mémoire.
vi.mock("./native", async (original) => {
  const actual = await original<typeof import("./native")>();
  return { ...actual, IS_APP: true, PORTAL_URL: "https://parents.ticagou.nc", hasPlugins: () => false,
    appHeaders: () => ({ "X-Ticagou-App": "parents/1.0.0 (ios)" }) };
});

type Reply = { status?: number; body?: unknown };

function mockApi(routes: Record<string, Reply>) {
  const calls: { url: string; key: string; body: unknown; init: RequestInit }[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const url = new URL(String(input));
    const key = `${init?.method ?? "GET"} ${url.pathname.replace(/^\/api\/v1/, "")}`;
    calls.push({ url: String(input), key, body: init?.body ? JSON.parse(String(init.body)) : undefined, init: init ?? {} });
    const reply = routes[key];
    if (!reply) return new Response(JSON.stringify({ code: "not_found", detail: key }), { status: 404 });
    const status = reply.status ?? 200;
    return new Response(status === 204 ? null : JSON.stringify(reply.body ?? {}), { status, headers: { "Content-Type": "application/json" } });
  });
  return calls;
}

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

const me = { email: "claire@mail.nc", families: [{
  structureId: "s-1", structureName: "Les P’tits Niaoulis", structurePhone: null, paymentInfo: null, mealAbsenceCutoff: null,
  familyId: "f-1", guardianId: "g-1", firstName: "Claire",
  children: [{ id: "c-1", firstName: "Maëlys", lastInitial: "G", section: "Moyens", hasPhoto: true, sharedHealth: [] }],
  unreadMessages: 0, documentsToSign: 0 }] };

afterEach(() => storeAppSession(null));

describe("application Ti’Cagou Parents", () => {
  it("se connecte par code, garde le jeton et l’envoie au portail, sans cookie", async () => {
    const calls = mockApi({
      "GET /me": { status: 401, body: { code: "unauthenticated" } },
      "POST /auth/request": { status: 202, body: { message: "ok" } },
      "POST /auth/verify": { body: { email: "claire@mail.nc", token: "jeton-session", expiresAt: "2027-01-01T00:00:00Z" } },
      "GET /me/media-token": { body: { token: "jeton-media", expiresAt: "2026-11-16T23:00:00Z" } },
    });
    const user = userEvent.setup();
    renderAt("/connexion");
    await user.type(await screen.findByLabelText("Adresse e-mail"), "claire@mail.nc");
    await user.click(screen.getByRole("button", { name: "Recevoir mon code de connexion" }));
    expect(await screen.findByText(/Saisissez le code à 6 chiffres/)).toBeInTheDocument();
    await user.type(screen.getByLabelText("Code à 6 chiffres"), "123456");
    await user.click(screen.getByRole("button", { name: "Se connecter" }));

    await waitFor(() => expect(getSessionToken()).toBe("jeton-session"));
    const verify = calls.find((c) => c.key === "POST /auth/verify")!;
    expect(verify.url).toBe("https://parents.ticagou.nc/api/v1/auth/verify");
    expect(verify.init.credentials).toBe("omit");
    expect((verify.init.headers as Record<string, string>)["X-Ticagou-App"]).toBe("parents/1.0.0 (ios)");
    const media = calls.find((c) => c.key === "GET /me/media-token")!;
    expect((media.init.headers as Record<string, string>).Authorization).toBe("Bearer jeton-session");
    // Les photos portent le jeton de média, jamais le jeton de session.
    expect(childPhotoUrl("s-1", "c-1")).toBe("https://parents.ticagou.nc/api/v1/me/children/s-1/c-1/photo?jeton=jeton-media");
  });

  it("supprime le compte après le code reçu, puis l’annonce à la connexion (Pa8)", async () => {
    await storeAppSession("jeton-session");
    const calls = mockApi({
      "GET /me": { body: me },
      "POST /auth/request": { status: 202, body: { message: "ok" } },
      "POST /me/account/delete": { status: 204 },
      "GET /me/children/s-1/c-1/day": { body: { day: "2026-11-02", items: [], days: [] } },
    });
    const user = userEvent.setup();
    const router = renderAt("/compte/supprimer");
    expect(await screen.findByText("Ce qui reste à la crèche")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Recevoir un code de confirmation" }));
    await user.type(await screen.findByLabelText("Code envoyé à claire@mail.nc"), "654321");
    await user.click(screen.getByRole("button", { name: /Supprimer définitivement/ }));

    await waitFor(() => expect(router.state.location.search).toBe("?compte=supprime"));
    expect(calls.find((c) => c.key === "POST /me/account/delete")?.body).toEqual({ code: "654321" });
    expect(getSessionToken()).toBeNull();
    expect(await screen.findByText(/Votre compte est supprimé/)).toBeInTheDocument();
  });

  it("propose le verrouillage dans « Mon compte »", async () => {
    await storeAppSession("jeton-session");
    mockApi({ "GET /me": { body: me }, "GET /me/children/s-1/c-1/day": { body: { day: "2026-11-02", items: [], days: [] } } });
    renderAt("/compte");
    const toggle = await screen.findByLabelText(/Verrouiller l’application/);
    expect(toggle).not.toBeChecked();
    await userEvent.click(toggle);
    expect(toggle).toBeChecked();
    expect(window.localStorage.getItem("ticagou.parents.verrou")).toBe("1");
    expect(portal).toBeDefined();
  });
});
