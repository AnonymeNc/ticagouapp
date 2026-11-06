import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { director, healthUp, installed, mockApi, renderAt, session, structure } from "@/test/render";
import type { Me } from "@/features/auth/types";
import type { ChildListItem, ChildRecord } from "./api";

const sections = [
  { id: "sec-b", name: "Bébés", minAgeMonths: 2, maxAgeMonths: 18, capacity: 12, color: "#9e2f66", displayOrder: 0 },
  { id: "sec-m", name: "Moyens", minAgeMonths: 18, maxAgeMonths: 30, capacity: 16, color: "#924510", displayOrder: 1 },
];

const noaItem: ChildListItem = {
  id: "c-1", fileNumber: "ENF-2025-001", firstName: "Noa", lastName: "Wahéo", birthDate: "2024-07-22",
  sectionId: "sec-m", entryDate: "2025-02-01", exitDate: null, hasPhoto: false, familyId: "f-1",
  health: { allergies: [{ allergen: "Arachide", severity: "SEVERE" }], pai: true, diet: "Sans porc" },
};
const kainaItem: ChildListItem = {
  ...noaItem, id: "c-2", fileNumber: "ENF-2025-002", firstName: "Kaïna", lastName: "Wamytan", birthDate: "2025-03-02",
  sectionId: "sec-b", familyId: "f-2", health: { allergies: [], pai: false, diet: null },
};

const noaRecord: ChildRecord = {
  id: "c-1", fileNumber: "ENF-2025-001", firstName: "Noa", lastName: "Wahéo", birthDate: "2024-07-22",
  sectionId: "sec-m", entryDate: "2025-02-01", exitDate: null, homeLanguages: "Français, drehu", hasPhoto: false, version: 3,
  family: {
    id: "f-1", label: "Famille Wahéo", address: "12 allée des Flamboyants", postalCode: "98800", city: "Nouméa",
    situation: null, notes: null, version: 0,
    guardians: [{ id: "g-1", firstName: "Sarah", lastName: "Wahéo", relationship: "Mère", legalGuardian: true, payer: true,
      mobilePhone: "77 12 34", workPhone: null, email: "sarah@exemple.nc", employer: null, displayOrder: 0 }],
    children: [{ id: "c-1", firstName: "Noa", lastName: "Wahéo", birthDate: "2024-07-22", exitDate: null }],
  },
  authorizedPersons: [{ id: "p-1", firstName: "Joseph", lastName: "Poédi", relationship: "Oncle maternel", phone: "78 90 13",
    idVerifiedOn: null, idVerifiedBy: null, hasPhoto: false }],
  health: {
    diet: "Sans porc", dietDetails: "Plateau identifié par une étiquette.", paiActive: true, paiReason: "Allergie alimentaire sévère",
    paiValidFrom: "2026-08-28", paiValidUntil: "2027-08-31", paiKitLocation: "pharmacie Moyens", paiKitDetails: null,
    paiDocumentId: null, emergencyProtocol: "Suivre le protocole du PAI, appeler le 15", doctorName: null, doctorPhone: null,
    doctorAddress: null, instructions: null, vaccinationCheckedOn: null,
    allergies: [{ allergen: "Arachide", reaction: "Gonflement du visage", severity: "SEVERE" }], vaccinations: [],
    dietId: null, mealTexture: "NORMALE", packedLunch: false,
  },
  consents: [],
  documents: [],
};

function server(user: Me = director, extra: Record<string, unknown> = {}) {
  window.localStorage.setItem("ticagou.refreshToken", "refresh-1");
  return mockApi({
    "GET /setup/status": { body: installed },
    "GET /health": { body: healthUp },
    "POST /auth/refresh": { body: session(user) },
    "GET /structure": { body: structure },
    "GET /sections": { body: sections },
    "GET /children": { body: [noaItem, kainaItem] },
    "GET /children/c-1": { body: noaRecord },
    ...(extra as Record<string, { body?: unknown; status?: number }>),
  });
}

