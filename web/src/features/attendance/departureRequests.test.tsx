import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { healthUp, installed, mockApi, renderAt, session, structure } from "@/test/render";
import { RemoteApproval } from "@/features/kiosk/RemoteApproval";

const kiosk = vi.hoisted(() => ({ online: true, refresh: vi.fn() }));
vi.mock("@/features/kiosk/KioskContext", () => ({
  useKiosk: () => ({ online: kiosk.online, session: { displayName: "Sandrine W." }, refresh: kiosk.refresh }),
}));

const pending = {
  id: "req-1", childId: "c-1", childName: "Lila Gope", sectionName: "Bébés", allergies: ["Arachide"], pai: false, diet: null,
  personName: "Joseph Gope", relationship: "Oncle", idChecked: true, requestedBy: "Sandrine W.", deviceName: "Accueil",
  requestedAt: "2026-11-16T22:38:00Z", status: "PENDING",
};

const server = (extra = {}) => ({
  "GET /setup/status": { body: installed },
  "GET /health": { body: healthUp },
  "POST /auth/refresh": { body: session() },
  "GET /structure": { body: structure },
  ...extra,
});

describe("départ bloqué à valider (maquettes B1 et B5)", () => {
  it("la direction voit la demande, la santé de l’enfant, et décide avec un motif", async () => {
    const { calls } = mockApi(server({
      "GET /attendance/departure-requests": { body: [pending] },
      "POST /attendance/departure-requests/req-1/decision": { body: { ...pending, status: "APPROVED" } },
    }));
    const user = userEvent.setup();
    renderAt("/presences");

    await user.click(await screen.findByRole("button", { name: /Départ à valider.*Lila Gope.*tablette Accueil/ }));
    const sheet = await screen.findByRole("dialog");
    expect(within(sheet).getByText("Arachide")).toBeInTheDocument();
    expect(within(sheet).getByText(/Joseph Gope, Oncle/)).toBeInTheDocument();
    // Sans motif, aucun bouton n’est actif.
    expect(within(sheet).getByRole("button", { name: "Autoriser" })).toBeDisabled();
    expect(within(sheet).getByRole("button", { name: "Refuser le départ" })).toBeDisabled();

    await user.type(within(sheet).getByRole("textbox"), "Le père a confirmé par téléphone");
    await user.click(within(sheet).getByRole("button", { name: "Autoriser" }));
    await waitFor(() => expect(calls.find((c) => c.key === "POST /attendance/departure-requests/req-1/decision")?.body)
      .toEqual({ approve: true, reason: "Le père a confirmé par téléphone" }));
  });

  it("une notification touchée ouvre directement la feuille de la demande", async () => {
    mockApi(server({ "GET /attendance/departure-requests": { body: [pending] } }));
    renderAt("/presences?depart=req-1");
    expect(await screen.findByRole("dialog")).toHaveTextContent("Départ bloqué");
  });

  it("rien à valider : pas de bandeau", async () => {
    mockApi(server({ "GET /attendance/departure-requests": { body: [] } }));
    renderAt("/presences");
    expect(await screen.findByRole("heading", { level: 1, name: "Présences" })).toBeInTheDocument();
    expect(screen.queryByText("Départ à valider")).toBeNull();
  });
});

describe("tablette : accord à distance", () => {
  const props = { attendanceId: "att-1", childFirstName: "Lila", personName: "Joseph Gope", relationship: "Oncle", idChecked: true };

  beforeEach(() => {
    kiosk.online = true;
    kiosk.refresh.mockClear();
  });

  it("envoie la demande, attend, puis passe la main quand la direction autorise", async () => {
    let status = "PENDING";
    const { calls } = mockApi({
      "POST /kiosk/departure-requests": { body: { id: "req-1", status: "PENDING", decidedBy: null, reason: null } },
      "GET /kiosk/departure-requests/req-1": () => ({ body: { id: "req-1", status, decidedBy: "Nathalie Kaloï", reason: "ok" } }),
    });
    const onApproved = vi.fn();
    render(<RemoteApproval {...props} onApproved={onApproved} />);
    await userEvent.setup().click(screen.getByRole("button", { name: /Demander son accord à distance/ }));

    expect(await screen.findByText("Demande envoyée à la direction")).toBeInTheDocument();
    expect(calls[0].body).toEqual({ attendanceId: "att-1", personName: "Joseph Gope", relationship: "Oncle", idChecked: true, requestedBy: "Sandrine W." });
    status = "APPROVED";
    await waitFor(() => expect(onApproved).toHaveBeenCalled(), { timeout: 8000 });
    expect(kiosk.refresh).toHaveBeenCalled();
  });

  it("affiche le refus et son motif ; l’enfant reste", async () => {
    mockApi({
      "POST /kiosk/departure-requests": { body: { id: "req-2", status: "PENDING", decidedBy: null, reason: null } },
      "GET /kiosk/departure-requests/req-2": { body: { id: "req-2", status: "REFUSED", decidedBy: "Nathalie Kaloï", reason: "Personne inconnue" } },
    });
    render(<RemoteApproval {...props} onApproved={vi.fn()} />);
    await userEvent.setup().click(screen.getByRole("button", { name: /Demander son accord à distance/ }));
    expect(await screen.findByText("Départ refusé par Nathalie Kaloï", {}, { timeout: 8000 })).toBeInTheDocument();
    expect(screen.getByText("« Personne inconnue »")).toBeInTheDocument();
  });

  it("hors connexion, seule la validation sur place reste possible", () => {
    kiosk.online = false;
    mockApi({});
    render(<RemoteApproval {...props} onApproved={vi.fn()} />);
    expect(screen.getByText(/Hors connexion/)).toBeInTheDocument();
    expect(screen.queryByRole("button")).toBeNull();
  });
});

describe("réglage des notifications", () => {
  it("dans le navigateur, renvoie vers l’application", async () => {
    mockApi(server());
    renderAt("/notifications");
    expect(await screen.findByRole("heading", { level: 1, name: "Notifications" })).toBeInTheDocument();
    expect(screen.getByText(/dans l’application Ti’Cagou Direction/)).toBeInTheDocument();
  });
});
