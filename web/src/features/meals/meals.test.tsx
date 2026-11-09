import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { director, healthUp, installed, mockApi, renderAt, session, structure } from "@/test/render";
import type { Me } from "@/features/auth/types";
import { todayIso } from "@/lib/age";
import { mondayOf, type KitchenDay, type MenuWeek, type Quarter } from "./api";

const day = todayIso();
const count = (name: string, lunches: number, snacks: number, extra: Partial<KitchenDay["total"]> = {}) => ({
  sectionId: name === "Total" ? null : `sec-${name}`, name, color: name === "Total" ? null : "#924510",
  lunches, snacks, packedLunches: 0, children: lunches, minced: 0, mashed: 0, leavingBeforeSnack: 0, ...extra,
});

const noa = {
  childId: "c-1", firstName: "Noa", lastName: "Wahéo", sectionId: "sec-Bébés", sectionName: "Bébés",
  allergies: ["Arachide"], pai: true, diet: null, texture: "MIXEE" as const, packedLunch: false,
  lunch: true, snack: true, present: true, planned: true, lunchBasis: "POINTAGE" as const, snackBasis: "PREVISION" as const,
  advice: ["Rien à remplacer aujourd’hui."], note: null, lunchAdjustment: null, snackAdjustment: null,
};
const jade = {
  ...noa, childId: "c-2", firstName: "Jade", lastName: "Rossi", sectionId: "sec-Grands", sectionName: "Grands",
  allergies: [], pai: false, diet: "Végétarien", texture: "NORMALE" as const,
  advice: ["Poulet au curry doux → Lentilles corail au curry."],
};

const kitchen: KitchenDay = {
  day, countedAt: new Date().toISOString(), structureName: "Les P’tits Niaoulis", lunchTime: "11:30:00", snackTime: "15:30:00",
  openDay: true, closedQuarter: false,
  sections: [count("Bébés", 10, 10, { mashed: 6, minced: 4 }), count("Grands", 16, 15, { leavingBeforeSnack: 1 })],
  total: count("Total", 26, 25, { children: 27, packedLunches: 1 }),
  toAdapt: [noa, jade],
  absentWithNeeds: [{ ...noa, childId: "c-3", firstName: "Kalani", lastName: "Wejieme", allergies: [], pai: false, diet: "Sans gluten" }],
  children: [noa, jade],
  menu: [
    { dayOfWeek: 1, course: "PLAT", dietId: null, label: "Poulet au curry doux, riz", tags: ["viande"] },
    { dayOfWeek: 1, course: "VARIANTE", dietId: "d-veg", label: "Lentilles corail au curry", tags: [] },
    { dayOfWeek: 1, course: "LAITAGE", dietId: null, label: "Yaourt nature", tags: ["lait"] },
  ],
  diets: { "d-veg": "Végétarien" },
};

const monday = mondayOf(day);
const week: MenuWeek = {
  weekStart: monday, weekEnd: monday, isoWeek: 45, status: "BROUILLON", saved: true, updatedAt: null, updatedBy: null,
  openDays: [1, 2, 3],
  items: [
    { dayOfWeek: 1, course: "PLAT", dietId: null, label: "Poulet au curry", tags: ["viande"] },
    { dayOfWeek: 3, course: "PLAT", dietId: null, label: "Rôti de porc, purée", tags: ["porc", "lait"] },
  ],
  diets: [{ dietId: "d-porc", label: "Sans porc", excludes: ["porc"], children: ["Maëlys Garnier"], days: [] }],
  warnings: [], version: 1,
};

