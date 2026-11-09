import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { director, healthUp, installed, mockApi, renderAt, session, structure } from "@/test/render";
import type { Me } from "@/features/auth/types";
import { addDays } from "@/lib/format";
import { todayIso } from "@/lib/age";
import type { Board, RequestCard, RequestRecord } from "./api";

const sections = [
  { id: "sec-b", name: "Bébés", minAgeMonths: 2, maxAgeMonths: 18, capacity: 12, color: "#9e2f66", displayOrder: 0 },
  { id: "sec-m", name: "Moyens", minAgeMonths: 18, maxAgeMonths: 30, capacity: 16, color: "#924510", displayOrder: 1 },
];
const weekdays = [1, 2, 3, 4, 5].map((d) => ({ dayOfWeek: d, opensAt: "06:30:00", closesAt: "17:30:00" }));

const card = (overrides: Partial<RequestCard>): RequestCard => ({
  id: "r-1", childFirstName: "Hinano", childLastName: "Tein", birthDate: "2025-06-22", expectedBirthDate: null,
  sectionId: "sec-m", desiredStart: "2099-01-05", days: [1, 2, 4], stage: "NOUVELLE", receivedOn: "2026-10-14",
  stageChangedOn: "2026-10-14", followUpOn: null, visitAt: null, visitDone: false, offerExpiresOn: null,
  refusalReason: null, score: 5, tags: ["Fratrie déjà accueillie"], capacity: { verdict: "ACCEPTABLE", firstMonth: null, fullDays: [] },
  ...overrides,
});

const board: Board = {
  stats: { inProgress: 2, overdueFollowUps: 1, visitsThisWeek: 0, awaitingAnswers: 0 },
  requests: [
    card({}),
    card({
      id: "r-2", childFirstName: "Mathis", childLastName: "Leroy", stage: "A_RECONTACTER", tags: [],
      followUpOn: addDays(todayIso(), -5), capacity: { verdict: "LATER", firstMonth: "2099-02", fullDays: [2] },
    }),
    card({ id: "r-3", childFirstName: "Sacha", childLastName: "Bernard", stage: "REFUSEE", refusalReason: "Horaires hors amplitude d’ouverture", capacity: null }),
  ],
};

const record: RequestRecord = {
  ...card({}),
  source: "BUREAU",
  consentAt: null,
  answers: [],
  careType: "REGULIER", arrivesAt: "07:30:00", leavesAt: "16:30:00", guardianFirstName: "Vaïana", guardianLastName: "Tein",
  relationship: "Mère", phone: "77.12.34", email: "vaiana.tein@exemple.nc", familyId: null, familyLabel: null, siblings: [],
  childId: null, contracts: [], criteria: ["k-1"], availableCriteria: [
    { id: "k-1", label: "Fratrie déjà accueillie", points: 3, validFrom: "2020-01-01", validTo: null, displayOrder: 0 },
    { id: "k-2", label: "Parent seul", points: 2, validFrom: "2020-01-01", validTo: null, displayOrder: 1 },
  ],
  ranking: { rank: 2, of: 4 }, notes: [], history: [{ id: "e-1", label: "Demande reçue au bureau", author: "Nathalie Kaloï", createdAt: "2026-10-14T09:00:00Z" }],
  version: 2,
} as RequestRecord;

const capacityGrid = {
  months: ["2099-01", "2099-02"], openDays: [1, 2, 3, 4, 5],
  sections: [{ sectionId: "sec-m", name: "Moyens", color: "#924510", capacity: 16, days: [1, 2, 3, 4, 5].map((d) => ({ dayOfWeek: d, free: [d === 2 ? 0 : 2, 3] })) }],
};

function server(user: Me = director, extra: Record<string, unknown> = {}) {
  window.localStorage.setItem("ticagou.refreshToken", "refresh-1");
  return mockApi({
    "GET /setup/status": { body: installed },
    "GET /health": { body: healthUp },
    "POST /auth/refresh": { body: session(user) },
    "GET /structure": { body: { ...structure, openingHours: weekdays } },
    "GET /sections": { body: sections },
    "GET /enrollment-requests": { body: board },
    "GET /enrollment-requests/r-1": { body: record },
    "GET /capacity": { body: capacityGrid },
    ...(extra as Record<string, { body?: unknown; status?: number }>),
  });
}

