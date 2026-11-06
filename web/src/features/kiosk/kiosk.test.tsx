import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { healthUp, installed, mockApi, renderAt, session } from "@/test/render";
import type { Me } from "@/features/auth/types";
import type { KioskToday, Operation } from "./api";
import { saveSealed } from "./secureStore";

const maeva: Me = {
  id: "u-2",
  structureId: "s-1",
  firstName: "Maëva",
  lastName: "Tiaou",
  role: "EDUCATRICE",
  authMethod: "PIN",
  lastLoginAt: null,
  mfaEnabled: false,
  mfaEnrollmentRequired: false,
};

const day = new Date().toLocaleDateString("sv-SE", { timeZone: "Pacific/Noumea" });
const at = (time: string) => new Date(`${day}T${time}:00+11:00`).toISOString();

const health = { allergies: [], pai: false, paiReason: null, paiKitLocation: null, diet: null, dietDetails: null, instructions: null };

const today: KioskToday = {
  serverTime: new Date().toISOString(),
  day,
  structureName: "Les P’tits Niaoulis",
  sections: [{ id: "sec-m", name: "Moyens", color: "#924510" }],
  children: [
    {
      id: "c-kenzo", firstName: "Kenzo", lastName: "Lefèvre", birthDate: "2024-09-02", sectionId: "sec-m",
      planned: true, variablePlanning: false, expectedAt: "23:59:00", expectedLeaveAt: "23:59:00", childrenPerAdult: 8,
      health: { ...health, allergies: [{ allergen: "arachide", severity: "SEVERE", reaction: "Aucun aliment qui en contient, même en traces." }], pai: true, paiKitLocation: "pharmacie de la section Moyens" },
      pickup: [], parents: [], attendances: [],
    },
    {
      id: "c-noa", firstName: "Noa", lastName: "Wahéo", birthDate: "2024-08-12", sectionId: "sec-m",
      planned: true, variablePlanning: false, expectedAt: "07:30:00", expectedLeaveAt: "16:30:00", childrenPerAdult: 8, health,
      pickup: [
        { kind: "RESPONSABLE", id: "g-1", name: "Sandrine Wahéo", relationship: "Mère", phone: "77 12 34", legalGuardian: true },
        { kind: "AUTORISEE", id: "p-1", name: "Odile Wahéo", relationship: "Grand-mère", phone: null, legalGuardian: false },
      ],
      parents: [{ name: "Sandrine Wahéo", relationship: "Mère", phone: "77 12 34" }],
      attendances: [{ id: "a-noa", arrivedAt: at("07:42"), departedAt: null, droppedOffBy: "Mère", pickedUpByName: null, instructions: [] }],
    },
  ],
  staff: [
    {
      userId: "u-2", displayName: "Maëva Tiaou", initials: "MT", role: "EDUCATRICE", jobTitle: "Éducatrice", homeSectionId: "sec-m",
      countsInRatio: true, hasPin: true, attendances: [{ id: "s-1", arrivedAt: at("06:55"), departedAt: null, sectionId: "sec-m" }],
    },
    {
      userId: "u-3", displayName: "Léa Kaouma", initials: "LK", role: "EDUCATRICE", jobTitle: "Éducatrice", homeSectionId: "sec-m",
      countsInRatio: true, hasPin: true, attendances: [],
    },
    {
      userId: "u-1", displayName: "Claire Hnawia", initials: "CH", role: "DIRECTION", jobTitle: "Directrice", homeSectionId: null,
      countsInRatio: false, hasPin: true, attendances: [],
    },
  ],
  directors: [{ userId: "u-1", displayName: "Claire Hnawia" }],
  fallbackChildrenPerAdult: 5,
};

const pinUsers = [
  { id: "u-2", displayName: "Maëva T.", initials: "MT", role: "EDUCATRICE" },
  { id: "u-4", displayName: "Paulo T.", initials: "PT", role: "CUISINE" },
];

function pair() {
  window.localStorage.setItem(
    "ticagou.device",
    JSON.stringify({ deviceId: "d-1", secret: "secret", name: "Accueil — entrée", structureName: "Les P’tits Niaoulis" }),
  );
}

