import { helpFor } from "./HelpButton";

describe("aide de chaque écran", () => {
  it("choisit l’aide de l’écran ouvert, sinon celle du tableau de bord", () => {
    expect(helpFor("/").title).toBe("Tableau de bord");
    expect(helpFor("/repas/temperatures").title).toBe("Repas");
    expect(helpFor("/personnel/mon-espace").title).toBe("Personnel");
    expect(helpFor("/inconnu").title).toBe("Tableau de bord");
  });
});