const quarter: Quarter = {
  year: 2026, quarter: 3, label: "3e trimestre 2026", from: "2026-07-01", to: "2026-09-30", state: "A_CLORE",
  closedAt: null, closedBy: null,
  sections: [{ id: "sec-b", name: "Bébés", color: "#9e2f66" }, { id: "sec-m", name: "Moyens", color: "#924510" }],
  months: [
    { month: "2026-07", label: "Juillet 2026", openDays: 22, lunchesBySection: { "sec-b": 205, "sec-m": 284 }, lunches: 489, snacks: 470, packedLunches: 20 },
    { month: "2026-08", label: "Août 2026", openDays: 21, lunchesBySection: { "sec-b": 196, "sec-m": 268 }, lunches: 464, snacks: 444, packedLunches: 21 },
    { month: "2026-09", label: "Septembre 2026", openDays: 21, lunchesBySection: { "sec-b": 201, "sec-m": 279 }, lunches: 480, snacks: 471, packedLunches: 20 },
  ],
  total: { month: null, label: "Total du trimestre", openDays: 64, lunchesBySection: { "sec-b": 602, "sec-m": 831 }, lunches: 1433, snacks: 1385, packedLunches: 61 },
  averagePerDay: "22,4", checks: { monthsOver: true, toReview: 0, closed: false },
};

function server(user: Me = director, extra: Record<string, Parameters<typeof mockApi>[0][string]> = {}) {
  window.localStorage.setItem("ticagou.refreshToken", "refresh-1");
  return mockApi({
    "GET /setup/status": { body: installed },
    "GET /health": { body: healthUp },
    "POST /auth/refresh": { body: session(user) },
    "GET /structure": { body: structure },
    "GET /meals/kitchen": { body: kitchen },
    "GET /menus": { body: week },
    "GET /meals/quarters": { body: [{ year: 2026, quarter: 3, label: "3e trimestre 2026", state: "A_CLORE" }] },
    "GET /meals/quarters/2026/3": { body: quarter },
    ...extra,
  });
}

describe("vue cuisine du jour", () => {
  it("affiche les comptes, les textures et les enfants à adapter", async () => {
    server();
    renderAt("/repas");

    const bebes = await screen.findByRole("article", { name: "Section Bébés" });
    expect(within(bebes).getByText("10 repas")).toBeInTheDocument();
    expect(within(bebes).getByText("Mixé 6 · Haché 4")).toBeInTheDocument();
    expect(within(screen.getByRole("article", { name: "Section Grands" })).getByText("1 départ avant le goûter")).toBeInTheDocument();
    const total = screen.getByRole("article", { name: "Total à préparer" });
    expect(within(total).getByText("26 repas")).toBeInTheDocument();
    expect(within(total).getByText("dont 1 panier repas")).toBeInTheDocument();

    const toAdapt = screen.getByRole("table", { name: "Enfants à adapter" });
    expect(within(toAdapt).getByText("Allergie : arachide")).toBeInTheDocument();
    expect(within(toAdapt).getByText("Poulet au curry doux → Lentilles corail au curry.")).toBeInTheDocument();
    expect(screen.getByText(/Absent aujourd’hui, rien à préparer : Kalani Wejieme \(régime sans gluten\)/)).toBeInTheDocument();
    expect(screen.getByText("Végétarien : Lentilles corail au curry")).toBeInTheDocument();
    expect(screen.getByText("Contient : lait")).toBeInTheDocument();
  });

  it("corrige le goûter d’un enfant avec un motif", async () => {
    const { calls } = server(director, { "PUT /meals/adjustments": { body: kitchen } });
    renderAt("/repas");
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Corriger le goûter de Noa Wahéo" }));
    const dialog = screen.getByRole("dialog");
    await user.click(within(dialog).getByLabelText("Non servi"));
    await user.click(within(dialog).getByRole("button", { name: "Enregistrer la correction" }));
    expect(within(dialog).getByText("Indiquez le motif de la correction.")).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText("Motif"), "Parti avant le goûter");
    await user.click(within(dialog).getByRole("button", { name: "Enregistrer la correction" }));

    await waitFor(() =>
      expect(calls.find((c) => c.key === "PUT /meals/adjustments")?.body).toEqual({
        childId: "c-1", day, meal: "GOUTER", served: false, reason: "Parti avant le goûter",
      }),
    );
  });

  it("l’éducatrice consulte sans corriger", async () => {
    server({ ...director, role: "EDUCATRICE" });
    renderAt("/repas");
    expect(await screen.findByRole("article", { name: "Section Bébés" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Corriger le repas/ })).not.toBeInTheDocument();
  });
});

