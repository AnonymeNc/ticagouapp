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