describe("liste des enfants", () => {
  it("affiche la santé critique de chaque enfant et filtre localement", async () => {
    server();
    renderAt("/enfants");
    const user = userEvent.setup();

    const table = await screen.findByRole("table", { name: "Liste des enfants inscrits" });
    const noaRow = (await within(table).findByText("Noa")).closest("tr")!;
    expect(within(noaRow).getByText("Allergie : Arachide")).toBeInTheDocument();
    expect(within(noaRow).getByText("PAI")).toBeInTheDocument();
    expect(within(noaRow).getByText("Sans porc")).toBeInTheDocument();
    expect(within(table).getByText("Rien à signaler")).toBeInTheDocument();

    await user.type(screen.getByLabelText("Rechercher un enfant"), "kaina");
    expect(within(table).queryByText("Noa")).not.toBeInTheDocument();
    expect(within(table).getByText("Kaïna")).toBeInTheDocument();

    await user.clear(screen.getByLabelText("Rechercher un enfant"));
    await user.click(screen.getByLabelText(/Avec allergie/));
    expect(within(table).getByText("Noa")).toBeInTheDocument();
    expect(within(table).queryByText("Kaïna")).not.toBeInTheDocument();
    expect(screen.getByText("1 enfant affiché")).toHaveAttribute("role", "status");
  });

  it("la cuisine voit les allergies mais pas les fiches ni la création", async () => {
    server({ ...director, role: "CUISINE", firstName: "Paulo", lastName: "Taufana" });
    renderAt("/enfants");

    const table = await screen.findByRole("table", { name: "Liste des enfants inscrits" });
    expect(await within(table).findByText("Allergie : Arachide")).toBeInTheDocument();
    expect(within(table).queryByRole("link")).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /Nouvel enfant/ })).not.toBeInTheDocument();
  });

  it("la comptabilité ne voit pas l'entrée Enfants", async () => {
    server({ ...director, role: "COMPTABILITE" });
    renderAt("/");

    const nav = await screen.findByRole("navigation", { name: "Navigation principale" });
    expect(within(nav).queryByText("Enfants")).not.toBeInTheDocument();
    expect(within(nav).getByText("Facturation")).toBeInTheDocument();
  });
});

