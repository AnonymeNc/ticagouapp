import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { director, healthUp, installed, mockApi, renderAt, session, structure } from "@/test/render";
import type { Me } from "@/features/auth/types";
import { todayIso } from "@/lib/age";
import { addDays } from "@/lib/format";

const day = todayIso();
const sections = [
  { id: "sec-b", name: "Bébés", minAgeMonths: 2, maxAgeMonths: 18, capacity: 12, color: "#9e2f66", displayOrder: 0 },
  { id: "sec-m", name: "Moyens", minAgeMonths: 18, maxAgeMonths: 30, capacity: 16, color: "#924510", displayOrder: 1 },
];
const load = (children: number, adults: number, required: number, coveredPlaces: number, status: string) => ({
  children, adults, required, coveredPlaces, status, missingRule: false,
});
const staffing = {
  at: new Date().toISOString(),
  day,
  global: load(13, 2, 3, 15, "INSUFFISANT"),
  sections: [
    { sectionId: "sec-b", name: "Bébés", color: "#9e2f66", load: load(4, 1, 1, 5, "CONFORME"), staff: [{ userId: "u-5", displayName: "Mereana T.", initials: "MT" }] },
    { sectionId: "sec-m", name: "Moyens", color: "#924510", load: load(9, 1, 2, 8, "INSUFFISANT"), staff: [] },
  ],
  rules: [],
  alertDesktop: true,
};
const dayView = {
  day,
  expected: 15,
  children: [
    {
      id: "a-1", day, childId: "c-1", firstName: "Lila", lastName: "Gope", sectionId: "sec-m",
      arrivedAt: new Date(`${day}T07:42:00+11:00`).toISOString(), departedAt: null, droppedOffBy: "Mère",
      pickedUpByKind: null, pickedUpByName: null, unplanned: false, arrivalRecordedBy: "Maëva Tiaou", departureRecordedBy: null,
      source: "TABLETTE", needsReview: false, reviewReason: null, instructions: [], override: null, version: 0,
    },
  ],
  notArrived: [
    { childId: "c-2", firstName: "Noa", lastName: "Wahéo", sectionId: "sec-m", expectedAt: "23:30:00", late: false },
    { childId: "c-3", firstName: "Tiavai", lastName: "Tuheiava", sectionId: "sec-b", expectedAt: "07:30:00", late: true },
  ],
  staff: [],
  staffNotArrived: [],
};
const health = { allergies: [{ allergen: "Arachide", severity: "SEVERE", reaction: null }], pai: true, paiReason: null, paiKitLocation: "Placard de la cuisine", diet: null, dietDetails: null, instructions: null };
const kioskToday = {
  serverTime: new Date().toISOString(), day, structureName: "Les P’tits Niaoulis", sections: [], staff: [], directors: [], fallbackChildrenPerAdult: 8,
  children: [{
    id: "c-1", firstName: "Lila", lastName: "Gope", birthDate: "2024-08-01", sectionId: "sec-m", planned: true, variablePlanning: false,
    expectedAt: "07:30:00", expectedLeaveAt: "16:00:00", childrenPerAdult: 8, health, pickup: [], parents: [], attendances: [],
  }],
};
const journal = {
  day,
  closesAt: "17:30:00",
  absences: [{ childId: "c-4", name: "Kenzo Ataba", sectionId: "sec-b", reason: "Maladie", until: addDays(day, 2), notifiedOn: day, note: null }],
  departures: [{ childId: "c-1", name: "Lila Gope", sectionId: "sec-m", leavesAt: "16:00:00", present: true }],
  staffArrivals: [{ userId: "u-7", name: "Malia Tauhavili", sectionId: "sec-m", startsAt: "09:30:00", countsInRatio: true }],
};
const kpis = {
  month: "2026-11", fillRate: 873, reservedMinutes: 120_000, enrolled: 44, capacity: 48,
  invoicedMonth: "2026-10", invoiced: 4_120_000, invoicedChange: 42, invoices: 41, billableFamilies: 42,
  unpaid: 186_000, unpaidCount: 3, oldestUnpaid: "2026-09-01",
  waitingList: 5, waitingBySection: { "sec-b": 3, "sec-m": 2 }, newRequests: 2,
};
const collections = {
  collectedThisMonth: 0, collectedCount: 0, upcoming: 62_000, upcomingCount: 1, nextDueOn: null, overdue: 124_000, overdueCount: 2, overdueOver30: 1,
  unpaid: [{ id: "i-1" }, { id: "i-2" }, { id: "i-3" }], recentPayments: [],
};
const license = {
  state: "A_RENOUVELER", license: null, renewalFrom: null, pairedTablets: 1, sections: 2, capacity: 48, version: "dev",
  message: "La licence arrive à échéance le 30/11/2026 : pensez à la renouveler.",
};

