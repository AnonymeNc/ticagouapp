import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router";
import App from "@/App";

type Call = { path: string; method: string; headers: Record<string, string>; body: unknown };

function mockServer(routes: Record<string, (call: Call) => { status?: number; body?: unknown }>) {
  const calls: Call[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    const path = url.replace(/^.*\/api\/v1/, "");
    const call: Call = {
      path,
      method: init?.method ?? "GET",
      headers: (init?.headers ?? {}) as Record<string, string>,
      body: init?.body ? JSON.parse(String(init.body)) : undefined,
    };
    calls.push(call);
    const handler = routes[`${call.method} ${path}`];
    const { status = 200, body } = handler ? handler(call) : { status: 404, body: { code: "not_found", detail: "Inconnu" } };
    return new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
  });
  return calls;
}

function renderApp(path = "/") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const operator = { id: "1", login: "jordan", displayName: "Jordan Moniot" };

describe("connexion à la console", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=jeton-csrf";
  });

  it("mot de passe, QR code à la première connexion, puis code : la console s'ouvre", async () => {
    let connected = false;
    const calls = mockServer({
      "GET /auth/state": () => ({
        body: connected
          ? { setupRequired: false, login: { step: "CONNECTE", secret: null, uri: null }, operator }
          : { setupRequired: false, login: { step: "MOT_DE_PASSE", secret: null, uri: null }, operator: null },
      }),
      "POST /auth/login": () => ({ body: { step: "TOTP_ENROLL", secret: "JBSWY3DPEHPK3PXP", uri: "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP" } }),
      "POST /auth/totp": () => {
        connected = true;
        return { body: operator };
      },
    });
    const user = userEvent.setup();
    renderApp();

    await user.type(await screen.findByLabelText("Identifiant"), "jordan");
    await user.type(screen.getByLabelText("Mot de passe"), "un mot de passe assez long");
    await user.click(screen.getByRole("button", { name: "Continuer" }));

    expect(await screen.findByRole("img", { name: /QR code/ })).toBeInTheDocument();
    expect(screen.getByText("JBSWY3DPEHPK3PXP")).toBeInTheDocument();
    await user.type(screen.getByLabelText("Code à 6 chiffres"), "12a3456");
    await user.click(screen.getByRole("button", { name: "Activer et se connecter" }));

    expect(await screen.findByRole("heading", { name: "Bonjour Jordan" })).toBeInTheDocument();
    expect(screen.getByRole("navigation", { name: "Navigation principale" })).toHaveTextContent("Boîte d’envoi");
    const login = calls.find((c) => c.path === "/auth/login")!;
    expect(login.headers["X-XSRF-TOKEN"]).toBe("jeton-csrf");
    expect(calls.find((c) => c.path === "/auth/totp")!.body).toEqual({ code: "123456" });
  });

  it("affiche le refus du serveur et l'heure de fin de blocage", async () => {
    mockServer({
      "GET /auth/state": () => ({ body: { setupRequired: false, login: { step: "MOT_DE_PASSE", secret: null, uri: null }, operator: null } }),
      "POST /auth/login": () => ({
        status: 423,
        body: { code: "account_locked", detail: "Trop d’essais manqués : ce compte est bloqué pendant quelques minutes.", lockedUntil: "2026-11-09T08:15:00Z" },
      }),
    });
    const user = userEvent.setup();
    renderApp();
    await user.type(await screen.findByLabelText("Identifiant"), "jordan");
    await user.type(screen.getByLabelText("Mot de passe"), "x");
    await user.click(screen.getByRole("button", { name: "Continuer" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Réessayez après 19:15");
  });

  it("la déconnexion ramène à l'écran de connexion", async () => {
    let connected = true;
    const calls = mockServer({
      "GET /auth/state": () => ({
        body: connected
          ? { setupRequired: false, login: { step: "CONNECTE", secret: null, uri: null }, operator }
          : { setupRequired: false, login: { step: "MOT_DE_PASSE", secret: null, uri: null }, operator: null },
      }),
      "POST /auth/logout": () => {
        connected = false;
        return { status: 204 };
      },
    });
    const user = userEvent.setup();
    renderApp();
    await user.click(await screen.findByRole("button", { name: "Se déconnecter" }));
    expect(await screen.findByRole("heading", { name: "Connexion" })).toBeInTheDocument();
    expect(calls.filter((c) => c.path === "/auth/logout")).toHaveLength(1);
  });

  it("le lien de premier compte crée le compte avec le jeton de l'adresse", async () => {
    const calls = mockServer({
      "GET /auth/state": () => ({ body: { setupRequired: true, login: { step: "MOT_DE_PASSE", secret: null, uri: null }, operator: null } }),
      "POST /auth/setup": () => ({ status: 201, body: operator }),
    });
    const user = userEvent.setup();
    renderApp("/premier-compte?jeton=abc123");
    await user.type(await screen.findByLabelText("Identifiant"), "jordan");
    await user.type(screen.getByLabelText("Nom affiché"), "Jordan Moniot");
    await user.type(screen.getByLabelText("Mot de passe"), "un mot de passe assez long");
    await user.type(screen.getByLabelText("Mot de passe, encore"), "un mot de passe assez long");
    await user.click(screen.getByRole("button", { name: "Créer le compte" }));
    expect(await screen.findByText(/Compte créé/)).toBeInTheDocument();
    expect(calls.find((c) => c.path === "/auth/setup")!.body).toEqual({
      token: "abc123",
      login: "jordan",
      displayName: "Jordan Moniot",
      password: "un mot de passe assez long",
    });
  });
});
