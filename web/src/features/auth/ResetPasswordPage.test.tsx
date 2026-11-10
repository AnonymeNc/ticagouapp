import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { mockApi, renderAt } from "@/test/render";

describe("lien de réinitialisation du mot de passe", () => {
  it("affiche le compte, vérifie la saisie puis enregistre", async () => {
    const { calls } = mockApi({
      "POST /auth/password-reset/check": { body: { login: "nathalie.kaloi", name: "Nathalie Kaloï" } },
      "POST /auth/password-reset": { status: 204 },
    });
    renderAt("/reinitialiser?jeton=abc123");
    const user = userEvent.setup();

    expect(await screen.findByText("nathalie.kaloi")).toBeInTheDocument();
    await user.type(screen.getByLabelText("Nouveau mot de passe"), "Lagon-Bleu-2027!");
    await user.type(screen.getByLabelText("Retapez-le"), "Lagon-Bleu-2026!");
    await user.click(screen.getByRole("button", { name: "Enregistrer" }));
    expect(await screen.findByText("Les deux mots de passe sont différents.")).toBeInTheDocument();

    await user.clear(screen.getByLabelText("Retapez-le"));
    await user.type(screen.getByLabelText("Retapez-le"), "Lagon-Bleu-2027!");
    await user.click(screen.getByRole("button", { name: "Enregistrer" }));

    expect(await screen.findByRole("link", { name: "Se connecter" })).toBeInTheDocument();
    expect(calls.find((c) => c.key === "POST /auth/password-reset")?.body).toEqual({ token: "abc123", password: "Lagon-Bleu-2027!" });
  });

  it("lien expiré : le dit, sans formulaire", async () => {
    mockApi({
      "POST /auth/password-reset/check": {
        status: 422,
        body: { code: "reset_expired", detail: "Ce lien a expiré ou a déjà servi. Demandez-en un nouveau à l’éditeur de Ti’Cagou." },
      },
    });
    renderAt("/reinitialiser?jeton=vieux");

    expect(await screen.findByRole("alert")).toHaveTextContent("Ce lien a expiré ou a déjà servi.");
    expect(screen.queryByLabelText("Nouveau mot de passe")).not.toBeInTheDocument();
  });
});
