import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { director, healthUp, installed, mockApi, renderAt, session, structure } from "@/test/render";
import type { PortalStatus } from "./api";

const shares = { entries: true, photos: true, invoices: true, documents: true, messages: true, absences: true, paymentInfo: null, mealAbsenceCutoff: null };
const off: PortalStatus = {
  moduleIncluded: true, state: "DESACTIVE", portalUrl: null, activatedAt: null, shares, lastSuccessAt: null, lastAttemptAt: null,
  lastError: null, pending: 0, invitedGuardians: 0,
};
const on: PortalStatus = { ...off, state: "ACTIF", portalUrl: "https://parents.ticagou.nc", activatedAt: "2026-11-02T00:00:00Z", pending: 3 };

function server(status: PortalStatus, extra: Record<string, Parameters<typeof mockApi>[0][string]> = {}) {
  window.localStorage.setItem("ticagou.refreshToken", "refresh-1");
  return mockApi({
    "GET /setup/status": { body: installed },
    "GET /health": { body: healthUp },
    "POST /auth/refresh": { body: session(director) },
    "GET /structure": { body: structure },
    "GET /sections": { body: [] },
    "GET /tariff-schedules": { body: [] },
    "GET /portal": { body: status },
    "GET /portal/documents": { body: [] },
    ...extra,
  });
}

const openPortal = async () => {
  renderAt("/parametres");
  await userEvent.click(await screen.findByRole("button", { name: /^Portail parents/ }));
};

describe("Paramètres › Portail parents", () => {
  it("active le portail avec l’adresse et le code de l’hébergeur", async () => {
    const { calls } = server(off, { "POST /portal/activate": { body: on } });
    await openPortal();
    await userEvent.type(await screen.findByLabelText(/Adresse du portail/), "https://parents.ticagou.nc");
    await userEvent.type(screen.getByLabelText(/Code d’activation/), "k7qm-2wxp-9htd");
    await userEvent.click(screen.getByRole("button", { name: "Activer le portail" }));
    await waitFor(() =>
      expect(calls.find((c) => c.key === "POST /portal/activate")?.body).toEqual({ portalUrl: "https://parents.ticagou.nc", activationCode: "K7QM-2WXP-9HTD" }),
    );
  });

  it("n’annonce le repas non facturé aux parents que si l’heure limite est réglée", async () => {
    const { calls } = server(on, { "PUT /portal/shares": (body) => ({ body: { ...on, shares: body } }) });
    await openPortal();
    const cutoff = await screen.findByLabelText(/Repas non facturé si l’absence est signalée avant/);
    expect(cutoff).toHaveValue("");
    await userEvent.click(screen.getByRole("button", { name: "Enregistrer" }));
    await waitFor(() => expect(calls.find((c) => c.key === "PUT /portal/shares")?.body).toMatchObject({ mealAbsenceCutoff: null }));

    await userEvent.type(cutoff, "09:00");
    await userEvent.click(screen.getByRole("button", { name: "Enregistrer" }));
    await waitFor(() =>
      expect(calls.filter((c) => c.key === "PUT /portal/shares").at(-1)?.body).toMatchObject({ mealAbsenceCutoff: "09:00" }),
    );
  });

  it("demande DÉSACTIVER avant d’effacer le portail", async () => {
    const { calls } = server(on, { "POST /portal/deactivate": { body: { sent: 1, received: 0, error: null, status: off } } });
    await openPortal();
    const section = (await screen.findByRole("heading", { name: "Désactiver le portail" })).closest("section")!;
    const button = within(section).getByRole("button", { name: "Désactiver et effacer" });
    expect(button).toBeDisabled();
    await userEvent.type(within(section).getByLabelText(/saisissez DÉSACTIVER/), "désactiver");
    await userEvent.click(button);
    await waitFor(() => expect(calls.find((c) => c.key === "POST /portal/deactivate")?.body).toEqual({ confirmation: "désactiver" }));
  });
});

describe("Messages › actualités et messages à plusieurs familles", () => {
  const moyens = { id: "sec-m", name: "Moyens", minAgeMonths: 18, maxAgeMonths: 30, capacity: 12, color: "#2a7", displayOrder: 2 };
  const published = {
    id: "n-1", sectionId: null, sectionName: null, title: "Menu de la semaine", body: "En pièce jointe.", fileName: "menu.pdf",
    publishedBy: "Nathalie Kowalski", publishedAt: "2026-11-02T04:00:00Z", expiresOn: null, withdrawnAt: null, status: "PUBLIEE", families: 12,
  };

  it("écrit à toutes les familles d’une section", async () => {
    const { calls } = server(on, {
      "GET /sections": { body: [moyens] },
      "GET /messages": { body: [] },
      "POST /messages/broadcast": { body: { families: 9 } },
    });
    renderAt("/messages");
    await userEvent.click(await screen.findByRole("button", { name: /Écrire à plusieurs familles/ }));
    await userEvent.selectOptions(screen.getByLabelText("Destinataires"), "sec-m");
    await userEvent.type(screen.getByLabelText("Message"), "Pensez aux chapeaux demain.");
    await userEvent.click(screen.getByRole("button", { name: "Envoyer" }));
    expect(await screen.findByText(/Message envoyé à 9 familles/)).toBeInTheDocument();
    expect(calls.find((c) => c.key === "POST /messages/broadcast")?.body).toEqual({ sectionId: "sec-m", body: "Pensez aux chapeaux demain." });
  });

  it("publie une actualité avec sa date de fin et la liste", async () => {
    const { calls } = server(on, {
      "GET /portal/news": { body: [published] },
      "POST /portal/news": { status: 201, body: { ...published, id: "n-2", title: "Fête du lagon", families: 3 } },
    });
    renderAt("/messages/actualites");
    expect(await screen.findByRole("heading", { name: "Menu de la semaine" })).toBeInTheDocument();
    expect(screen.getByText(/Toute la crèche · Nathalie Kowalski · 02\/11\/2026 · 12 familles/)).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("Titre"), "Fête du lagon");
    await userEvent.type(screen.getByLabelText("Texte"), "Samedi 10 h.");
    await userEvent.type(screen.getByLabelText(/Affichée jusqu’au/), "19/12/2026");
    await userEvent.click(screen.getByRole("button", { name: /Publier pour les parents/ }));
    expect(await screen.findByText(/Publiée : 3 familles la verront/)).toBeInTheDocument();
    const form = calls.find((c) => c.key === "POST /portal/news")?.body as FormData;
    expect(form.get("title")).toBe("Fête du lagon");
    expect(form.get("expiresOn")).toBe("2026-12-19");
    expect(form.get("sectionId")).toBeNull();
  });
});