function server(extra: Record<string, Parameters<typeof mockApi>[0][string]> = {}) {
  return mockApi({
    "GET /setup/status": { body: installed },
    "GET /health": { body: healthUp },
    "POST /devices/session": { body: { accessToken: "device-1", expiresIn: 900, deviceId: "d-1", name: "Accueil — entrée", structureId: "s-1", structureName: "Les P’tits Niaoulis" } },
    "GET /auth/pin-users": { body: pinUsers },
    "POST /auth/pin": (body) =>
      (body as { pin: string }).pin === "4831"
        ? { body: { ...session(maeva, "refresh-pin"), sessionId: "session-1" } }
        : { status: 401, body: { code: "bad_pin", detail: "Code PIN incorrect." } },
    "POST /auth/logout": { status: 204 },
    "GET /kiosk/today": { body: today },
    "POST /kiosk/operations": (body) => ({
      body: {
        serverTime: new Date().toISOString(),
        results: (body as { operations: Operation[] }).operations.map((op) =>
          op.override && op.override.directorPin !== "2580"
            ? { clientId: op.clientId, outcome: "REFUSEE", code: "bad_director_pin", message: "Code incorrect : le départ reste bloqué.", needsReview: false }
            : { clientId: op.clientId, outcome: "APPLIQUEE", code: null, message: null, needsReview: false },
        ),
      },
    }),
    ...extra,
  });
}

async function typeDigits(digits: string, group?: HTMLElement) {
  const scope = group ? within(group) : screen;
  for (const digit of digits) {
    await userEvent.click(scope.getByRole("button", { name: digit }));
  }
}

async function loginAsMaeva() {
  await userEvent.click(await screen.findByText("Maëva T."));
  await typeDigits("4831");
  return screen.findByRole("navigation", { name: "Sections" });
}

function sentOperations(calls: { key: string; body: unknown }[]) {
  return calls.filter((c) => c.key === "POST /kiosk/operations").flatMap((c) => (c.body as { operations: Operation[] }).operations);
}

describe("appairage et connexion", () => {
  it("demande le code d’appairage à une tablette inconnue", async () => {
    const { calls } = server({
      "POST /devices/pair": { body: { deviceId: "d-1", secret: "secret", name: "Accueil — entrée", structureName: "Les P’tits Niaoulis" } },
    });
    renderAt("/kiosk");

    expect(await screen.findByRole("heading", { name: "Appairer cette tablette" })).toBeInTheDocument();
    await typeDigits("482915", screen.getByRole("group", { name: "Code d’appairage" }));

    expect(await screen.findByRole("heading", { name: "Qui êtes-vous ?" })).toBeInTheDocument();
    expect(calls.find((c) => c.key === "POST /devices/pair")?.body).toEqual({ code: "482915" });
    expect(JSON.parse(window.localStorage.getItem("ticagou.device")!).deviceId).toBe("d-1");
  });

  it("s’appaire seule à l’ouverture du QR code et retire le code de l’adresse", async () => {
    const { calls } = server({
      "POST /devices/pair": { body: { deviceId: "d-2", secret: "secret", name: "Salle des Bébés", structureName: "Les P’tits Niaoulis" } },
    });
    const { router } = renderAt("/kiosk?pair=793723");

    expect(await screen.findByRole("heading", { name: "Qui êtes-vous ?" })).toBeInTheDocument();
    expect(calls.filter((c) => c.key === "POST /devices/pair").map((c) => c.body)).toEqual([{ code: "793723" }]);
    expect(router.state.location.search).toBe("");
  });

  it("ouvre la session par PIN puis permet de changer d’utilisateur", async () => {
    pair();
    const { calls } = server();
    renderAt("/kiosk");

    await userEvent.click(await screen.findByText("Maëva T."));
    expect(screen.getByRole("heading", { name: "Bonjour Maëva" })).toBeInTheDocument();
    await typeDigits("1111");
    expect(await screen.findByText("Code incorrect, réessayez.")).toBeInTheDocument();

    await typeDigits("4831");
    expect(await screen.findByRole("navigation", { name: "Sections" })).toBeInTheDocument();
    expect(calls.filter((c) => c.key === "POST /auth/pin").at(-1)?.body).toEqual({ userId: "u-2", pin: "4831" });
    // La tablette ne conserve jamais la session
    expect(window.localStorage.getItem("ticagou.refreshToken")).toBeNull();

    await userEvent.click(screen.getByRole("button", { name: /Changer d’utilisateur/ }));
    expect(await screen.findByRole("heading", { name: "Qui êtes-vous ?" })).toBeInTheDocument();
  });
});