describe("tableau des demandes", () => {
  it("range les demandes par étape avec places libres et relances", async () => {
    server();
    renderAt("/inscriptions");
    const user = userEvent.setup();

    const nouvelles = await screen.findByRole("region", { name: /Nouvelle demande/ });
    expect(await within(nouvelles).findByText("Hinano Tein")).toBeInTheDocument();
    expect(within(nouvelles).getByText("Place disponible")).toBeInTheDocument();
    expect(within(nouvelles).getByText("Fratrie déjà accueillie")).toBeInTheDocument();

    const relances = screen.getByRole("region", { name: /À recontacter/ });
    expect(within(relances).getByText("À relancer depuis 5 jours")).toBeInTheDocument();
    expect(within(relances).getByText("Possible dès février")).toBeInTheDocument();
    const refusees = screen.getByRole("region", { name: /Refusée/ });
    expect(within(refusees).getByText("Horaires hors amplitude d’ouverture")).toBeInTheDocument();
    expect(within(relances).getByText("Mathis Leroy")).toBeInTheDocument();

    await user.type(screen.getByLabelText("Rechercher une demande"), "hinano");
    expect(within(relances).queryByText("Mathis Leroy")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Nouvelle demande/ })).toHaveAttribute("href", "/inscriptions/nouvelle");
  });

  it("la comptabilité consulte sans créer de demande", async () => {
    server({ ...director, role: "COMPTABILITE" });
    renderAt("/inscriptions");

    expect(await screen.findByText("Hinano Tein")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /Nouvelle demande/ })).not.toBeInTheDocument();
    const nav = screen.getByRole("navigation", { name: "Navigation principale" });
    expect(within(nav).getByText("Inscriptions")).toBeInTheDocument();
  });

  it("l'éducatrice ne voit pas l'entrée Inscriptions", async () => {
    server({ ...director, role: "EDUCATRICE" });
    renderAt("/");

    const nav = await screen.findByRole("navigation", { name: "Navigation principale" });
    expect(within(nav).queryByText("Inscriptions")).not.toBeInTheDocument();
    expect(within(nav).queryByText("Paramètres")).not.toBeInTheDocument();
  });
});

describe("fiche demande", () => {
  it("affiche le score, le rang et les places libres", async () => {
    server();
    renderAt("/inscriptions/r-1");

    expect(await screen.findByRole("heading", { level: 1, name: "Hinano Tein" })).toBeInTheDocument();
    expect(screen.getByText(/Score 3/)).toHaveTextContent("2e sur 4 demandes");
    expect(await screen.findByRole("table", { name: /Places libres par mois/ })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Transformer en contrat/ })).toHaveAttribute("href", "/inscriptions/r-1/contrat");
  });

  it("refuse la demande avec un motif", async () => {
    const { calls } = server(director, { "PUT /enrollment-requests/r-1/stage": { status: 204 } });
    renderAt("/inscriptions/r-1");
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Refuser la demande" }));
    const dialog = screen.getByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Refuser la demande" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Indiquez le motif du refus.");

    await user.type(within(dialog).getByLabelText(/Motif du refus/), "Horaires hors amplitude");
    await user.click(within(dialog).getByRole("button", { name: "Refuser la demande" }));

    await waitFor(() =>
      expect(calls.find((c) => c.key === "PUT /enrollment-requests/r-1/stage")?.body).toMatchObject({
        stage: "REFUSEE", reason: "Horaires hors amplitude",
      }),
    );
  });
});

describe("transformer en contrat", () => {
  it("reprend la demande, calcule le forfait et crée le contrat", async () => {
    const quote = {
      tariffScheduleId: "t-1", tariffLabel: "Barème 2099", criterion: "HEURES_SEMAINE", weeksPerYear: 47,
      bands: [{ lowerBound: 0, hourlyRate: 620 }, { lowerBound: 1200, hourlyRate: 560 }, { lowerBound: 2115, hourlyRate: 510 }],
      bandLowerBound: 1200, hourlyRate: 560, weeklyMinutes: 1620, monthlyMinutes: 6345, monthlyFee: 59220,
      registrationFee: 10000, adaptationMinutes: 600, adaptationRate: 620, adaptationCost: 6200, billingMode: "MENSUALISE", problems: [],
    };
    const { calls } = server(director, {
      "POST /contracts/quote": { body: quote },
      "POST /capacity/test": { body: { verdict: "ACCEPTABLE", firstMonth: "2099-01", fullDays: [] } },
      "POST /contracts": { status: 201, body: { id: "c-1" } },
      "GET /contracts/c-1": { body: { id: "c-1", childId: "ch-1" } },
    });
    renderAt("/inscriptions/r-1/contrat");
    const user = userEvent.setup();

    expect(await screen.findByLabelText("Prénom de l’enfant")).toHaveValue("Hinano");
    expect(screen.getByLabelText("Date de naissance")).toHaveValue("22/06/2025");
    await user.click(screen.getByRole("button", { name: /Étape suivante : planning type/ }));

    expect(screen.getByLabelText(/Arrivée lundi/)).toHaveValue("07:30");
    expect(screen.getByLabelText("Mercredi")).not.toBeChecked();
    expect(screen.getByText("Total hebdomadaire").nextSibling).toHaveTextContent("27 h 00");
    await user.click(screen.getByRole("button", { name: /Étape suivante : tarification/ }));

    expect(await screen.findByText(/27 h 00 × 47 semaines ÷ 12 mois/)).toHaveTextContent(/= 59.220.F/);
    expect(screen.getByText("Appliqué")).toBeInTheDocument();
    // Mensualisé par défaut (Paramètres) ; au réel : 27 h × 560 F par semaine, 4 ou 5 semaines selon le mois.
    expect(screen.getByRole("radio", { name: /Mensualisé — 59.220.F chaque mois/ })).toBeChecked();
    expect(screen.getByRole("radio", { name: /Au réel — de 60.480.F à 75.600.F selon le mois/ })).not.toBeChecked();
    await user.click(screen.getByRole("radio", { name: /Au réel/ }));
    await user.click(screen.getByRole("button", { name: /Étape suivante : adaptation/ }));

    expect(screen.getByLabelText("Début du contrat")).toHaveValue("05/01/2099");
    expect(await screen.findByText("Place disponible")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /Étape suivante : récapitulatif/ }));
    await user.click(screen.getByRole("button", { name: "Créer le contrat" }));

    await waitFor(() => expect(calls.some((c) => c.key === "GET /contracts/c-1")).toBe(true));
    const body = calls.find((c) => c.key === "POST /contracts")?.body as Record<string, any>;
    expect(body.requestId).toBe("r-1");
    expect(body.newChild.child).toMatchObject({ firstName: "Hinano", birthDate: "2025-06-22", entryDate: "2099-01-05" });
    expect(body.newChild.family.guardian).toMatchObject({ firstName: "Vaïana", legalGuardian: true, payer: true });
    expect(body.contract.planning.days).toEqual([
      { dayOfWeek: 1, arrivesAt: "07:30", leavesAt: "16:30" },
      { dayOfWeek: 2, arrivesAt: "07:30", leavesAt: "16:30" },
      { dayOfWeek: 4, arrivesAt: "07:30", leavesAt: "16:30" },
    ]);
    expect(body.contract.endDate).toBe("2100-01-04");
    expect(body.contract.planning.billingMode).toBe("REEL");
    expect(body.contract.adaptation.length).toBeGreaterThan(0);
    expect(body.contract.adaptation.every((s: { date: string }) => s.date < "2099-01-05")).toBe(true);
  });
});