describe("menus de la semaine", () => {
  it("signale la variante manquante et enregistre la semaine", async () => {
    const { calls } = server(director, { [`PUT /menus/${monday}`]: { body: week } });
    renderAt("/repas/menus");
    const user = userEvent.setup();

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Mercredi : le plat contient porc et 1 enfant suit le régime « Sans porc » (Maëlys Garnier).");
    expect(screen.getByText("Variante manquante")).toBeInTheDocument();

    await user.type(screen.getByLabelText(/^Sans porc — mercredi/), "Rôti de dinde, purée");
    expect(screen.queryByText("Variante manquante")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Publier" }));

    await waitFor(() => expect(calls.some((c) => c.key === `PUT /menus/${monday}`)).toBe(true));
    const body = calls.find((c) => c.key === `PUT /menus/${monday}`)!.body as { status: string; items: unknown[] };
    expect(body.status).toBe("PUBLIE");
    expect(body.items).toContainEqual({ dayOfWeek: 3, course: "VARIANTE", dietId: "d-porc", label: "Rôti de dinde, purée", tags: [] });
    expect(await screen.findByText("Semaine publiée : la fiche cuisine reprend ces menus.")).toBeInTheDocument();
  });
});

describe("récapitulatif trimestriel", () => {
  it("affiche les repas par mois et clôt le trimestre", async () => {
    const { calls } = server(director, {
      "POST /meals/quarters/2026/3/close": { body: { ...quarter, state: "CLOS", closedAt: "2026-10-02T09:00:00Z", closedBy: "Nathalie Kaloï", checks: { ...quarter.checks, closed: true } } },
    });
    vi.spyOn(window, "confirm").mockReturnValue(true);
    renderAt("/repas/recapitulatif");
    const user = userEvent.setup();

    const table = await screen.findByRole("table", { name: /Repas et goûters délivrés par mois/ });
    const totalRow = within(table).getByRole("row", { name: /Total du trimestre/ });
    expect(totalRow).toHaveTextContent(/1\s433/);
    expect(screen.getByText("22,4 repas")).toBeInTheDocument();
    expect(screen.getByText("Aucun pointage à vérifier.")).toBeInTheDocument();
    expect(screen.getByText(/61 sur le trimestre/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Clore le trimestre" }));
    await waitFor(() => expect(calls.some((c) => c.key === "POST /meals/quarters/2026/3/close")).toBe(true));
    expect(await screen.findByRole("button", { name: "Rouvrir le trimestre" })).toBeInTheDocument();
    expect(screen.getAllByText("Trimestre clos").length).toBeGreaterThan(0);
  });

  it("refuse la clôture tant que des pointages restent à vérifier", async () => {
    server(director, { "GET /meals/quarters/2026/3": { body: { ...quarter, checks: { ...quarter.checks, toReview: 2 } } } });
    renderAt("/repas/recapitulatif");
    expect(await screen.findByText("2 pointages à vérifier dans Présences.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Clore le trimestre" })).toBeDisabled();
  });
});

describe("relevés de températures", () => {
  it("exige une mesure corrective hors seuil", async () => {
    const { calls } = server(director, {
      "GET /haccp/points": { body: [{ id: "p-1", label: "Frigo cuisine", kind: "FRIGO", minTenths: 0, maxTenths: 40, active: true }] },
      "GET /haccp/readings": { body: [] },
      "POST /haccp/readings": { status: 201, body: {} },
    });
    renderAt("/repas/temperatures");
    const input = await screen.findByLabelText("Température de Frigo cuisine");
    await userEvent.type(input, "6,2");
    const note = screen.getByRole("button", { name: "Noter" });
    expect(note).toBeDisabled();
    await userEvent.type(screen.getByLabelText("Mesure corrective"), "Porte refermée, produits jetés");
    await userEvent.click(note);
    await waitFor(() => expect(calls.find((c) => c.key === "POST /haccp/readings")?.body)
      .toEqual({ pointId: "p-1", tenths: 62, action: "Porte refermée, produits jetés" }));
  });
});