describe("pointage des enfants", () => {
  it("enregistre l’arrivée avec une consigne de médicament", async () => {
    pair();
    const { calls } = server();
    renderAt("/kiosk");
    await loginAsMaeva();

    expect(screen.getByText(/présents \/ attendus/).textContent).toMatch(/1\s*\/ 2 présents \/ attendus/);
    expect(screen.getByRole("status", { name: /Encadrement conforme, 1 adulte · 1 requis/ })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /Kenzo Lefèvre, attendu/ }));

    expect(await screen.findByRole("heading", { name: "Arrivée de Kenzo enregistrée" })).toBeInTheDocument();
    expect(screen.getByText(/arachide\. Aucun aliment/)).toBeInTheDocument();
    expect(screen.getByText(/trousse d’urgence : pharmacie de la section Moyens/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Médicament à donner" }));
    await userEvent.type(screen.getByLabelText("Médicament et dose"), "Paracétamol sirop");
    await userEvent.click(screen.getByLabelText("Ordonnance remise par le parent"));
    await userEvent.click(screen.getByRole("button", { name: "Enregistrer les consignes" }));

    await waitFor(() => expect(sentOperations(calls).map((o) => o.type)).toEqual(["ARRIVEE", "CONSIGNES"]));
    const [arrival, instructions] = sentOperations(calls);
    expect(arrival).toMatchObject({ childId: "c-kenzo", userId: "u-2", sessionId: "session-1", offline: false });
    expect(instructions.attendanceId).toBe(arrival.attendanceId);
    expect(instructions.instructions).toEqual([
      expect.objectContaining({ kind: "MEDICAMENT", medicationDose: "Paracétamol sirop", prescriptionGiven: true }),
    ]);
  });

  it("bloque le départ avec une personne non autorisée jusqu’au code de la directrice", async () => {
    pair();
    const { calls } = server();
    renderAt("/kiosk");
    await loginAsMaeva();

    await userEvent.click(screen.getByRole("button", { name: /Noa Wahéo, présent depuis 07:42/ }));
    expect(await screen.findByRole("heading", { name: "Départ de Noa Wahéo" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: /Odile Wahéo/ })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("radio", { name: /Une autre personne/ }));
    await userEvent.click(screen.getByRole("button", { name: "Continuer avec une autre personne" }));

    expect(await screen.findByRole("heading", { name: "Personne NON autorisée" })).toBeInTheDocument();
    expect(screen.getByText("77 12 34")).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("Prénom et nom"), "Marc Tein");
    await userEvent.type(screen.getByLabelText("Lien avec Noa"), "Voisin, envoyé par la mère");
    await userEvent.click(screen.getByLabelText("Pièce d’identité présentée et vérifiée"));

    const pad = screen.getByRole("group", { name: "Code personnel de Claire Hnawia" });
    await typeDigits("1397", pad);
    expect(await within(pad).findByText("Code incorrect : le départ reste bloqué.")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Personne NON autorisée" })).toBeInTheDocument();

    await typeDigits("2580", pad);
    expect(await screen.findByRole("navigation", { name: "Sections" })).toBeInTheDocument();
    const departures = sentOperations(calls).filter((o) => o.type === "DEPART");
    expect(departures).toHaveLength(2);
    expect(departures[1]).toMatchObject({
      attendanceId: "a-noa",
      pickup: { kind: "AUTRE" },
      override: { personName: "Marc Tein", relationship: "Voisin, envoyé par la mère", idChecked: true, directorId: "u-1", directorPin: "2580" },
    });
  });
});

describe("hors connexion", () => {
  it("pointe le personnel par son nom et garde l’opération en attente d’envoi", async () => {
    pair();
    await saveSealed("ticagou.kiosk.today", today);
    vi.spyOn(globalThis, "fetch").mockRejectedValue(new TypeError("Failed to fetch"));
    renderAt("/kiosk");

    expect(await screen.findByText(/Internet ne répond pas/)).toBeInTheDocument();
    await userEvent.click(await screen.findByText("Maëva T."));
    await typeDigits("4831");
    await userEvent.click(await screen.findByRole("button", { name: /Pointage personnel/ }));

    await userEvent.click(screen.getByRole("button", { name: /^Léa Kaouma/ }));
    await typeDigits("7316", screen.getByRole("group", { name: "Code de Léa Kaouma" }));

    expect(await screen.findByText(/Arrivée de Léa Kaouma à \d\d:\d\d\. L’encadrement passe à 2 adultes\./)).toBeInTheDocument();
    expect(within(screen.getByRole("button", { name: /^Léa Kaouma/ })).getByText("En attente d’envoi")).toBeInTheDocument();
    expect(screen.getByText(/2 pointages en attente d’envoi/)).toBeInTheDocument();
  });
});