describe("fiche enfant", () => {
  it("affiche la santé critique au-dessus des onglets", async () => {
    server();
    renderAt("/enfants/c-1");

    const banner = await screen.findByRole("region", { name: /Santé : à connaître avant toute prise en charge/ });
    expect(within(banner).getByText("Allergie sévère")).toBeInTheDocument();
    expect(within(banner).getByText("Arachide")).toBeInTheDocument();
    expect(within(banner).getByText("PAI actif")).toBeInTheDocument();
    expect(within(banner).getByText("Suivre le protocole du PAI, appeler le 15")).toBeInTheDocument();
    expect(within(banner).getByText(/prévenir Sarah Wahéo au 77 12 34/)).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Noa Wahéo");
    expect(screen.getByRole("tab", { name: /Santé/ })).toHaveTextContent("(alertes santé)");
  });

  it("navigue entre les onglets et signale la pièce à vérifier", async () => {
    server();
    renderAt("/enfants/c-1");
    const user = userEvent.setup();

    await user.click(await screen.findByRole("tab", { name: /Personnes autorisées/ }));
    expect(screen.getByText("À vérifier au premier passage")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Voir l’onglet Santé" }));
    expect(screen.getByRole("tab", { name: /Santé/ })).toHaveAttribute("aria-selected", "true");
    expect(within(screen.getByRole("tabpanel")).getByText("Gonflement du visage")).toBeInTheDocument();
  });

  it("enregistre les informations de santé", async () => {
    const { calls } = server(director, { "PUT /children/c-1/health": { body: noaRecord.health } });
    renderAt("/enfants/c-1");
    const user = userEvent.setup();

    await user.click(await screen.findByRole("tab", { name: /Santé/ }));
    await user.click(screen.getByRole("button", { name: "Modifier les informations de santé" }));
    await user.click(screen.getByRole("button", { name: "Ajouter une allergie" }));
    await user.type(screen.getAllByLabelText("Allergène")[1], "Kiwi");
    await user.selectOptions(screen.getAllByLabelText("Gravité")[1], "LEGERE");
    await user.click(screen.getByRole("button", { name: "Enregistrer" }));

    const body = calls.find((c) => c.key === "PUT /children/c-1/health")?.body as { allergies: unknown[]; paiValidUntil: string };
    expect(body.allergies).toEqual([
      { allergen: "Arachide", reaction: "Gonflement du visage", severity: "SEVERE" },
      { allergen: "Kiwi", reaction: null, severity: "LEGERE" },
    ]);
    expect(body.paiValidUntil).toBe("2027-08-31");
  });

  it("la suppression des données exige le nom de la famille", async () => {
    const { calls } = server(director, { "POST /families/f-1/purge": { status: 204 } });
    renderAt("/enfants/c-1");
    const user = userEvent.setup();

    await user.click(await screen.findByRole("tab", { name: "Famille" }));
    await user.click(screen.getByRole("button", { name: /Supprimer définitivement…/ }));
    const dialog = screen.getByRole("dialog");
    const confirm = within(dialog).getByRole("button", { name: "Supprimer définitivement" });
    expect(confirm).toBeDisabled();

    await user.type(within(dialog).getByLabelText(/Pour confirmer/), "famille wahéo");
    await user.click(confirm);

    expect(await screen.findByRole("heading", { name: "Enfants" })).toBeInTheDocument();
    expect(calls.find((c) => c.key === "POST /families/f-1/purge")?.body).toEqual({ confirmation: "famille wahéo" });
  });

  it("l'éducatrice consulte sans pouvoir modifier", async () => {
    server({ ...director, role: "EDUCATRICE" });
    renderAt("/enfants/c-1");

    expect(await screen.findByRole("heading", { level: 1 })).toHaveTextContent("Noa");
    expect(screen.queryByRole("button", { name: /Modifier la fiche/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Changer la photo/ })).not.toBeInTheDocument();
  });
});

describe("nouvel enfant", () => {
  it("crée l'enfant et sa famille puis ouvre la fiche", async () => {
    const { calls } = server(director, { "POST /children": { status: 201, body: { id: "c-1" } } });
    renderAt("/enfants/nouveau");
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText("Prénom"), "Noa");
    await user.type(screen.getByLabelText("Nom"), "Wahéo");
    await user.type(screen.getByLabelText("Date de naissance"), "22/07/2024");
    await user.selectOptions(screen.getByLabelText("Section"), "sec-m");
    await user.click(screen.getByRole("button", { name: "Créer la fiche" }));
    expect(screen.getByText("Indiquez le prénom.")).toBeInTheDocument();

    await user.type(screen.getByLabelText("Prénom du responsable"), "Sarah");
    await user.type(screen.getByLabelText("Nom du responsable"), "Wahéo");
    await user.type(screen.getByLabelText("Téléphone portable"), "77 12 34");
    await user.click(screen.getByRole("button", { name: "Créer la fiche" }));

    expect(await screen.findByRole("region", { name: /Santé : à connaître/ })).toBeInTheDocument();
    const body = calls.find((c) => c.key === "POST /children")?.body as Record<string, any>;
    expect(body.child).toMatchObject({ firstName: "Noa", birthDate: "2024-07-22", sectionId: "sec-m" });
    expect(body.family.family.label).toBe("Famille Wahéo");
    expect(body.family.guardian).toMatchObject({ firstName: "Sarah", relationship: "Mère", legalGuardian: true, payer: true });
  });
});
