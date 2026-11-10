import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { director, healthUp, installed, mockApi, renderAt, session, structure } from "@/test/render";
import type { Me } from "@/features/auth/types";
import { pairingLink, qrDataUrl } from "./DevicesTab";

const fullStructure = {
  ...structure,
  openingHours: [{ dayOfWeek: 1, opensAt: "06:30:00", closesAt: "17:30:00" }],
  contact: { address: "12 rue des Cocotiers", postalCode: "98835", phone: null, email: null },
  bank: { holder: null, bankName: null, iban: null, bic: null, printRib: false },
  passageRules: { propose: true, firstOfMonth: true, blockFull: true },
};
const bebes = { id: "sec-b", name: "Bébés", minAgeMonths: 2, maxAgeMonths: 18, capacity: 12, color: "#9e2f66", displayOrder: 0 };
const moyens = { id: "sec-m", name: "Moyens", minAgeMonths: 20, maxAgeMonths: 30, capacity: 16, color: "#924510", displayOrder: 1 };
const overview = {
  sections: [{ section: bebes, enrolled: 12 }, { section: moyens, enrolled: 9 }],
  totalPlaces: 28,
  capacity: 48,
  gaps: ["Aucune section n’accueille les enfants de 18 à 20 mois."],
  rules: fullStructure.passageRules,
  passages: [{ childId: "c-1", name: "Lila Gope", fromSectionId: "sec-b", toSectionId: "sec-m", on: "2026-12-01", ageMonths: 18, blocked: false }],
};
const run = (id: string, status: string, kind = "AUTOMATIQUE") => ({
  id, kind, author: kind === "AUTOMATIQUE" ? null : "Nathalie Kaloï", startedAt: "2026-10-31T15:00:00Z", finishedAt: "2026-10-31T15:02:00Z",
  status, sizeBytes: status === "REUSSIE" ? 1_288_490_189 : null, fileName: null, copies: status === "REUSSIE" ? 2 : 0,
  detail: status === "ECHEC" ? "Dossier de copie inaccessible" : null,
});
const backups = {
  enabled: true, dailyTime: "02:00:00", destination: "E:\\Sauvegardes", offsite: null, keepDays: 7, keepWeeks: 4, keepMonths: 12,
  keyHandedOn: null, keyHandedBy: null, keyCreated: true, lastTestOn: "2026-10-15", lastTestOk: true, toolsAvailable: true, running: false,
  lastSuccess: run("b-1", "REUSSIE"), nextRun: "2026-11-01T15:00:00Z", stale: false, history: [run("b-2", "ECHEC", "MANUELLE"), run("b-1", "REUSSIE")],
};

const evaluation = {
  state: "EVALUATION", readOnly: false, license: null, renewalFrom: null, deadline: "2026-11-30", daysLeft: 20, pairedTablets: 0,
  desktops: 1, desktopLimit: 2, sections: 2, capacity: 48, version: "dev", developmentKey: false, message: null,
};

function server(user: Me = director, extra: Record<string, Parameters<typeof mockApi>[0][string]> = {}) {
  window.localStorage.setItem("ticagou.refreshToken", "refresh-1");
  return mockApi({
    "GET /setup/status": { body: installed },
    "GET /health": { body: healthUp },
    "POST /auth/refresh": { body: session(user) },
    "GET /structure": { body: fullStructure },
    "GET /sections": { body: [bebes, moyens] },
    "GET /tariff-schedules": { body: [] },
    ...extra,
  });
}

const open = async (name: RegExp) => {
  const user = userEvent.setup();
  await user.click(await screen.findByRole("button", { name }));
  return user;
};

describe("une seule formule", () => {
  it("toutes les rubriques apparaissent à la direction", async () => {
    server(director);
    renderAt("/parametres");

    const nav = await screen.findByRole("navigation", { name: "Rubriques des paramètres" });
    for (const name of [/^Structure/, /^Critères d’inscription/, /^Repas et régimes/, /^Barème et facturation/, /^Financeurs et aides/,
      /^Comptabilité/, /^Utilisateurs et rôles/, /^Sauvegardes/, /^Tablettes et abonnement/, /^Portail parents/]) {
      expect(within(nav).getByRole("button", { name })).toBeInTheDocument();
    }
  });

  it("les rubriques masquées à la demande de la crèche disparaissent (ADR 0022)", async () => {
    server(director, { "GET /rubriques": { body: { hidden: ["FACTURATION", "COMPTABILITE", "PORTAIL_PARENTS"], labels: ["Facturation", "Comptabilité", "Portail parents"] } } });
    renderAt("/parametres");

    const nav = await screen.findByRole("navigation", { name: "Rubriques des paramètres" });
    await waitFor(() => expect(within(nav).queryByRole("button", { name: /^Comptabilité/ })).not.toBeInTheDocument());
    for (const name of [/^Financeurs et aides/, /^Portail parents/]) {
      expect(within(nav).queryByRole("button", { name })).not.toBeInTheDocument();
    }
    expect(within(nav).getByRole("button", { name: /^Barème et facturation/ })).toBeInTheDocument();
  });
});

