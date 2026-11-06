import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { healthUp, mockApi, renderAt, session, structure } from "@/test/render";
import { describeMonths } from "./setupSchemas";

describe("assistant de premier lancement", () => {
  it("installe la structure en une question par écran", async () => {
    let initialized = false;
    const { calls } = mockApi({
      "GET /setup/status": () => ({ body: { initialized, structureName: initialized ? "Les P’tits Niaoulis" : null } }),
      "GET /health": { body: healthUp },
      "GET /structure": { body: structure },
      "POST /setup": () => {
        initialized = true;
        return { status: 201, body: session() };
      },
    });
    renderAt("/setup?jeton=jeton-de-demarrage");
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Commencer" }));

    expect(screen.getByText("Étape 1 sur 5")).toBeInTheDocument();
    await user.type(screen.getByLabelText("Nom de la structure"), "Les P’tits Niaoulis");
    await user.type(screen.getByLabelText("Commune"), "Dumbéa");
    await user.click(screen.getByRole("button", { name: "Continuer" }));

    // Agrément : contrôles de saisie
    await user.type(screen.getByLabelText("Capacité agréée (places)"), "40");
    await user.type(screen.getByLabelText("Date d’agrément"), "31/02/2024");
    await user.type(screen.getByLabelText("RIDET"), "123");
    await user.click(screen.getByRole("button", { name: "Continuer" }));
    expect(screen.getByText("Date attendue au format JJ/MM/AAAA.")).toBeInTheDocument();
    expect(screen.getByText(/Le RIDET comporte 7 chiffres/)).toBeInTheDocument();
    await user.clear(screen.getByLabelText("Date d’agrément"));
    await user.type(screen.getByLabelText("Date d’agrément"), "15/01/2024");
    await user.clear(screen.getByLabelText("RIDET"));
    await user.type(screen.getByLabelText("RIDET"), "1234567.001");
    await user.click(screen.getByRole("button", { name: "Continuer" }));

    // Sections proposées : 48 places pour 40 agréées
    expect(screen.getByText("Total 48 / 40 places")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Continuer" }));
    expect(screen.getByRole("alert")).toHaveTextContent("ne peut pas dépasser les 40 places");
    const places = screen.getAllByLabelText("Places");
    await user.clear(places[2]);
    await user.type(places[2], "12");
    expect(screen.getByText("Total 40 / 40 places")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Continuer" }));

    // Compte de direction
    await user.type(screen.getByLabelText("Prénom"), "Nathalie");
    await user.type(screen.getByLabelText("Nom"), "Kaloï");
    await user.type(screen.getByLabelText("Identifiant"), "nathalie.kaloi");
    await user.type(screen.getByLabelText("Mot de passe"), "Niaouli-2026!");
    await user.type(screen.getByLabelText("Confirmation"), "Niaouli-2026?");
    await user.click(screen.getByRole("button", { name: "Continuer" }));
    expect(screen.getByText("Les deux mots de passe ne sont pas identiques.")).toBeInTheDocument();
    await user.clear(screen.getByLabelText("Confirmation"));
    await user.type(screen.getByLabelText("Confirmation"), "Niaouli-2026!");
    await user.click(screen.getByRole("button", { name: "Continuer" }));

    // Récapitulatif puis création
    expect(screen.getByRole("heading", { name: "Tout est prêt ?" })).toBeInTheDocument();
    expect(screen.getByText("40 places")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Créer ma structure" }));

    expect(await screen.findByRole("heading", { name: "Votre structure est prête" })).toBeInTheDocument();
    expect(calls.find((c) => c.key === "POST /setup")?.body).toEqual({
      structure: {
        name: "Les P’tits Niaoulis",
        city: "Dumbéa",
        ridet: "1234567.001",
        agrementNumber: null,
        agrementDate: "2024-01-15",
        capacity: 40,
      },
      sections: [
        { name: "Bébés", minAgeMonths: 2, maxAgeMonths: 18, capacity: 12, color: "#9e2f66", displayOrder: 0 },
        { name: "Moyens", minAgeMonths: 18, maxAgeMonths: 30, capacity: 16, color: "#924510", displayOrder: 1 },
        { name: "Grands", minAgeMonths: 30, maxAgeMonths: null, capacity: 12, color: "#3d479b", displayOrder: 2 },
      ],
      admin: { firstName: "Nathalie", lastName: "Kaloï", login: "nathalie.kaloi", password: "Niaouli-2026!" },
    });
    const sent = vi.mocked(globalThis.fetch).mock.calls.find(([url]) => String(url).endsWith("/api/v1/setup"));
    expect((sent?.[1]?.headers as Record<string, string>)["X-Ticagou-Setup"]).toBe("jeton-de-demarrage");

    await user.click(screen.getByRole("button", { name: "Ouvrir le tableau de bord" }));
    expect(await screen.findByRole("heading", { level: 1, name: /^Bonjour, / })).toBeInTheDocument();
    expect(screen.getByText("Nathalie Kaloï")).toBeInTheDocument();
  }, 15_000);

  it("affiche l'erreur du serveur au récapitulatif", async () => {
    mockApi({
      "GET /setup/status": { body: { initialized: false, structureName: null } },
      "POST /setup": {
        status: 403,
        body: { code: "setup_token", detail: "Ouvrez le lien de démarrage reçu de Ti’Cagou pour créer votre crèche." },
      },
    });
    renderAt("/setup");
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Commencer" }));
    await user.type(screen.getByLabelText("Nom de la structure"), "Crèche");
    await user.click(screen.getByRole("button", { name: "Continuer" }));
    await user.type(screen.getByLabelText("Capacité agréée (places)"), "48");
    await user.click(screen.getByRole("button", { name: "Continuer" }));
    await user.click(screen.getByRole("button", { name: "Continuer" }));
    await user.type(screen.getByLabelText("Prénom"), "Nathalie");
    await user.type(screen.getByLabelText("Nom"), "Kaloï");
    await user.type(screen.getByLabelText("Identifiant"), "nathalie.kaloi");
    await user.type(screen.getByLabelText("Mot de passe"), "Niaouli-2026!");
    await user.type(screen.getByLabelText("Confirmation"), "Niaouli-2026!");
    await user.click(screen.getByRole("button", { name: "Continuer" }));
    await user.click(screen.getByRole("button", { name: "Créer ma structure" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Ouvrez le lien de démarrage");
  });

  it("serveur déjà installé : renvoie vers la connexion", async () => {
    mockApi({ "GET /setup/status": { body: { initialized: true, structureName: "Crèche" } } });
    renderAt("/setup");

    expect(await screen.findByRole("heading", { name: "Connexion" })).toBeInTheDocument();
  });

  it("décrit les âges en années et mois", () => {
    expect(describeMonths("2")).toBe("2 mois");
    expect(describeMonths("18")).toBe("1 an 6 mois");
    expect(describeMonths("30")).toBe("2 ans 6 mois");
    expect(describeMonths("36")).toBe("3 ans");
  });
});
