import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { healthUp, installed, mockApi, renderAt, session } from "@/test/render";
import type { Me } from "@/features/auth/types";
import type { KioskJournal, KioskToday, Operation } from "./api";
import { entrySummary } from "./JournalScreen";

const maeva: Me = { id: "u-2", structureId: "s-1", firstName: "Maëva", lastName: "Tiaou", role: "EDUCATRICE", authMethod: "PIN", lastLoginAt: null, mfaEnabled: false, mfaEnrollmentRequired: false };
const day = new Date().toLocaleDateString("sv-SE", { timeZone: "Pacific/Noumea" });
const at = (time: string) => new Date(`${day}T${time}:00+11:00`).toISOString();
const health = { allergies: [], pai: false, paiReason: null, paiKitLocation: null, diet: null, dietDetails: null, instructions: null };

const child = (id: string, firstName: string, extra: Partial<KioskToday["children"][number]> = {}) => ({
  id, firstName, lastName: "Wahéo", birthDate: "2024-08-12", sectionId: "sec-m", planned: true, variablePlanning: false,
  expectedAt: "07:30:00", expectedLeaveAt: "16:30:00", childrenPerAdult: 8, health, pickup: [], parents: [],
  attendances: [{ id: `a-${id}`, arrivedAt: at("07:42"), departedAt: null, droppedOffBy: "Mère", pickedUpByName: null, instructions: [] }],
  ...extra,
});

const today: KioskToday = {
  serverTime: new Date().toISOString(),
  day,
  structureName: "Les P’tits Niaoulis",
  sections: [{ id: "sec-m", name: "Moyens", color: "#924510" }],
  children: [
    child("c-noa", "Noa"),
    child("c-lea", "Léa", { health: { ...health, allergies: [{ allergen: "arachide", severity: "SEVERE", reaction: null }] } }),
  ],
  staff: [{ userId: "u-2", displayName: "Maëva Tiaou", initials: "MT", role: "EDUCATRICE", jobTitle: "Éducatrice", homeSectionId: "sec-m",
    countsInRatio: true, hasPin: true, attendances: [{ id: "s-1", arrivedAt: at("06:55"), departedAt: null, sectionId: "sec-m" }] }],
  directors: [],
  fallbackChildrenPerAdult: 5,
};

const journal: KioskJournal = {
  entries: [],
  photoConsents: ["c-noa"],
  reportedAbsences: [{ childId: "c-lea", reason: "Fièvre", guardianName: "Sandrine Wahéo" }],
};

function server(extra: Parameters<typeof mockApi>[0] = {}) {
  window.localStorage.setItem("ticagou.device", JSON.stringify({ deviceId: "d-1", secret: "secret", name: "Accueil", structureName: "Les P’tits Niaoulis" }));
  return mockApi({
    "GET /setup/status": { body: installed },
    "GET /health": { body: healthUp },
    "POST /devices/session": { body: { accessToken: "device-1", expiresIn: 900, deviceId: "d-1", name: "Accueil", structureId: "s-1", structureName: "Les P’tits Niaoulis" } },
    "GET /auth/pin-users": { body: [{ id: "u-2", displayName: "Maëva T.", initials: "MT", role: "EDUCATRICE" }] },
    "POST /auth/pin": { body: { ...session(maeva, "refresh-pin"), sessionId: "session-1" } },
    "GET /kiosk/today": { body: today },
    "GET /kiosk/journal": { body: journal },
    ...extra,
    "POST /kiosk/operations": (body) => ({
      body: {
        serverTime: new Date().toISOString(),
        results: (body as { operations: Operation[] }).operations.map((op) => ({ clientId: op.clientId, outcome: "APPLIQUEE", code: null, message: null, needsReview: false })),
      },
    }),
  });
}

async function login() {
  await userEvent.click(await screen.findByText("Maëva T."));
  for (const digit of "4831") await userEvent.click(screen.getByRole("button", { name: digit }));
  await screen.findByRole("navigation", { name: "Sections" });
}