describe("barème et formules de facturation", () => {
  it("enregistre les formules du barème et les affiche", async () => {
    const tariff = {
      id: "t-1", label: "Barème 2027", criterion: "HEURES_SEMAINE", weeksPerYear: 47, registrationFee: 0,
      validFrom: "2027-01-01", validTo: null, bands: [{ lowerBound: 0, hourlyRate: 620 }],
      formulas: [{ label: "Temps plein", monthlyFee: 98_500 }], used: false, version: 0,
    };
    const { calls } = server(director, {
      "GET /tariff-schedules": { body: [tariff] },
      "PUT /tariff-schedules/t-1": { body: tariff },
      "GET /billing-rules": { body: [] },
    });
    renderAt("/parametres?onglet=bareme");

    const card = await screen.findByRole("region", { name: "Barème 2027" });
    expect(within(card).getByRole("table", { name: "Formules de facturation" })).toHaveTextContent(/Temps plein.*98\s500/);

    const user = userEvent.setup();
    await user.click(within(card).getByRole("button", { name: /Modifier/ }));
    expect(screen.getByLabelText("Libellé de la formule 1")).toHaveValue("Temps plein");
    await user.click(screen.getByRole("button", { name: "Ajouter une formule" }));
    await user.type(screen.getByLabelText("Libellé de la formule 2"), "Matins seulement");
    await user.type(screen.getByLabelText("Forfait mensuel de la formule 2"), "60 000");
    // Une ligne vide est ignorée.
    await user.click(screen.getByRole("button", { name: "Ajouter une formule" }));
    await user.click(screen.getByRole("button", { name: "Enregistrer" }));

    await waitFor(() => expect(calls.find((c) => c.key === "PUT /tariff-schedules/t-1")).toBeDefined());
    expect((calls.find((c) => c.key === "PUT /tariff-schedules/t-1")!.body as { formulas: unknown }).formulas).toEqual([
      { label: "Temps plein", monthlyFee: 98_500 }, { label: "Matins seulement", monthlyFee: 60_000 },
    ]);
  });
});

