import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { director, healthUp, installed, mockApi, renderAt, session, structure } from "@/test/render";
import { todayIso } from "@/lib/age";
import type { DayView } from "./api";

const sections = [
  { id: "sec-b", name: "Bébés", minAgeMonths: 2, maxAgeMonths: 18, capacity: 12, color: "#9e2f66", displayOrder: 0 },
  { id: "sec-m", name: "Moyens", minAgeMonths: 18, maxAgeMonths: 30, capacity: 16, color: "#924510", displayOrder: 1 },
];
const day = todayIso();
const load = (children: number, adults: number, required: number, coveredPlaces: number, status: string) => ({
  children, adults, required, coveredPlaces, status, missingRule: false,
});

const staffing = {
  at: new Date().toISOString(),
  day,
  global: load(21, 3, 4, 18, "INSUFFISANT"),
  sections: [
    { sectionId: "sec-b", name: "Bébés", color: "#9e2f66", load: load(9, 2, 2, 10, "LIMITE"), staff: [{ userId: "u-5", displayName: "Mereana T.", initials: "MT" }] },
    { sectionId: "sec-m", name: "Moyens", color: "#924510", load: load(12, 1, 2, 8, "INSUFFISANT"), staff: [] },
  ],
  rules: ["1 adulte pour 5 enfants qui ne marchent pas", "1 adulte pour 8 enfants qui marchent"],
  alertDesktop: true,
};

const dayView: DayView = {
  day,
  expected: 2,
  children: [
    {
      id: "a-1", day, childId: "c-1", firstName: "Noa", lastName: "Wahéo", sectionId: "sec-m",
      arrivedAt: new Date(`${day}T07:42:00+11:00`).toISOString(), departedAt: null, droppedOffBy: "Mère",
      pickedUpByKind: null, pickedUpByName: null, unplanned: false, arrivalRecordedBy: "Maëva Tiaou", departureRecordedBy: null,
      source: "TABLETTE", needsReview: false, reviewReason: null, instructions: [], override: null, version: 0,
    },
  ],
  notArrived: [{ childId: "c-2", firstName: "Tiavai", lastName: "Tuheiava", sectionId: "sec-m", expectedAt: "08:30:00", late: true }],
  staff: [],
  staffNotArrived: [],
};

function server(extra: Record<string, Parameters<typeof mockApi>[0][string]> = {}) {
  window.localStorage.setItem("ticagou.refreshToken", "refresh-1");
  return mockApi({
    "GET /setup/status": { body: installed },
    "GET /health": { body: healthUp },
    "POST /auth/refresh": { body: session(director) },
    "GET /structure": { body: structure },
    "GET /sections": { body: sections },
    "GET /staffing": { body: staffing },
    "GET /attendance": { body: dayView },
    "GET /attendance/review": { body: { children: [], staff: [], refused: [] } },
    "GET /kiosk/today": { body: { children: [], staff: [], sections: [], directors: [] } },
    ...extra,
  });
}

describe("tableau de bord", () => {
  it("affiche l’encadrement par section et alerte en cas de dépassement", async () => {
    server();
    renderAt("/");

    expect(await screen.findByRole("heading", { level: 1, name: "Bonjour, Nathalie" })).toBeInTheDocument();
    expect((await screen.findByText(/Encadrement insuffisant chez les Moyens\./)).closest("article")).toHaveTextContent(
      "12 enfants pour 1 adulte présent, 2 requis.",
    );
    const moyens = screen.getByRole("article", { name: "Section Moyens" });
    expect(within(moyens).getByText("Insuffisant")).toBeInTheDocument();
    expect(within(moyens).getByText("12 sur 8")).toBeInTheDocument();
    const bebes = screen.getByRole("article", { name: "Section Bébés" });
    expect(within(bebes).getByText("À la limite")).toBeInTheDocument();
    expect(within(bebes).getByText("Mereana T.")).toBeInTheDocument();
    expect(screen.getByText(/règles : 1 adulte pour 5 enfants qui ne marchent pas/)).toBeInTheDocument();
    expect(screen.getByText("En retard")).toBeInTheDocument();
  });
});

describe("présences", () => {
  it("corrige une arrivée avec un motif obligatoire", async () => {
    const { calls } = server({ "PUT /attendance/children/records/a-1": { status: 204 } });
    renderAt("/presences");
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Corriger" }));
    const dialog = screen.getByRole("dialog");
    const arrival = within(dialog).getByLabelText("Arrivée");
    await user.clear(arrival);
    await user.type(arrival, "07:30");
    await user.click(within(dialog).getByRole("button", { name: "Enregistrer la correction" }));
    expect(within(dialog).getByText(/Indiquez le motif de la correction/)).toBeInTheDocument();

    await user.type(within(dialog).getByLabelText("Motif"), "Tablette pointée avec retard");
    await user.click(within(dialog).getByRole("button", { name: "Enregistrer la correction" }));
    await waitFor(() =>
      expect(calls.find((c) => c.key === "PUT /attendance/children/records/a-1")?.body).toEqual({
        arrivedAt: new Date(`${day}T07:30:00+11:00`).toISOString(),
        departedAt: null,
        sectionId: "sec-m",
        pickedUpBy: null,
        reason: "Tablette pointée avec retard",
      }),
    );
  });
});

describe("paramètres d’encadrement", () => {
  it("liste les règles datées et en ajoute une", async () => {
    const { calls } = server({
      "GET /tariff-schedules": { body: [] },
      "GET /ratio-rules": {
        body: [
          { id: "r-1", label: "Enfants qui ne marchent pas", minAgeMonths: 0, maxAgeMonths: 18, childrenPerAdult: 5, validFrom: "2024-01-01", validTo: null, state: "EN_VIGUEUR", version: 0 },
          { id: "r-2", label: "Enfants qui marchent", minAgeMonths: 18, maxAgeMonths: null, childrenPerAdult: 6, validFrom: "2027-01-01", validTo: null, state: "A_VENIR", version: 0 },
        ],
      },
      "GET /ratio-rules/settings": { body: { alertDesktop: true } },
      "POST /ratio-rules": { status: 201, body: {} },
    });
    renderAt("/parametres");
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: /^Règles d’encadrement/ }));
    const table = await screen.findByRole("table", { name: "Règles d’encadrement" });
    expect(within(table).getByText("1 adulte pour 6 enfants")).toBeInTheDocument();
    expect(within(table).getByText("À venir")).toBeInTheDocument();
    expect(within(table).getByText("De 0 à 18 mois")).toBeInTheDocument();
    expect(screen.getByLabelText("Prévenir la direction sur le PC du bureau en cas de dépassement")).toBeChecked();

    await user.click(screen.getByRole("button", { name: "Ajouter une règle" }));
    const dialog = screen.getByRole("dialog");
    await user.type(within(dialog).getByLabelText("Tranche d’âge"), "Passerelle");
    const min = within(dialog).getByLabelText("À partir de (mois)");
    await user.clear(min);
    await user.type(min, "36");
    const ratio = within(dialog).getByLabelText("Enfants par adulte");
    await user.clear(ratio);
    await user.type(ratio, "10");
    await user.click(within(dialog).getByRole("button", { name: "Enregistrer" }));

    await waitFor(() =>
      expect(calls.find((c) => c.key === "POST /ratio-rules")?.body).toEqual({
        label: "Passerelle", minAgeMonths: 36, maxAgeMonths: null, childrenPerAdult: 10, validFrom: day, validTo: null,
      }),
    );
  });
});
