import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider, createMemoryRouter } from "react-router";
import { routes } from "./App";
import type { Family, Me } from "./api";

const family = (extra: Partial<Family> = {}): Family => ({
  structureId: "s-1", structureName: "Les P’tits Niaoulis", structurePhone: "27 12 34", paymentInfo: "Virement : IBAN NC00 1234",
  mealAbsenceCutoff: null, familyId: "f-1", guardianId: "g-1", firstName: "Claire",
  children: [{ id: "c-1", firstName: "Maëlys", lastInitial: "G", section: "Moyens", hasPhoto: false, sharedHealth: [] }],
  unreadMessages: 1, documentsToSign: 1, ...extra,
});

type Reply = { status?: number; body?: unknown } | ((body: unknown) => { status?: number; body?: unknown });

function mockApi(routes: Record<string, Reply>) {
  const calls: { key: string; body: unknown; headers: Record<string, string> }[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const url = new URL(String(input), "http://localhost");
    const key = `${init?.method ?? "GET"} ${url.pathname.replace(/^\/api\/v1/, "")}`;
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    calls.push({ key, body, headers: (init?.headers ?? {}) as Record<string, string> });
    const route = routes[key];
    const reply = typeof route === "function" ? route(body) : route;
    if (!reply) return new Response(JSON.stringify({ code: "not_found", detail: key }), { status: 404 });
    const status = reply.status ?? 200;
    return new Response(status === 204 ? null : JSON.stringify(reply.body ?? {}), { status, headers: { "Content-Type": "application/json" } });
  });
  return calls;
}

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

const me = (f: Family): Me => ({ email: "claire@mail.nc", families: [f] });
const empty = { "GET /me/children/s-1/c-1/day": { body: { day: "2026-11-02", items: [], days: [] } } };

describe("connexion", () => {
  it("sans session, envoie vers la connexion puis se connecte avec le code reçu", async () => {
    const calls = mockApi({
      "GET /me": { status: 401, body: { code: "unauthenticated", detail: "Connexion requise." } },
      "POST /auth/request": { status: 202, body: { message: "ok" } },
      "POST /auth/verify": { body: { email: "claire@mail.nc" } },
    });
    const router = renderAt("/");
    await userEvent.type(await screen.findByLabelText("Adresse e-mail"), "claire@mail.nc");
    await userEvent.click(screen.getByRole("button", { name: "Recevoir mon lien de connexion" }));
    await userEvent.type(await screen.findByLabelText("Code à 6 chiffres"), "123456");
    await userEvent.click(screen.getByRole("button", { name: "Se connecter" }));
    await waitFor(() => expect(router.state.location.pathname).toBe("/"));
    expect(calls.find((c) => c.key === "POST /auth/verify")?.body).toEqual({ email: "claire@mail.nc", code: "123456" });
    // Protection CSRF : toute écriture porte l'en-tête du portail.
    expect(calls.find((c) => c.key === "POST /auth/request")?.headers["X-Ticagou-Portail"]).toBe("1");
  });
});

describe("signaler une absence", () => {
  it("ne parle pas du repas quand la crèche n’a pas réglé d’heure limite", async () => {
    mockApi({ "GET /me": { body: me(family()) }, ...empty });
    renderAt("/absence");
    expect(await screen.findByRole("heading", { name: "Signaler une absence" })).toBeInTheDocument();
    expect(screen.queryByText(/repas du jour ne vous sera pas facturé/)).not.toBeInTheDocument();
  });

  it("annonce le repas non facturé avec l’heure réglée par la directrice", async () => {
    mockApi({ "GET /me": { body: me(family({ mealAbsenceCutoff: "09:00" })) }, ...empty });
    renderAt("/absence");
    expect(await screen.findByText(/Prévenez avant 09:00 : le repas du jour ne vous sera pas facturé/)).toBeInTheDocument();
  });

  it("prévient la crèche avec le motif et le mot", async () => {
    const calls = mockApi({
      "GET /me": { body: me(family()) },
      "POST /me/absences": (body) => ({ status: 201, body: { id: "a-1", type: "ABSENCE", at: null, data: body, hasFile: false } }),
    });
    renderAt("/absence");
    await userEvent.click(await screen.findByRole("button", { name: /Rendez-vous/ }));
    await userEvent.type(screen.getByLabelText(/Un mot pour l’équipe/), "Vaccin");
    await userEvent.click(screen.getByRole("button", { name: "Prévenir la crèche" }));
    expect(await screen.findByText(/la crèche est prévenue de l’absence de Maëlys/)).toBeInTheDocument();
    const sent = calls.find((c) => c.key === "POST /me/absences")?.body as Record<string, string>;
    expect(sent).toMatchObject({ structureId: "s-1", childId: "c-1", reason: "Rendez-vous — Vaccin" });
    expect(sent.fromDay).toBe(sent.toDay);
  });
});

