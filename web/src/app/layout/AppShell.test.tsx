import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { healthUp, installed, mockApi, renderAt, session, structure } from "@/test/render";
import { navItems } from "../navigation";

const connectedServer = {
  "GET /setup/status": { body: installed },
  "GET /health": { body: healthUp },
  "POST /auth/refresh": { body: session() },
  "GET /structure": { body: structure },
  "POST /auth/logout": { status: 204 },
};

describe("coquille du poste bureau", () => {
  beforeEach(() => {
    // Session précédente conservée sur le poste
    window.localStorage.setItem("ticagou.refreshToken", "refresh-1");
  });

  it("reprend la session et affiche la navigation, l'utilisateur et la structure", async () => {
    const { calls } = mockApi(connectedServer);
    renderAt("/enfants");

    const nav = await screen.findByRole("navigation", { name: "Navigation principale" });
    for (const item of navItems) {
      expect(nav).toHaveTextContent(item.label);
    }
    expect(within(nav).getByRole("link", { name: "Enfants" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("heading", { level: 1, name: "Enfants" })).toBeInTheDocument();
    expect(screen.getByText("Nathalie Kaloï")).toBeInTheDocument();
    expect(screen.getByText("Direction")).toBeInTheDocument();
    expect(await screen.findByText("Les P’tits Niaoulis · Dumbéa")).toBeInTheDocument();
    expect(await screen.findByText("Connecté")).toBeInTheDocument();

    expect(calls.find((c) => c.key === "POST /auth/refresh")?.body).toBeUndefined();
    const structureCall = calls.find((c) => c.key === "GET /structure");
    expect(structureCall?.headers.Authorization).toBe("Bearer access-refresh-2");
  });

  it("masque les menus des rubriques que la crèche n’utilise pas (ADR 0022)", async () => {
    mockApi({ ...connectedServer, "GET /rubriques": { body: { hidden: ["COMPTABILITE", "PERSONNEL", "REPAS"], labels: ["Comptabilité", "Personnel", "Repas"] } } });
    renderAt("/enfants");

    const nav = await screen.findByRole("navigation", { name: "Navigation principale" });
    await waitFor(() => expect(nav).not.toHaveTextContent("Personnel"));
    expect(nav).not.toHaveTextContent("Repas");
    expect(nav).toHaveTextContent("Facturation");
    expect(nav).toHaveTextContent("Inscriptions");
  });

  it("renvoie vers la connexion après la déconnexion", async () => {
    const { calls } = mockApi(connectedServer);
    renderAt("/");

    await userEvent.click(await screen.findByRole("button", { name: "Se déconnecter" }));

    expect(await screen.findByRole("heading", { name: "Connexion" })).toBeInTheDocument();
    expect(calls.some((c) => c.key === "POST /auth/logout")).toBe(true);
  });

  it("rafraîchit la session une seule fois quand le jeton d'accès expire", async () => {
    let structureCalls = 0;
    const { calls } = mockApi({
      ...connectedServer,
      "GET /structure": () => (++structureCalls === 1 ? { status: 401, body: { code: "unauthenticated" } } : { body: structure }),
    });
    renderAt("/");

    expect(await screen.findByText("Les P’tits Niaoulis · Dumbéa")).toBeInTheDocument();
    expect(calls.filter((c) => c.key === "POST /auth/refresh")).toHaveLength(2);
  });
});

describe("accès au poste bureau", () => {
  it("sans session, affiche la connexion", async () => {
    mockApi({ "GET /setup/status": { body: installed }, "GET /health": { body: healthUp } });
    renderAt("/");

    expect(await screen.findByRole("heading", { name: "Connexion" })).toBeInTheDocument();
    expect(screen.getByText("Les P’tits Niaoulis")).toBeInTheDocument();
  });

  it("serveur non installé : ouvre l'assistant de premier lancement", async () => {
    mockApi({ "GET /setup/status": { body: { initialized: false, structureName: null } } });
    renderAt("/");

    expect(await screen.findByRole("heading", { name: "Bienvenue dans Ti’Cagou" })).toBeInTheDocument();
  });

  it("service injoignable : l'annonce sans demander d'adresse", async () => {
    vi.spyOn(globalThis, "fetch").mockRejectedValue(new TypeError("Failed to fetch"));
    renderAt("/");

    expect(await screen.findByRole("heading", { name: "Ti’Cagou ne répond pas" })).toBeInTheDocument();
  });

  it("connexion par identifiant et mot de passe", async () => {
    const { calls } = mockApi({
      "GET /setup/status": { body: installed },
      "GET /health": { body: healthUp },
      "GET /structure": { body: structure },
      "POST /auth/login": (body) =>
        (body as { password: string }).password === "Niaouli-2026!"
          ? { body: session() }
          : { status: 401, body: { code: "bad_credentials", detail: "Identifiant ou mot de passe incorrect." } },
    });
    renderAt("/login");
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText("Identifiant"), "nathalie.kaloi");
    await user.type(screen.getByLabelText("Mot de passe"), "faux");
    await user.click(screen.getByRole("button", { name: "Se connecter" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Identifiant ou mot de passe incorrect.");

    await user.type(screen.getByLabelText("Mot de passe"), "Niaouli-2026!");
    await user.click(screen.getByRole("button", { name: "Se connecter" }));

    expect(await screen.findByRole("heading", { level: 1, name: /^Bonjour, / })).toBeInTheDocument();
    expect(calls.filter((c) => c.key === "POST /auth/login")).toHaveLength(2);
    expect(window.localStorage.getItem("ticagou.refreshToken")).toBeNull();
  });

  it("demande le code de l'application quand la double authentification est active", async () => {
    const { calls } = mockApi({
      "GET /setup/status": { body: installed },
      "GET /health": { body: healthUp },
      "GET /structure": { body: structure },
      "POST /auth/login": (body) =>
        (body as { code?: string }).code === "123456"
          ? { body: session() }
          : { status: 401, body: { code: "mfa_required", detail: "Saisissez le code de votre application." } },
    });
    renderAt("/login");
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText("Identifiant"), "nathalie.kaloi");
    await user.type(screen.getByLabelText("Mot de passe"), "Niaouli-2026!");
    await user.click(screen.getByRole("button", { name: "Se connecter" }));
    await user.type(await screen.findByLabelText("Code de votre application d’authentification"), "123456");
    await user.click(screen.getByRole("button", { name: "Se connecter" }));

    expect(await screen.findByRole("heading", { level: 1, name: /^Bonjour, / })).toBeInTheDocument();
    expect(calls.filter((c) => c.key === "POST /auth/login").at(-1)?.body).toMatchObject({ login: "nathalie.kaloi", code: "123456" });
  });

  it("impose l'enregistrement du téléphone avant tout accès", async () => {
    const pending = { ...session(), user: { ...session().user, mfaEnrollmentRequired: true } };
    const { calls } = mockApi({
      ...connectedServer,
      "POST /auth/refresh": () => ({ body: calls.some((c) => c.key === "POST /auth/mfa/confirm") ? session() : pending }),
      "POST /auth/mfa/start": { body: { secret: "ABCDEFGH", uri: "otpauth://totp/test?secret=ABCDEFGH" } },
      "POST /auth/mfa/confirm": { status: 204 },
    });
    renderAt("/");
    const user = userEvent.setup();

    expect(await screen.findByRole("heading", { name: "Protégez votre compte" })).toBeInTheDocument();
    expect(await screen.findByText("ABCDEFGH")).toBeInTheDocument();
    await user.type(screen.getByLabelText("Code affiché par l’application"), "123456");
    await user.click(screen.getByRole("button", { name: "Activer" }));

    expect(await screen.findByRole("heading", { level: 1, name: /^Bonjour, / })).toBeInTheDocument();
  });

  it("signale les champs vides sans appeler le serveur", async () => {
    const { calls } = mockApi({ "GET /setup/status": { body: installed } });
    renderAt("/login");

    await userEvent.click(await screen.findByRole("button", { name: "Se connecter" }));

    expect(screen.getByText("Indiquez votre identifiant.")).toBeInTheDocument();
    expect(screen.getByLabelText("Identifiant")).toHaveAttribute("aria-invalid", "true");
    expect(calls.some((c) => c.key === "POST /auth/login")).toBe(false);
  });
});