describe("paramètres", () => {
  it("range les rubriques par groupe et ne montre que la facturation à la comptabilité", async () => {
    server();
    const { unmount } = renderAt("/parametres");
    const nav = await screen.findByRole("navigation", { name: "Rubriques des paramètres" });
    for (const group of ["Établissement", "Facturation", "Accès", "Système"]) expect(within(nav).getByText(group)).toBeInTheDocument();
    expect(within(nav).getByRole("button", { name: /^Structure/ })).toHaveAttribute("aria-current", "page");
    unmount();

    server({ ...director, role: "COMPTABILITE" });
    renderAt("/parametres");
    const accounting = await screen.findByRole("navigation", { name: "Rubriques des paramètres" });
    expect(within(accounting).getAllByRole("button").map((b) => b.querySelector("span")?.textContent)).toEqual([
      "Barème et facturation", "Financeurs et aides", "Comptabilité",
    ]);
  });

  it("enregistre l’identité, les horaires et les coordonnées bancaires", async () => {
    const { calls } = server(director, { "PUT /structure": { body: fullStructure } });
    renderAt("/parametres");
    const user = userEvent.setup();

    expect(await screen.findByLabelText("Adresse")).toHaveValue("12 rue des Cocotiers");
    await user.type(screen.getByLabelText("Titulaire du compte"), "Les P’tits Niaoulis SARL");
    await user.type(screen.getByLabelText("IBAN"), "FR7630006000011234567890189");
    await user.click(screen.getByLabelText("Imprimer le RIB en bas de chaque facture"));
    await user.click(screen.getByLabelText("Samedi ouvert"));
    await user.click(screen.getByRole("button", { name: "Enregistrer" }));

    await waitFor(() => expect(calls.find((c) => c.key === "PUT /structure")).toBeDefined());
    const body = calls.find((c) => c.key === "PUT /structure")!.body as Record<string, unknown>;
    expect(body.bank).toEqual({ holder: "Les P’tits Niaoulis SARL", bankName: null, iban: "FR7630006000011234567890189", bic: null, printRib: true });
    expect(body.contact).toEqual({ address: "12 rue des Cocotiers", postalCode: "98835", phone: null, email: null });
    expect(body.openingHours).toEqual([
      { dayOfWeek: 1, opensAt: "06:30", closesAt: "17:30" },
      { dayOfWeek: 6, opensAt: "06:30", closesAt: "17:30" },
    ]);
    expect(await screen.findByText("Modifications enregistrées.")).toHaveAttribute("role", "status");
  }, 15_000);

  it("signale les trous entre sections et valide un passage", async () => {
    let applied = false;
    const current = () => ({ ...overview, passages: applied ? [] : overview.passages });
    const { calls } = server(director, {
      "GET /sections/overview": () => ({ body: current() }),
      "POST /sections/passages": () => {
        applied = true;
        return { body: current() };
      },
    });
    renderAt("/parametres");
    await open(/^Sections et tranches d’âge/);

    expect(await screen.findByText("Aucune section n’accueille les enfants de 18 à 20 mois.")).toBeInTheDocument();
    const table = screen.getByRole("table", { name: "Sections de la structure" });
    expect(within(table).getByText("28 / 48")).toBeInTheDocument();
    expect(within(table).getByText("Vers Moyens")).toBeInTheDocument();
    expect(within(table).getByText("Sortie de la structure")).toBeInTheDocument();

    const passages = screen.getByRole("list", { name: "Passages à valider" });
    expect(passages).toHaveTextContent(/Lila Gope · 1 an 6 mois le 01\/12\/2026 ·\s*Bébés\s*Moyens/);
    await userEvent.click(within(passages).getByRole("button", { name: "Valider le passage" }));
    await waitFor(() => expect(calls.find((c) => c.key === "POST /sections/passages")?.body).toEqual({ childId: "c-1", toSectionId: "sec-m" }));
    expect(await screen.findByText("Aucun passage à prévoir dans le mois qui vient.")).toBeInTheDocument();
  });

  it("montre l’état des sauvegardes et en lance une", async () => {
    const { calls } = server(director, {
      "GET /backups": { body: backups },
      "POST /backups/run": { status: 202, body: run("b-3", "EN_COURS", "MANUELLE") },
    });
    renderAt("/parametres");
    await open(/^Sauvegardes/);

    expect(await screen.findByText(/1,2 Go · durée 2 min/)).toBeInTheDocument();
    expect(screen.getByText("Dernière tentative en échec")).toBeInTheDocument();
    expect(screen.getByText("7 jours · 4 semaines · 12 mois")).toBeInTheDocument();
    expect(screen.getByText("Dossier de copie inaccessible")).toBeInTheDocument();
    expect(screen.queryByLabelText("Copie 1 — sur place")).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Sauvegarder maintenant" }));
    await waitFor(() => expect(calls.some((c) => c.key === "POST /backups/run")).toBe(true));
    expect(await screen.findByText("Sauvegarde lancée.")).toHaveAttribute("role", "status");
  });

  it("affiche le QR code d’appairage vers le kiosque de cette adresse", async () => {
    const device = { id: "d-1", name: "Accueil — entrée", state: "EN_ATTENTE", createdAt: "2026-11-01T00:00:00Z", pairedAt: null, lastSeenAt: null, userAgent: null, pairingExpiresAt: null };
    server(director, {
      "GET /devices": { body: [] },
      "POST /devices": { status: 201, body: { device, code: "482913", expiresAt: "2026-11-01T00:15:00Z" } },
      "GET /system/license": { body: evaluation },
      "GET /system/desktops": { body: [] },
    });
    renderAt("/parametres");
    const user = await open(/^Tablettes et abonnement/);

    await user.click(await screen.findByRole("button", { name: "Appairer une tablette" }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Obtenir le code" }));
    const dialog = await screen.findByRole("dialog", { name: "Code d’appairage de « Accueil — entrée »" });
    const qr = await within(dialog).findByRole("img", { name: /QR code d’appairage/ });
    expect(qr).toHaveAttribute("alt", `QR code d’appairage : ${window.location.origin}/kiosk?pair=482913`);
    expect(qr.getAttribute("src")).toMatch(/^data:image\/gif;base64,/);

    expect(screen.getByText("Évaluation")).toBeInTheDocument();
  });

  it("produit un QR code différent pour chaque adresse", () => {
    expect(qrDataUrl("http://a/kiosk?pair=1")).not.toEqual(qrDataUrl("http://a/kiosk?pair=2"));
  });

  it("le lien d’appairage est celui de la crèche, sans certificat à installer", () => {
    expect(pairingLink("482913")).toBe(`${window.location.origin}/kiosk?pair=482913`);
  });

  it("liste les PC du bureau comptés par l’abonnement et retire un ancien PC", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const api = server(director, {
      "GET /devices": { body: [] },
      "GET /system/license": { body: { ...evaluation, desktops: 2 } },
      "GET /system/desktops": { body: [
        { id: "pc-1", label: "BUREAU-DIRECTION", firstSeenAt: "2026-09-01T08:00:00Z", lastSeenAt: "2026-11-02T08:00:00Z", lastUserName: "Nathalie Kaloï", active: true },
        { id: "pc-2", label: "ANCIEN-PC", firstSeenAt: "2026-01-01T08:00:00Z", lastSeenAt: "2026-07-01T08:00:00Z", lastUserName: null, active: false },
      ] },
      "POST /system/desktops/pc-2/revoke": { status: 204 },
    });
    renderAt("/parametres?onglet=appareils");
    expect(await screen.findByText("BUREAU-DIRECTION")).toBeInTheDocument();
    expect(screen.getByText(/2 PC sur 2|L’abonnement permet 2 PC/)).toBeInTheDocument();
    expect(screen.getByText(/ne compte plus/)).toBeInTheDocument();
    const user = userEvent.setup();
    const row = screen.getByText("ANCIEN-PC").closest("li")!;
    await user.click(within(row).getByRole("button", { name: "Retirer" }));
    await waitFor(() => expect(api.calls.some((c) => c.key === "POST /system/desktops/pc-2/revoke")).toBe(true));
  });

  it("envoie l’identifiant du poste avec chaque requête", async () => {
    const api = server();
    renderAt("/parametres");
    await screen.findByRole("navigation", { name: "Rubriques des paramètres" });
    const call = api.calls.find((c) => c.key === "GET /structure")!;
    expect(call.headers["X-Ticagou-Poste"]).toMatch(/^[0-9a-f]{32}$/);
    expect(call.headers["X-Ticagou-Poste-Nom"]).toBe("Navigateur");
  });
});

describe("Paramètres › Modèles de documents", () => {
  it("crée les modèles de départ puis un modèle avec un champ de fusion", async () => {
    const { calls } = server(director, {
      "GET /document-templates": { body: [] },
      "GET /document-settings": { body: { signatory: null, hasStamp: false } },
      "GET /document-templates/fields": { body: [{ key: "enfant.prenom", label: "Prénom de l’enfant" }, { key: "date.jour", label: "Date du jour" }] },
      "POST /document-templates/starters": { status: 201, body: [] },
      "POST /document-templates": { status: 201, body: {} },
    });
    renderAt("/parametres");
    const nav = await screen.findByRole("navigation", { name: "Rubriques des paramètres" });
    await userEvent.click(within(nav).getByRole("button", { name: /^Modèles de documents/ }));
    await userEvent.click(await screen.findByRole("button", { name: /Créer les modèles de départ/ }));
    await waitFor(() => expect(calls.some((c) => c.key === "POST /document-templates/starters")).toBe(true));
    await userEvent.click(screen.getByRole("button", { name: /Nouveau modèle/ }));
    await userEvent.type(screen.getByLabelText("Titre"), "Attestation CAFAT");
    await userEvent.type(screen.getByLabelText(/^Texte/), "Pour ");
    await userEvent.click(await screen.findByRole("button", { name: "Prénom de l’enfant" }));
    await userEvent.click(screen.getByRole("button", { name: "Enregistrer le modèle" }));
    await waitFor(() => expect(calls.find((c) => c.key === "POST /document-templates")?.body)
      .toEqual({ subject: "ENFANT", title: "Attestation CAFAT", body: "Pour {{enfant.prenom}}" }));
  });
});

describe("défilement des paramètres", () => {
  it("remonte en haut à chaque rubrique et amène le formulaire d'un modèle à l'écran", async () => {
    const scrollTo = vi.fn();
    const scrollIntoView = vi.fn();
    Object.defineProperty(HTMLElement.prototype, "scrollTo", { configurable: true, value: scrollTo });
    Object.defineProperty(HTMLElement.prototype, "scrollIntoView", { configurable: true, value: scrollIntoView });
    server(director, {
      "GET /document-templates": { body: [{ id: "t1", subject: "ENFANT", title: "Attestation de présence", body: "Texte", version: 1, validFrom: "2026-01-01" }] },
      "GET /document-settings": { body: { signatory: null, hasStamp: false } },
      "GET /document-templates/fields": { body: [] },
    });
    renderAt("/parametres");
    const nav = await screen.findByRole("navigation", { name: "Rubriques des paramètres" });
    scrollTo.mockClear();

    await userEvent.click(within(nav).getByRole("button", { name: /^Modèles de documents/ }));
    await waitFor(() => expect(scrollTo).toHaveBeenCalledWith({ top: 0 }));

    await userEvent.click(await screen.findByRole("button", { name: "Modifier" }));
    expect(await screen.findByRole("region", { name: "Édition du modèle" })).toBeInTheDocument();
    await waitFor(() => expect(scrollIntoView).toHaveBeenCalled());
    expect(scrollIntoView.mock.contexts.at(-1)).toBe(screen.getByRole("region", { name: "Édition du modèle" }));
  });
});
