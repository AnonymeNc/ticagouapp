import { screen } from "@testing-library/react";
import { director, healthUp, installed, mockApi, renderAt, session, structure } from "@/test/render";
import type { Me } from "@/features/auth/types";
import type { LicenseStatus } from "@/features/settings/systemApi";
import { bannerTone } from "./LicenseBanner";

const educatrice: Me = { ...director, id: "u-2", firstName: "Léa", lastName: "Wema", role: "EDUCATRICE" };

function server(user: Me, status: LicenseStatus) {
  window.localStorage.setItem("ticagou.refreshToken", "refresh-1");
  return mockApi({
    "GET /setup/status": { body: installed },
    "GET /health": { body: healthUp },
    "POST /auth/refresh": { body: session(user) },
    "GET /structure": { body: structure },
    "GET /sections": { body: [] },
    "GET /system/license/status": { body: status },
  });
}

describe("bandeau d’abonnement", () => {
  it("passe de l’information à l’alerte à mesure que l’échéance approche", () => {
    const at = (state: LicenseStatus["state"], daysLeft: number) => bannerTone({ state, daysLeft, deadline: null, message: "…" });
    expect(at("ACTIVE", 200)).toBeNull();
    expect(at("EVALUATION", 20)).toBe("info");
    expect(at("A_RENOUVELER", 25)).toBe("info");
    expect(at("A_RENOUVELER", 5)).toBe("warning");
    expect(at("EXPIREE_GRACE", 25)).toBe("warning");
    expect(at("EXPIREE_GRACE", 8)).toBe("danger");
    expect(at("LECTURE_SEULE", 0)).toBe("danger");
    expect(at("EVALUATION_TERMINEE", 0)).toBe("danger");
  });

  it("prévient toute l’équipe de la lecture seule, sans lien vers les paramètres", async () => {
    server(educatrice, {
      state: "LECTURE_SEULE", deadline: "2026-11-01", daysLeft: 0,
      message: "Licence expirée le 01/10/2026 : Ti’Cagou est en lecture seule.",
    });
    renderAt("/");
    expect(await screen.findByRole("alert")).toHaveTextContent("Ti’Cagou est en lecture seule");
    expect(screen.queryByRole("link", { name: "Voir l’abonnement" })).not.toBeInTheDocument();
  });

  it("donne à la direction le lien vers l’abonnement pendant la grâce", async () => {
    server(director, {
      state: "EXPIREE_GRACE", deadline: "2026-11-20", daysLeft: 17,
      message: "Licence expirée le 20/10/2026. Renouvelez-la : Ti’Cagou passera en lecture seule le 20/11/2026 (dans 17 jours).",
    });
    renderAt("/presences");
    expect(await screen.findByText(/passera en lecture seule le 20\/11\/2026/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Voir l’abonnement" })).toHaveAttribute("href", "/parametres?onglet=appareils");
  });
});