describe("onglet Journée de la tablette", () => {
  it("affiche les absences signalées par les parents sur la grille", async () => {
    server();
    renderAt("/kiosk");
    await login();
    expect((await screen.findByText(/Absence signalée par les parents/)).closest("[role=status]")).toHaveTextContent("Léa (Fièvre)");
  });

  it("enregistre un repas pour deux enfants par la file d’attente", async () => {
    const { calls } = server();
    renderAt("/kiosk");
    await login();
    await userEvent.click(screen.getByRole("button", { name: /Journée/ }));
    expect(await screen.findByRole("heading", { name: "Journée des enfants" })).toBeInTheDocument();

    const children = screen.getByRole("region", { name: /Pour qui/ });
    await userEvent.click(within(children).getByRole("button", { name: /Noa/ }));
    await userEvent.click(within(children).getByRole("button", { name: /Léa/ }));
    await userEvent.click(screen.getByRole("button", { name: "Repas" }));
    const save = screen.getByRole("button", { name: "Enregistrer" });
    expect(save).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "Déjeuner" }));
    await userEvent.click(screen.getByRole("button", { name: "Bien mangé" }));
    await userEvent.click(save);

    expect(await screen.findByText("Repas enregistré pour Noa, Léa.")).toBeInTheDocument();
    await waitFor(() => {
      const sent = calls.filter((c) => c.key === "POST /kiosk/operations").flatMap((c) => (c.body as { operations: Operation[] }).operations);
      const op = sent.find((o) => o.type === "TRANSMISSION");
      expect(op?.entry).toEqual({ kind: "REPAS", childIds: ["c-noa", "c-lea"], details: { meal: "DEJEUNER", quantity: "BIEN" }, text: null, shared: true });
      expect(op?.sessionId).toBe("session-1");
    });
  });

  it("note un biberon en millilitres, à une heure antérieure", async () => {
    const { calls } = server();
    renderAt("/kiosk");
    await login();
    await userEvent.click(screen.getByRole("button", { name: /Journée/ }));
    const children = await screen.findByRole("region", { name: /Pour qui/ });
    await userEvent.click(within(children).getByRole("button", { name: /Noa/ }));
    await userEvent.click(screen.getByRole("button", { name: "Repas" }));
    await userEvent.click(screen.getByRole("button", { name: "Biberon" }));
    await userEvent.click(screen.getByRole("button", { name: "Tout bu" }));
    await userEvent.type(screen.getByLabelText(/Quantité bue en ml/), "150");
    fireEvent.change(screen.getByLabelText("Heure"), { target: { value: "00:05" } });
    await userEvent.click(screen.getByRole("button", { name: "Enregistrer" }));

    await waitFor(() => {
      const sent = calls.filter((c) => c.key === "POST /kiosk/operations").flatMap((c) => (c.body as { operations: Operation[] }).operations);
      expect(sent.find((o) => o.type === "TRANSMISSION")?.entry).toEqual({
        kind: "REPAS", childIds: ["c-noa"], details: { meal: "BIBERON", quantity: "TOUT", volumeMl: "150" }, text: null, shared: true, time: "00:05",
      });
    });
  });

  it("note un médicament pour un seul enfant", async () => {
    const { calls } = server();
    renderAt("/kiosk");
    await login();
    await userEvent.click(screen.getByRole("button", { name: /Journée/ }));
    const children = await screen.findByRole("region", { name: /Pour qui/ });
    await userEvent.click(within(children).getByRole("button", { name: /Noa/ }));
    await userEvent.click(within(children).getByRole("button", { name: /Léa/ }));
    await userEvent.click(screen.getByRole("button", { name: "Médicament" }));
    await userEvent.type(screen.getByLabelText("Médicament donné"), "Doliprane");
    await userEvent.type(screen.getByLabelText(/Dose/), "2,5 ml");
    expect(screen.getByText(/Un médicament se note enfant par enfant/)).toBeInTheDocument();
    const save = screen.getByRole("button", { name: "Enregistrer" });
    expect(save).toBeDisabled();

    await userEvent.click(within(children).getByRole("button", { name: /Léa/ }));
    await userEvent.click(save);

    expect(await screen.findByText("Médicament enregistré pour Noa.")).toBeInTheDocument();
    await waitFor(() => {
      const sent = calls.filter((c) => c.key === "POST /kiosk/operations").flatMap((c) => (c.body as { operations: Operation[] }).operations);
      expect(sent.find((o) => o.type === "TRANSMISSION")?.entry).toMatchObject({ kind: "MEDICAMENT", childIds: ["c-noa"], details: { medicine: "Doliprane", dose: "2,5 ml" }, shared: true });
    });
  });

  it("refuse le partage d’une photo sans l’autorisation de chaque enfant", async () => {
    server();
    renderAt("/kiosk");
    await login();
    await userEvent.click(screen.getByRole("button", { name: /Journée/ }));
    const children = await screen.findByRole("region", { name: /Pour qui/ });
    await userEvent.click(within(children).getByRole("button", { name: /Léa/ }));
    await userEvent.click(screen.getByRole("button", { name: "Photo" }));
    expect(screen.getByRole("checkbox", { name: /Partager avec les familles/ })).toBeDisabled();
    expect(screen.getByText(/l’autorisation de photographier manque pour Léa/)).toBeInTheDocument();
  });

  it("filme une vidéo de 30 s au plus et l’envoie à part, partagée seulement si autorisée", async () => {
    class FakeRecorder {
      static isTypeSupported = (t: string) => t === "video/mp4";
      static last: FakeRecorder;
      mimeType = "video/mp4";
      ondataavailable: ((e: { data: Blob }) => void) | null = null;
      onstop: (() => void) | null = null;
      constructor(public stream: unknown, public options: MediaRecorderOptions) {
        FakeRecorder.last = this;
      }
      start() {}
      stop() {
        this.ondataavailable?.({ data: new Blob(["video"], { type: "video/mp4" }) });
        this.onstop?.();
      }
    }
    const stopTrack = vi.fn();
    vi.stubGlobal("MediaRecorder", FakeRecorder);
    Object.defineProperty(navigator, "mediaDevices", { configurable: true,
      value: { getUserMedia: vi.fn(async () => ({ getTracks: () => [{ stop: stopTrack }] })) } });
    URL.createObjectURL = vi.fn(() => "blob:video");
    URL.revokeObjectURL = vi.fn();
    const { calls } = server({
      "POST /kiosk/journal/video": { body: { clientId: "x", outcome: "APPLIQUEE", code: null, message: null, needsReview: false } },
    });
    renderAt("/kiosk");
    await login();
    await userEvent.click(screen.getByRole("button", { name: /Journée/ }));
    const children = await screen.findByRole("region", { name: /Pour qui/ });
    await userEvent.click(within(children).getByRole("button", { name: /Noa/ }));
    await userEvent.click(screen.getByRole("button", { name: "Vidéo" }));
    expect(screen.getByRole("checkbox", { name: /Partager avec les familles/ })).not.toBeChecked();
    await userEvent.click(screen.getByRole("button", { name: /Filmer \(30 s au plus\)/ }));
    expect(await screen.findByRole("timer")).toHaveTextContent("00 s / 30 s");
    expect(FakeRecorder.last.options).toMatchObject({ mimeType: "video/mp4", videoBitsPerSecond: 900_000 });
    await userEvent.click(screen.getByRole("button", { name: /Arrêter/ }));
    expect(await screen.findByRole("button", { name: /Refaire la vidéo/ })).toBeInTheDocument();
    expect(stopTrack).toHaveBeenCalled();
    await userEvent.click(screen.getByRole("checkbox", { name: /Partager avec les familles/ }));
    await userEvent.click(screen.getByRole("button", { name: "Enregistrer" }));
    expect(await screen.findByText("Vidéo enregistrée pour Noa.")).toBeInTheDocument();
    const form = calls.find((c) => c.key === "POST /kiosk/journal/video")?.body as FormData;
    const operation = JSON.parse(await (form.get("operation") as Blob).text());
    expect(operation.entry).toMatchObject({ kind: "VIDEO", childIds: ["c-noa"], shared: true });
    expect((form.get("video") as File).name).toBe("video.mp4");
    vi.unstubAllGlobals();
  });
});

describe("résumé d’une transmission", () => {
  it("décrit repas, sieste et change en clair", () => {
    expect(entrySummary({ kind: "REPAS", details: { meal: "GOUTER", quantity: "PEU" }, text: null })).toBe("Goûter · un peu");
    expect(entrySummary({ kind: "SIESTE", details: { from: "12:30", to: "14:05" }, text: null })).toBe("de 12:30 à 14:05");
    expect(entrySummary({ kind: "REPAS", details: { meal: "BIBERON", quantity: "TOUT", volumeMl: "150" }, text: null })).toBe("Biberon · 150 ml · tout bu");
    expect(entrySummary({ kind: "MEDICAMENT", details: { medicine: "Doliprane", dose: "2,5 ml" }, text: null })).toBe("Doliprane · 2,5 ml");
    expect(entrySummary({ kind: "CHANGE", details: { change: "SELLES" }, text: "RAS" })).toBe("Selles · RAS");
  });
});