describe("journée", () => {
  it("montre le fil du jour et la santé partagée par la crèche", async () => {
    mockApi({
      "GET /me": { body: me(family({ children: [{ id: "c-1", firstName: "Maëlys", lastInitial: "G", section: "Moyens", hasPhoto: false,
        sharedHealth: [{ label: "Allergie", text: "Arachide : PAI en place" }] }] })) },
      "GET /me/children/s-1/c-1/day": { body: { day: "2026-11-02", days: [], items: [
        { id: "i-1", type: "ATTENDANCE", at: "2026-11-01T20:42:00Z", hasFile: false, data: { day: "2026-11-02", arrivedAt: "2026-11-01T20:42:00Z", departedAt: null, childId: "c-1" } },
        { id: "i-2", type: "ENTRY", at: "2026-11-02T00:15:00Z", hasFile: false, data: { day: "2026-11-02", at: "2026-11-02T00:15:00Z", kind: "REPAS", text: "Riz, poisson", details: { meal: "DEJEUNER", quantity: "TOUT" } } },
        { id: "i-3", type: "ENTRY", at: "2026-11-02T01:20:00Z", hasFile: false, data: { day: "2026-11-02", at: "2026-11-02T01:20:00Z", kind: "SIESTE", text: null, details: { from: "12:20", to: "14:05" } } },
        { id: "i-4", type: "ENTRY", at: "2026-11-02T02:30:00Z", hasFile: false, data: { day: "2026-11-02", at: "2026-11-02T02:30:00Z", kind: "MEDICAMENT", text: null, details: { medicine: "Doliprane", dose: "2,5 ml" } } },
        { id: "i-5", type: "ENTRY", at: "2026-11-02T03:00:00Z", hasFile: false, data: { day: "2026-11-02", at: "2026-11-02T03:00:00Z", kind: "REPAS", text: null, details: { meal: "BIBERON", quantity: "TOUT", volumeMl: "150" } } },
      ] } },
    });
    renderAt("/");
    expect(await screen.findByRole("heading", { name: "La journée de Maëlys" })).toBeInTheDocument();
    expect(await screen.findByText("À la crèche")).toBeInTheDocument();
    expect(screen.getByText("Arrivée 07:42")).toBeInTheDocument();
    expect(screen.getByText("12:20 → 14:05, soit 1 h 45.")).toBeInTheDocument();
    expect(screen.getByText("Médicament donné")).toBeInTheDocument();
    expect(screen.getByText("Doliprane, 2,5 ml")).toBeInTheDocument();
    expect(screen.getAllByText("150 ml · tout bu").length).toBeGreaterThan(0);
    expect(screen.getByText(/Arachide : PAI en place/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Messages, 1 non lu/ })).toBeInTheDocument();
  });
});

describe("actualités et album", () => {
  const news = (id: string, structureId: string, title: string, file: boolean) => ({
    id, type: "NEWS", at: new Date().toISOString(), hasFile: file, fileType: file ? "application/pdf" : null, structureId, familyId: "f-1",
    data: { title, body: "Samedi 10 h, venez nombreux !", author: "Sandrine", publishedAt: new Date().toISOString(), expiresOn: null,
      fileName: file ? "menu-semaine.pdf" : null },
  });

  it("annonce la dernière actualité de la crèche sur la journée et mène à l’album", async () => {
    mockApi({
      "GET /me": { body: me(family()) }, ...empty,
      "GET /me/news": { body: [news("n-1", "s-1", "Fête du lagon", false), news("n-2", "autre", "Autre crèche", false)] },
      "GET /me/children/s-1/c-1/photos": { body: [{ id: "p-1", type: "ENTRY", at: "2026-11-02T01:00:00Z", hasFile: true,
        data: { day: "2026-11-02", at: "2026-11-02T01:00:00Z", kind: "PHOTO", text: "Atelier peinture", details: {} }, fileType: "image/jpeg" },
        { id: "v-1", type: "ENTRY", at: "2026-11-02T00:30:00Z", hasFile: true, fileType: "video/mp4",
        data: { day: "2026-11-02", at: "2026-11-02T00:30:00Z", kind: "VIDEO", text: "Premiers pas", details: {} } }] },
    });
    const router = renderAt("/");
    expect(await screen.findByRole("heading", { name: "Fête du lagon" })).toBeInTheDocument();
    expect(screen.queryByText("Autre crèche")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("link", { name: /Les photos de Maëlys/ }));
    await waitFor(() => expect(router.state.location.pathname).toBe("/album"));
    expect(await screen.findByRole("img", { name: "Atelier peinture" })).toHaveAttribute("src", "/api/v1/me/files/s-1/p-1");
    expect(screen.getByRole("link", { name: "Télécharger : Atelier peinture" })).toHaveAttribute("download");
    // Vidéo lue sur place, téléchargeable aussi.
    expect(screen.getByLabelText(/^Premiers pas/)).toHaveAttribute("src", "/api/v1/me/files/s-1/v-1");
    expect(screen.getByRole("link", { name: "Télécharger : Premiers pas" })).toHaveAttribute("download");
  });

  it("range les pièces jointes des actualités dans « De la crèche »", async () => {
    mockApi({
      "GET /me": { body: me(family()) },
      "GET /me/documents": { body: [] },
      "GET /me/news": { body: [news("n-1", "s-1", "Menu de la semaine", true), news("n-3", "s-1", "Sans pièce jointe", false)] },
    });
    renderAt("/documents");
    expect(await screen.findByRole("heading", { name: "De la crèche" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Menu de la semaine/ })).toHaveAttribute("href", "/api/v1/me/files/s-1/n-1");
    expect(screen.queryByText("Sans pièce jointe")).not.toBeInTheDocument();
  });
});

