import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { director, healthUp, installed, mockApi, renderAt, session, structure } from "@/test/render";
import type { Me } from "@/features/auth/types";
import type { ImportPreview } from "./api";

const clean: ImportPreview = {
  fileHash: "abc123",
  counts: { families: 1, guardians: 2, children: 2, allergies: 1, pais: 1, authorizedPersons: 1 },
  errors: [],
  warnings: [{ sheet: "Enfants", row: 3, message: "Aucune section : à compléter sur la fiche de l’enfant." }],
  children: [
    {
      reference: "E1", firstName: "Lila", lastName: "Gope", birthDate: "2024-03-15", section: "Moyens",
      allergies: [{ allergen: "Arachide", reaction: null, severity: "SEVERE" }], pai: true, paiReason: "Asthme", diet: "Sans porc",
      pickupPersons: ["Marie Gope (Mère)", "Tina Gope (Tante)"],
    },
    { reference: "E2", firstName: "Noa", lastName: "Gope", birthDate: "2025-08-20", section: null, allergies: [], pai: false, paiReason: null, diet: null, pickupPersons: ["Marie Gope (Mère)"] },
  ],
};
const withErrors: ImportPreview = {
  ...clean,
  counts: { families: 0, guardians: 0, children: 0, allergies: 0, pais: 0, authorizedPersons: 0 },
  errors: [{ sheet: "Allergies", row: 4, message: "Gravité : obligatoire." }],
  warnings: [],
  children: [],
};

function server(extra: Record<string, Parameters<typeof mockApi>[0][string]>, user: Me = director) {
  window.localStorage.setItem("ticagou.refreshToken", "refresh-1");
  return mockApi({
    "GET /setup/status": { body: installed },
    "GET /health": { body: healthUp },
    "POST /auth/refresh": { body: session(user) },
    "GET /structure": { body: structure },
    "GET /sections": { body: [] },
    ...extra,
  });
}

const file = new File(["contenu"], "reprise.xlsx", { type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" });

async function analyse() {
  const user = userEvent.setup();
  renderAt("/parametres?onglet=reprise");
  await user.upload(await screen.findByLabelText("Fichier de reprise"), file);
  await user.click(screen.getByRole("button", { name: "Analyser le fichier" }));
  return user;
}

describe("reprise des données", () => {
  it("la rubrique est réservée à la direction, sans module", async () => {
    server({ "GET /tariff-schedules": { body: [] } }, { ...director, role: "COMPTABILITE" });
    renderAt("/parametres");

    const nav = await screen.findByRole("navigation", { name: "Rubriques des paramètres" });
    expect(within(nav).queryByRole("button", { name: /^Reprise des données/ })).not.toBeInTheDocument();
  });

  it("montre les erreurs et n’offre pas l’import", async () => {
    server({ "POST /data-import/preview": { body: withErrors } });
    await analyse();

    expect(await screen.findByRole("heading", { name: "1 erreur à corriger" })).toBeInTheDocument();
    expect(screen.getByText("Allergies, ligne 4")).toBeInTheDocument();
    expect(screen.getByText(/Gravité : obligatoire/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Importer/ })).not.toBeInTheDocument();
  });

  it("l’import attend la relecture, puis envoie le fichier analysé", async () => {
    const { calls } = server({
      "POST /data-import/preview": { body: clean },
      "POST /data-import/commit": { body: clean.counts },
    });
    const user = await analyse();

    expect(await screen.findByText("Arachide : sévère")).toBeInTheDocument();
    expect(screen.getByText("PAI : Asthme")).toBeInTheDocument();
    expect(screen.getByText("Tina Gope (Tante)")).toBeInTheDocument();
    expect(screen.getByText(/Aucune section/)).toBeInTheDocument();
    const importer = screen.getByRole("button", { name: "Importer 2 enfants" });
    expect(importer).toBeDisabled();

    await user.click(screen.getByRole("checkbox", { name: /J’ai relu les allergies/ }));
    await user.click(importer);

    expect(await screen.findByText("Reprise terminée : 2 enfants et 1 famille importés.")).toBeInTheDocument();
    const sent = calls.find((c) => c.key === "POST /data-import/commit")?.body as FormData;
    expect(sent.get("fileHash")).toBe("abc123");
    expect(sent.get("healthReviewed")).toBe("true");
    expect((sent.get("file") as File).name).toBe("reprise.xlsx");
  });

  it("un nouveau fichier annule la relecture en cours", async () => {
    server({ "POST /data-import/preview": { body: clean } });
    const user = await analyse();
    await screen.findByText("Arachide : sévère");

    await user.upload(screen.getByLabelText("Fichier de reprise"), new File(["autre"], "autre.xlsx"));

    expect(screen.queryByRole("button", { name: /^Importer/ })).not.toBeInTheDocument();
  });

  it("rouvrir le choix du fichier oublie l’ancien, pour reprendre un fichier corrigé de même nom", async () => {
    server({ "POST /data-import/preview": { body: withErrors } });
    const user = await analyse();
    await screen.findByRole("heading", { name: "1 erreur à corriger" });

    await user.click(screen.getByLabelText("Fichier de reprise"));

    expect(screen.getByLabelText<HTMLInputElement>("Fichier de reprise").value).toBe("");
    expect(screen.getByRole("button", { name: "Analyser le fichier" })).toBeDisabled();
    expect(screen.queryByRole("heading", { name: "1 erreur à corriger" })).not.toBeInTheDocument();
  });

  it("affiche le refus du serveur", async () => {
    server({ "POST /data-import/preview": { status: 422, body: { code: "import_format", detail: "Ce fichier n’est pas un classeur Excel (.xlsx)." } } });
    await analyse();

    expect(await screen.findByRole("alert")).toHaveTextContent("Ce fichier n’est pas un classeur Excel (.xlsx).");
  });
});