describe("pré-inscription en ligne", () => {
  const publicForm = {
    enabled: true, structureName: "Les P’tits Niaoulis", intro: "Bienvenue.", criteria: [{ id: "c-1", label: "Fratrie déjà accueillie" }],
    questions: [{ id: "q-1", label: "Comment nous avez-vous connus ?", kind: "CHOIX", options: ["Internet", "Bouche-à-oreille"], required: true,
      displayOrder: 1, validFrom: "2026-11-01", validTo: null }],
  };

  it("dépose une demande sans compte, avec ses réponses et le consentement", async () => {
    const { calls } = mockApi({
      "GET /public/preinscription": { body: publicForm },
      "POST /public/preinscription": { status: 202, body: { message: "Votre demande est bien envoyée : la crèche vous recontactera." } },
    });
    renderAt("/preinscription");
    expect(await screen.findByRole("heading", { name: /Pré-inscription — Les P’tits Niaoulis/ })).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("Prénom"), "Lou");
    await userEvent.type(screen.getByLabelText("Nom"), "Tein");
    await userEvent.type(screen.getByLabelText(/Date de naissance/), "12/03/2025");
    await userEvent.type(screen.getByLabelText(/Entrée souhaitée/), "05/01/2027");
    await userEvent.click(screen.getByRole("button", { name: "Lundi" }));
    await userEvent.click(screen.getByRole("button", { name: "Jeudi" }));
    await userEvent.type(screen.getByLabelText("Votre prénom"), "Vaïana");
    await userEvent.type(screen.getByLabelText("Votre nom"), "Tein");
    await userEvent.type(screen.getByLabelText("Téléphone"), "77.12.34");
    await userEvent.click(screen.getByRole("checkbox", { name: "Fratrie déjà accueillie" }));
    await userEvent.click(screen.getByRole("button", { name: "Envoyer ma demande" }));
    expect(await screen.findByText(/Répondez à la question : Comment nous avez-vous connus/)).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText("Comment nous avez-vous connus ?"), "Internet");
    await userEvent.click(screen.getByRole("button", { name: "Envoyer ma demande" }));
    expect(await screen.findByText(/Cochez la case de consentement/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("checkbox", { name: /J’accepte que la crèche enregistre/ }));
    await userEvent.click(screen.getByRole("button", { name: "Envoyer ma demande" }));
    expect(await screen.findByText(/Votre demande est bien envoyée/)).toBeInTheDocument();
    const sent = calls.find((c) => c.key === "POST /public/preinscription")?.body as Record<string, unknown>;
    expect(sent).toMatchObject({
      consent: true, website: "", answers: { "q-1": "Internet" },
      request: { childFirstName: "Lou", birthDate: "2025-03-12", desiredStart: "2027-01-05", days: [1, 4], criteria: ["c-1"], phone: "77.12.34" },
    });
    expect(typeof sent.startedAt).toBe("number");
  });

  it("indique que la crèche ne reçoit pas de demande en ligne quand le formulaire est fermé", async () => {
    mockApi({ "GET /public/preinscription": { body: { ...publicForm, enabled: false, criteria: [], questions: [] } } });
    renderAt("/preinscription");
    expect(await screen.findByText(/ne reçoit pas de pré-inscription en ligne/)).toBeInTheDocument();
  });
});