describe("notifications", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    delete (navigator as any).serviceWorker;
  });

  it("explique quand le navigateur ne permet pas les notifications", async () => {
    mockApi({ "GET /me": { body: me(family()) } });
    renderAt("/notifications");
    expect(await screen.findByText(/Ce navigateur ne permet pas les notifications/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Activer les notifications/ })).not.toBeInTheDocument();
  });

  it("abonne ce téléphone avec les catégories choisies, factures décochées par défaut", async () => {
    const subscription = {
      endpoint: "https://fcm.googleapis.com/fcm/send/abc",
      toJSON: () => ({ keys: { p256dh: "BPub", auth: "secret" } }),
      unsubscribe: vi.fn(async () => true),
    };
    let subscribed: typeof subscription | null = null;
    const pushManager = {
      getSubscription: vi.fn(async () => subscribed),
      subscribe: vi.fn(async () => (subscribed = subscription)),
    };
    vi.stubGlobal("PushManager", function PushManager() {});
    vi.stubGlobal("Notification", Object.assign(function Notification() {}, { permission: "default", requestPermission: vi.fn(async () => "granted") }));
    Object.defineProperty(navigator, "serviceWorker", { configurable: true, value: { getRegistration: async () => ({ pushManager }) } });
    const calls = mockApi({
      "GET /me": { body: me(family()) },
      "GET /me/push": { body: { publicKey: "BEl62iUYgUivxIkv69yViEuiBIa-Ib9-SkvMeAtA3LFgDzkrxZJjSgSnfckjBJuBkr3qBUYIHBQFLXYp5Nksh8U", categories: null } },
      "POST /me/push": { status: 204 },
    });
    renderAt("/notifications");
    const factures = await screen.findByRole("checkbox", { name: /Factures/ });
    expect(factures).not.toBeChecked();
    await userEvent.click(screen.getByRole("checkbox", { name: /Actualités/ }));
    await userEvent.click(screen.getByRole("button", { name: /Activer les notifications/ }));
    expect(await screen.findByText("Notifications activées sur ce téléphone.")).toBeInTheDocument();
    expect(pushManager.subscribe).toHaveBeenCalledWith(expect.objectContaining({ userVisibleOnly: true }));
    expect(calls.find((c) => c.key === "POST /me/push")?.body).toEqual({
      endpoint: "https://fcm.googleapis.com/fcm/send/abc", p256dh: "BPub", auth: "secret", categories: ["PHOTOS", "MESSAGES", "DOCUMENTS"],
    });
    expect(screen.getByRole("button", { name: /Désactiver sur ce téléphone/ })).toBeInTheDocument();
  });
});

describe("signature", () => {
  it("exige le tracé et la certification, puis envoie la réponse", async () => {
    mockApi({
      "GET /me": { body: me(family()) },
      "GET /me/documents": { body: [{ id: "d-1", type: "DOCUMENT", at: null, hasFile: true, structureId: "s-1", familyId: "f-1",
        data: { childId: "c-1", kind: "AUTORISATION", title: "Autorisation de sorties — Maëlys", sha256: "x", status: "A_SIGNER" } }] },
      "POST /me/documents/s-1/d-1/sign": { body: { status: "REFUSE" } },
    });
    renderAt("/documents/s-1/d-1");
    const send = await screen.findByRole("button", { name: "Signer l’autorisation" });
    expect(send).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "Je n’autorise pas" }));
    await userEvent.click(screen.getByRole("checkbox", { name: /Je certifie être responsable légal de Maëlys/ }));
    expect(screen.getByRole("button", { name: "Envoyer mon refus" })).toBeDisabled();
  });
});
