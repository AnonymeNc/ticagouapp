import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { director, healthUp, installed, mockApi, renderAt, session, structure } from "@/test/render";
import type { Me } from "@/features/auth/types";
import { splitNavigation } from "../navigation";

const server = (user: Me = director, extra = {}) => ({
  "GET /setup/status": { body: installed },
  "GET /health": { body: healthUp },
  "POST /auth/refresh": { body: session(user) },
  "GET /structure": { body: structure },
  "POST /auth/logout": { status: 204 },
  ...extra,
});

const accountant: Me = { ...director, id: "u-2", firstName: "Sylvie", lastName: "Compta", role: "COMPTABILITE" };
const cook: Me = { ...director, id: "u-3", firstName: "Paulo", lastName: "Taufana", role: "CUISINE" };
const all = () => true;

describe("navigation du téléphone (maquette Direction mobile)", () => {
  it("donne à chaque rôle ses quatre gestes du quotidien, le reste dans « Plus »", () => {
    expect(splitNavigation("DIRECTION", all).tabs.map((t) => t.path)).toEqual(["/", "/presences", "/enfants", "/messages"]);
    expect(splitNavigation("COMPTABILITE", all).tabs.map((t) => t.path)).toEqual(["/", "/facturation", "/statistiques", "/inscriptions"]);
    expect(splitNavigation("CUISINE", all).tabs.map((t) => t.path)).toEqual(["/", "/repas", "/enfants", "/presences"]);

    const { tabs, more, visible } = splitNavigation("DIRECTION", all);
    expect([...tabs, ...more]).toHaveLength(visible.length);
    expect(more.map((m) => m.label)).toContain("Paramètres");
  });

  it("remplace une rubrique masquée par la suivante (ADR 0022)", () => {
    const { tabs, more } = splitNavigation("DIRECTION", (r) => r !== "PORTAIL_PARENTS");
    expect(tabs.map((t) => t.path)).toEqual(["/", "/presences", "/enfants", "/repas"]);
    expect(more.map((m) => m.path)).not.toContain("/messages");
  });

  it("affiche la barre basse et « Plus » avec le compte et la déconnexion", async () => {
    const { calls } = mockApi(server());
    renderAt("/plus");

    const bar = await screen.findByRole("navigation", { name: "Navigation rapide" });
    expect(within(bar).getAllByRole("link").map((l) => l.textContent)).toEqual(["Accueil", "Présences", "Enfants", "Messages", "Plus"]);
    expect(within(bar).getByRole("link", { name: "Plus" })).toHaveAttribute("aria-current", "page");

    const others = screen.getByRole("navigation", { name: "Autres rubriques" });
    expect(within(others).getByRole("link", { name: "Facturation" })).toHaveAttribute("href", "/facturation");
    expect(within(others).queryByRole("link", { name: "Enfants" })).toBeNull();
    expect(screen.getByRole("heading", { level: 1, name: "Plus" })).toBeInTheDocument();

    await userEvent.click(within(screen.getByRole("main")).getByRole("button", { name: "Se déconnecter" }));
    await waitFor(() => expect(calls.some((c) => c.key === "POST /auth/logout")).toBe(true));
  });

  it("la comptabilité a la facturation sous le pouce", async () => {
    mockApi(server(accountant));
    renderAt("/");
    const bar = await screen.findByRole("navigation", { name: "Navigation rapide" });
    expect(within(bar).getAllByRole("link").map((l) => l.textContent)).toEqual(["Accueil", "Facturation", "Stats", "Inscriptions", "Plus"]);
  });

  it("la cuisine n’a que ses rubriques", async () => {
    mockApi(server(cook));
    renderAt("/plus");
    const bar = await screen.findByRole("navigation", { name: "Navigation rapide" });
    expect(within(bar).getAllByRole("link").map((l) => l.textContent)).toEqual(["Accueil", "Repas", "Enfants", "Présences", "Plus"]);
    const others = screen.getByRole("navigation", { name: "Autres rubriques" });
    expect(within(others).queryByRole("link", { name: "Facturation" })).toBeNull();
  });
});

describe("tailles d’écran", () => {
  it("le menu latéral n’apparaît qu’à partir de la tablette, en rail jusqu’à 1 280 px", async () => {
    mockApi(server());
    renderAt("/");
    const side = await screen.findByRole("navigation", { name: "Navigation principale" });
    expect(side).toHaveClass("hidden", "md:flex", "w-[72px]", "xl:w-sidebar");
    // Libellé lisible par les lecteurs d’écran même en rail, et au survol.
    const link = within(side).getByRole("link", { name: "Facturation" });
    expect(link).toHaveAttribute("title", "Facturation");
    expect(screen.getByRole("navigation", { name: "Navigation rapide" })).toHaveClass("md:hidden");
  });
});