function server(user: Me = director, extra: Record<string, Parameters<typeof mockApi>[0][string]> = {}) {
  window.localStorage.setItem("ticagou.refreshToken", "refresh-1");
  return mockApi({
    "GET /setup/status": { body: installed },
    "GET /health": { body: healthUp },
    "POST /auth/refresh": { body: session(user) },
    "GET /structure": { body: structure },
    "GET /sections": { body: sections },
    "GET /staffing": { body: staffing },
    "GET /attendance": { body: dayView },
    "GET /attendance/review": { body: { children: [], staff: [], refused: [] } },
    "GET /kiosk/today": { body: kioskToday },
    "GET /dashboard/today": { body: journal },
    "GET /dashboard/kpis": { body: kpis },
    "GET /staff/alerts": { body: [{ userId: "u-9", name: "Maëva Tiaou", kind: "QUALIFICATION", state: "A_RENOUVELER", label: "Sauveteur secouriste du travail (SST)", date: addDays(day, 12) }] },
    "GET /billing/collections": { body: collections },
    "GET /system/license": { body: license },
    ...extra,
  });
}

const kpi = (label: string) => screen.getByText(label, { selector: "dt" }).parentElement!;

describe("tableau de bord de la direction", () => {
  it("affiche les alertes avec leur action, dans l’ordre de gravité", async () => {
    server();
    renderAt("/");

    const ratio = (await screen.findByText("Encadrement insuffisant chez les Moyens.")).closest("article")!;
    expect(ratio).toHaveTextContent("9 enfants pour 1 adulte présent, 2 requis. Malia Tauhavili est attendue à 09:30.");
    expect(within(ratio).getByRole("link", { name: "Trouver un renfort" })).toHaveAttribute("href", "/personnel/planning");

    const allergy = (await screen.findByText("Allergie · enfant présent")).closest("article")!;
    expect(allergy).toHaveTextContent("Lila Gope (Moyens) est présent·e. Allergie : arachide. PAI, trousse d’urgence : Placard de la cuisine.");
    expect(within(allergy).getByRole("link", { name: "Voir la fiche" })).toHaveAttribute("href", "/enfants/c-1");

    const qualification = (await screen.findByText("Qualification à renouveler")).closest("article")!;
    expect(qualification).toHaveTextContent("Maëva Tiaou : SST expire le");
    expect(qualification).toHaveTextContent("dans 12 jours.");

    const unpaid = (await screen.findByText("3 factures impayées")).closest("article")!;
    expect(unpaid).toHaveTextContent(/186\s000\s*F au total, dont 1 facture de plus de 30 jours\./);
    expect(within(unpaid).getByRole("link", { name: "Relancer les familles" })).toHaveAttribute("href", "/facturation/encaissements");

    expect((await screen.findByText(/La licence arrive à échéance/)).closest("article")).toHaveTextContent("Abonnement");
    const cards = screen.getByRole("region", { name: "Alertes" }).querySelectorAll("article strong");
    expect([...cards].map((t) => t.textContent)).toEqual([
      "Encadrement insuffisant chez les Moyens.", "Allergie · enfant présent", "Qualification à renouveler", "3 factures impayées", "Abonnement",
    ]);
    expect(screen.getByRole("alert")).toBe(ratio);
  });

  it("alerte en premier quand aucune sauvegarde n’a réussi depuis 48 h", async () => {
    const lastSuccess = { id: "b-1", kind: "AUTOMATIQUE", author: null, startedAt: "2026-10-29T15:00:00Z", finishedAt: "2026-10-29T15:01:00Z", status: "REUSSIE", sizeBytes: 1000, fileName: null, copies: 1, detail: null };
    server(director, { "GET /backups": { body: { stale: true, lastSuccess, history: [lastSuccess] } } });
    renderAt("/");

    const card = (await screen.findByText("Aucune sauvegarde réussie depuis le 30/10/2026")).closest("article")!;
    expect(card).toHaveAttribute("role", "alert");
    expect(within(card).getByRole("link", { name: "Voir les sauvegardes" })).toHaveAttribute("href", "/parametres?onglet=sauvegardes");
    await screen.findByText("Encadrement insuffisant chez les Moyens.");
    expect(screen.getByRole("region", { name: "Alertes" }).querySelector("article strong")).toHaveTextContent("Aucune sauvegarde réussie");
  });

  it("montre l’encadrement avec les arrivées attendues et les présences du jour", async () => {
    server();
    renderAt("/");

    const moyens = await screen.findByRole("article", { name: "Section Moyens" });
    expect(await within(moyens).findByText("Malia Tauhavili · à 09:30")).toBeInTheDocument();
    expect(within(moyens).getByText("Noa Wahéo arrive à 23:30")).toBeInTheDocument();

    const presences = screen.getByRole("region", { name: "Présences du jour" });
    expect(within(presences).getByText("dont 1 en retard")).toBeInTheDocument();
    expect(await within(presences).findByText("Kenzo Ataba")).toBeInTheDocument();
    expect(within(presences).getByText(/maladie, jusqu’au/)).toBeInTheDocument();
    expect(within(presences).getByText("16:00")).toBeInTheDocument();
    for (const action of ["Pointer", "Nouvelle inscription", "Facturer le mois", "Signaler une absence"]) {
      expect(screen.getByRole("link", { name: action })).toBeInTheDocument();
    }
  });

  it("calcule les indicateurs du mois", async () => {
    server();
    renderAt("/");

    expect(await screen.findByRole("heading", { name: "Indicateurs de novembre" })).toBeInTheDocument();
    expect(kpi("Taux de remplissage")).toHaveTextContent("87,3 %");
    expect(kpi("Taux de remplissage")).toHaveTextContent("44 enfants inscrits pour 48 places");
    expect(kpi("CA facturé · octobre 2026")).toHaveTextContent(/4\s120\s000\s*F\+/);
    expect(kpi("CA facturé · octobre 2026")).toHaveTextContent("+ 4,2 % sur le mois précédent · 41 factures émises sur 42");
    expect(kpi("Impayés")).toHaveTextContent("3 factures impayées · la plus ancienne date du 01/09/2026");
    expect(kpi("Liste d’attente")).toHaveTextContent("5 demandes");
    expect(kpi("Liste d’attente")).toHaveTextContent("Bébés · 3 · Moyens · 2");
    expect(kpi("Liste d’attente")).toHaveTextContent("2 nouvelles demandes ce mois-ci");
  });

  it("ne montre à la comptabilité que les indicateurs et les impayés", async () => {
    const accountant: Me = { ...director, id: "u-3", firstName: "Sylvie", role: "COMPTABILITE" };
    const { calls } = server(accountant);
    renderAt("/");

    expect(await screen.findByRole("heading", { name: "Indicateurs de novembre" })).toBeInTheDocument();
    expect(screen.getByText("3 factures impayées")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Encadrement en temps réel" })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Présences du jour" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Pointer" })).not.toBeInTheDocument();
    expect(calls.some((c) => c.key === "GET /dashboard/today")).toBe(false);
    expect(calls.some((c) => c.key === "GET /kiosk/today")).toBe(false);
  });
});

describe("demandes des parents sur le tableau de bord", () => {
  const booking = (extra: Record<string, unknown>) => ({
    id: "b-1", childId: "c-1", childName: "Noa Wahéo", familyId: "f-1", kind: "RESERVATION", day: "2026-11-20",
    arrivesAt: "08:00:00", leavesAt: "12:00:00", note: "Rendez-vous", guardianName: "Sandrine Wahéo",
    requestedAt: "2026-11-10T00:00:00Z", status: "DEMANDEE", answer: null, overCapacity: false, freePlaces: 3, ...extra,
  });

  it("accepte une demande, et exige un motif quand la section est complète", async () => {
    const { calls } = server(director, {
      "GET /portal/reported-absences": { body: [] },
      "GET /bookings": { body: [
        booking({}),
        booking({ id: "b-2", childName: "Léa Kaouma", freePlaces: 0 }),
        booking({ id: "l-1", childName: "Kenzo Lefèvre", kind: "RETARD", arrivesAt: null, leavesAt: "17:45:00", note: null }),
      ] },
      "POST /bookings/b-1/accept": { body: booking({ status: "ACCEPTEE" }) },
      "POST /bookings/b-2/accept": { body: booking({ id: "b-2", status: "ACCEPTEE", overCapacity: true }) },
    });
    renderAt("/");
    const card = (await screen.findByRole("heading", { name: "Demandes des parents" })).closest("section")!;
    expect(within(card).getByText(/récupéré vers 17:45/)).toBeInTheDocument();
    expect(within(card).getByText("3 places libres")).toBeInTheDocument();
    expect(within(card).getByText("Section complète")).toBeInTheDocument();
    const [first, second] = within(card).getAllByRole("listitem").filter((li) => li.textContent?.includes("le 20/11/2026"));
    await userEvent.click(within(first).getByRole("button", { name: "Accepter" }));
    await waitFor(() => expect(calls.find((c) => c.key === "POST /bookings/b-1/accept")?.body).toEqual({ overrideReason: null }));
    await userEvent.click(within(second).getByRole("button", { name: /Accepter malgré tout/ }));
    const accept = within(second).getByRole("button", { name: "Accepter" });
    expect(accept).toBeDisabled();
    await userEvent.type(within(second).getByLabelText("Motif pour Léa Kaouma"), "Renfort ce matin-là");
    await userEvent.click(accept);
    await waitFor(() => expect(calls.find((c) => c.key === "POST /bookings/b-2/accept")?.body).toEqual({ overrideReason: "Renfort ce matin-là" }));
  });
});

describe("prise en main", () => {
  it("montre les premiers pas restants et l’aide avec le support", async () => {
    server(director, {
      "GET /getting-started": { body: [
        { key: "enfants", label: "Ajouter les enfants (ou reprendre un fichier Excel)", done: false, path: "/enfants" },
        { key: "tablette", label: "Appairer la tablette de l’accueil", done: true, path: "/parametres" },
      ] },
      "GET /support": { body: { phone: "27 12 34", whatsapp: "+687 77 12 34", email: null, hours: "Du lundi au vendredi, 7 h 30 – 17 h", delay: "Réponse sous 4 h ouvrées" } },
    });
    renderAt("/");
    const card = (await screen.findByRole("heading", { name: "Premiers pas" })).closest("section")!;
    expect(within(card).getByText("1 sur 2")).toBeInTheDocument();
    expect(within(card).getByRole("link", { name: /Ajouter les enfants/ })).toHaveAttribute("href", "/enfants");
    await userEvent.click(screen.getByRole("button", { name: "Aide" }));
    expect(await screen.findByRole("heading", { name: "Aide · Tableau de bord" })).toBeInTheDocument();
    expect(await screen.findByRole("link", { name: /WhatsApp/ })).toHaveAttribute("href", "https://wa.me/68777 1234".replace(" ", ""));
    expect(screen.getByText("Réponse sous 4 h ouvrées")).toBeInTheDocument();
  });
});
